package com.aiagent.android.kiro.keys

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Снимок пула, удобный для UI и тестов. Иммутабельный.
 */
data class KeyPoolSnapshot(
    val provider: ProviderType,
    val keys: List<KeyEntry>,
) {
    fun byState(state: KeyState): List<KeyEntry> = keys.filter { it.state == state }
    fun healthy(): List<KeyEntry> = byState(KeyState.HEALTHY)
    fun unstable(): List<KeyEntry> = byState(KeyState.UNSTABLE)
    fun dead(): List<KeyEntry> = byState(KeyState.DEAD)
    fun reserve(): List<KeyEntry> = byState(KeyState.RESERVE)
}

/**
 * Пул ключей одного провайдера. Поддерживает round-robin выдачу
 * среди HEALTHY/UNSTABLE, помечает нестабильные и мёртвые по
 * результатам операций, поддерживает RESERVE отдельно (не выдаёт
 * саб-агентам, выдаёт основному только если других нет).
 *
 * Все методы thread-safe (внутри [Mutex]).
 *
 * Не выполняет сетевых операций сам — внешний код (`HealthChecker`,
 * клиенты LLM/STT) сообщает результаты через [release].
 *
 * Источник времени параметризован для тестируемости — production
 * передаёт `System::currentTimeMillis`, тесты — управляемые часы.
 */
class KeyPool(
    val provider: ProviderType,
    initial: List<KeyEntry> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow(KeyPoolSnapshot(provider, initial))
    val state: StateFlow<KeyPoolSnapshot> = _state

    // round-robin индекс, отдельно для основных и для саб-агентов,
    // чтобы они не "сталкивались" на одном ключе
    private var rrPrimaryIdx = 0
    private var rrSubagentIdx = 0

    /**
     * Заменить весь пул ключей. Используется при загрузке из
     * [KeyStorage] и при изменениях из UI Settings.
     */
    suspend fun replaceAll(entries: List<KeyEntry>) = mutex.withLock {
        _state.update { KeyPoolSnapshot(provider, entries) }
    }

    suspend fun add(entry: KeyEntry) = mutex.withLock {
        _state.update { snap -> snap.copy(keys = snap.keys + entry) }
    }

    suspend fun remove(id: String) = mutex.withLock {
        _state.update { snap -> snap.copy(keys = snap.keys.filterNot { it.id == id }) }
    }

    /**
     * Пометить ключ как RESERVE (запасной). RESERVE не выдаётся саб-агентам
     * и выдаётся основному потребителю в самую последнюю очередь.
     */
    suspend fun markReserve(id: String) = mutex.withLock {
        _state.update { snap ->
            snap.copy(keys = snap.keys.map { e ->
                if (e.id == id) e.copy(state = KeyState.RESERVE, cooldownUntilMs = 0L)
                else e
            })
        }
    }

    /** Снять флажок RESERVE → вернуть ключ в HEALTHY. */
    suspend fun unmarkReserve(id: String) = mutex.withLock {
        _state.update { snap ->
            snap.copy(keys = snap.keys.map { e ->
                if (e.id == id && e.state == KeyState.RESERVE) {
                    e.copy(state = KeyState.HEALTHY, consecutiveFailures = 0, consecutiveSuccesses = 0)
                } else e
            })
        }
    }

    /**
     * Выдать рабочий ключ. Алгоритм:
     *   1. Пройтись round-robin по HEALTHY (не на cooldown) → отдать первый.
     *   2. Если нет HEALTHY — пройтись по UNSTABLE (не на cooldown) → отдать.
     *   3. Если [forSubagent] == false — попробовать RESERVE.
     *   4. Иначе null.
     *
     * @param forSubagent Если true, RESERVE никогда не выдаётся. Это защищает
     *   "пожарный" ключ от исчерпания фоновыми задачами вроде веб-поиска.
     */
    suspend fun acquire(forSubagent: Boolean = false): KeyEntry? = mutex.withLock {
        val nowMs = now()
        val keys = _state.value.keys

        // 1) HEALTHY
        pickRoundRobin(keys, KeyState.HEALTHY, forSubagent, nowMs)?.let { return@withLock it }

        // 2) UNSTABLE
        pickRoundRobin(keys, KeyState.UNSTABLE, forSubagent, nowMs)?.let { return@withLock it }

        // 3) RESERVE — только для основного потребителя
        if (!forSubagent) {
            val reserveReady = keys.firstOrNull { it.state == KeyState.RESERVE && nowMs >= it.cooldownUntilMs }
            if (reserveReady != null) return@withLock reserveReady
        }

        return@withLock null
    }

    private fun pickRoundRobin(
        keys: List<KeyEntry>,
        targetState: KeyState,
        forSubagent: Boolean,
        nowMs: Long,
    ): KeyEntry? {
        val candidates = keys.filter { it.state == targetState && nowMs >= it.cooldownUntilMs }
        if (candidates.isEmpty()) return null
        val idxRef = if (forSubagent) ::rrSubagentIdx else ::rrPrimaryIdx
        val current = idxRef.get() % candidates.size
        val picked = candidates[current]
        idxRef.set((current + 1) % candidates.size)
        return picked
    }

    /**
     * Отчитаться о результате операции с ключом — продвинуть state-machine.
     */
    suspend fun release(id: String, result: KeyOperationResult) = mutex.withLock {
        val nowMs = now()
        _state.update { snap ->
            snap.copy(keys = snap.keys.map { e ->
                if (e.id != id) e
                else applyResult(e, result, nowMs)
            })
        }
    }

    /**
     * Применить health-check ping-результат к ключу. Отличается от [release]
     * тем, что более агрессивно поднимает DEAD → HEALTHY при успехе.
     */
    suspend fun onPingResult(id: String, success: Boolean, reason: String?) = mutex.withLock {
        val nowMs = now()
        _state.update { snap ->
            snap.copy(keys = snap.keys.map { e ->
                if (e.id != id) e
                else {
                    if (success) {
                        // DEAD → HEALTHY одним ping'ом, UNSTABLE → HEALTHY,
                        // HEALTHY остаётся HEALTHY. RESERVE не трогаем —
                        // флаг ставит пользователь.
                        when (e.state) {
                            KeyState.DEAD, KeyState.UNSTABLE -> e.copy(
                                state = KeyState.HEALTHY,
                                lastSuccessAtMs = nowMs,
                                consecutiveFailures = 0,
                                consecutiveSuccesses = e.consecutiveSuccesses + 1,
                                cooldownUntilMs = 0L,
                            )
                            KeyState.HEALTHY -> e.copy(
                                lastSuccessAtMs = nowMs,
                                consecutiveSuccesses = e.consecutiveSuccesses + 1,
                            )
                            KeyState.RESERVE -> e.copy(lastSuccessAtMs = nowMs)
                        }
                    } else {
                        // Ping не прошёл — относимся как к одной transient-ошибке.
                        applyResult(e, KeyOperationResult.TransientError(reason ?: "ping failed"), nowMs)
                    }
                }
            })
        }
    }

    private fun applyResult(
        entry: KeyEntry,
        result: KeyOperationResult,
        nowMs: Long,
    ): KeyEntry = when (result) {
        is KeyOperationResult.Success -> {
            val newSuccesses = entry.consecutiveSuccesses + 1
            val newState = when (entry.state) {
                KeyState.UNSTABLE -> if (newSuccesses >= SUCCESSES_TO_HEAL) KeyState.HEALTHY else KeyState.UNSTABLE
                KeyState.DEAD -> entry.state // DEAD поднимается только через ping
                else -> entry.state
            }
            entry.copy(
                state = newState,
                lastSuccessAtMs = nowMs,
                consecutiveFailures = 0,
                consecutiveSuccesses = newSuccesses,
                cooldownUntilMs = 0L,
            )
        }
        is KeyOperationResult.TransientError -> {
            val newFailures = entry.consecutiveFailures + 1
            val newState = when {
                entry.state == KeyState.HEALTHY && newFailures >= FAILURES_TO_DESTABILIZE -> KeyState.UNSTABLE
                entry.state == KeyState.UNSTABLE && newFailures >= FAILURES_TO_KILL -> KeyState.DEAD
                else -> entry.state
            }
            val cooldown = calcCooldownMs(newFailures)
            entry.copy(
                state = if (entry.state == KeyState.RESERVE) entry.state else newState,
                lastFailureAtMs = nowMs,
                consecutiveFailures = newFailures,
                consecutiveSuccesses = 0,
                cooldownUntilMs = nowMs + cooldown,
                recentErrors = (entry.recentErrors + result.reason).takeLast(KeyEntry.MAX_RECENT_ERRORS),
            )
        }
        is KeyOperationResult.PermanentError -> entry.copy(
            // RESERVE не сдвигаем в DEAD — это решение пользователя.
            // Но фиксируем ошибку, чтобы было видно.
            state = if (entry.state == KeyState.RESERVE) entry.state else KeyState.DEAD,
            lastFailureAtMs = nowMs,
            consecutiveFailures = entry.consecutiveFailures + 1,
            consecutiveSuccesses = 0,
            cooldownUntilMs = nowMs + COOLDOWN_DEAD_RECHECK_MS,
            recentErrors = (entry.recentErrors + result.reason).takeLast(KeyEntry.MAX_RECENT_ERRORS),
        )
    }

    /**
     * Экспоненциальный cooldown для transient-ошибок.
     * 1 failure → 30с, 2 → 60с, 3 → 2мин, 4 → 5мин, 5+ → 10мин.
     */
    private fun calcCooldownMs(consecutiveFailures: Int): Long = when (consecutiveFailures) {
        in 0..1 -> 30_000L
        2 -> 60_000L
        3 -> 120_000L
        4 -> 300_000L
        else -> 600_000L
    }

    companion object {
        /** Сколько transient-ошибок подряд переводят HEALTHY → UNSTABLE. */
        const val FAILURES_TO_DESTABILIZE = 2

        /** Сколько transient-ошибок суммарно переводят UNSTABLE → DEAD. */
        const val FAILURES_TO_KILL = 5

        /** Сколько успехов подряд поднимают UNSTABLE → HEALTHY. */
        const val SUCCESSES_TO_HEAL = 3

        /** Как часто фоном перепроверяется DEAD ключ. */
        const val COOLDOWN_DEAD_RECHECK_MS = 300_000L // 5 минут
    }
}
