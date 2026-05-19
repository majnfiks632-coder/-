package com.aiagent.android.kiro.keys

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Контракт пинга для одного провайдера. Реализация делает максимально
 * лёгкий вызов к API, чтобы проверить валидность ключа без расходов на
 * квоту/токены.
 *
 * Должна:
 *   - возвращать [PingResult.Success] при HTTP 2xx или эквиваленте;
 *   - возвращать [PingResult.PermanentlyDead] на 401/403, "invalid api key";
 *   - возвращать [PingResult.Transient] на всё остальное (5xx, timeout,
 *     network unreachable).
 *
 * **Важно**: реализации НЕ должны логировать сам ключ. Только [KeyEntry.masked].
 */
fun interface ProviderPinger {
    suspend fun ping(key: KeyEntry): PingResult
}

sealed class PingResult {
    data object Success : PingResult()
    data class PermanentlyDead(val reason: String) : PingResult()
    data class Transient(val reason: String) : PingResult()
}

/**
 * Фоновой health-check всех пулов. Запускается при старте приложения,
 * крутится бесконечно.
 *
 * Логика на каждый тик:
 *  1) Для каждого провайдера и каждого ключа в [KeyState.DEAD] — пинг
 *     с интервалом [recheckDeadEveryMs] (5 минут default).
 *  2) Для каждого ключа в [KeyState.HEALTHY] / [KeyState.UNSTABLE] —
 *     пинг с интервалом [recheckHealthyEveryMs] (15 минут default,
 *     не часто, чтобы не палить квоту).
 *  3) [KeyState.RESERVE] — пинг раз в час, только для индикации, без
 *     влияния на state.
 *
 * Шаг pause между ключами — [interKeyDelayMs] (500мс) — чтобы не
 * долбить провайдера серией запросов.
 */
class HealthChecker(
    private val keyManager: KeyManager,
    private val pingers: Map<ProviderType, ProviderPinger>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val tickMs: Long = 60_000L,
    private val recheckDeadEveryMs: Long = 5 * 60_000L,
    private val recheckHealthyEveryMs: Long = 15 * 60_000L,
    private val recheckReserveEveryMs: Long = 60 * 60_000L,
    private val interKeyDelayMs: Long = 500L,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(tickMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Один проход по всем пулам. Открыт для прямого вызова из тестов
     * и из ручной кнопки "Проверить ключи" в Settings.
     */
    suspend fun tick() {
        val nowMs = now()
        for (type in ProviderType.values()) {
            val pinger = pingers[type] ?: continue
            val pool = keyManager.pool(type)
            for (entry in pool.state.value.keys) {
                val staleness = nowMs - maxOf(entry.lastSuccessAtMs, entry.lastFailureAtMs)
                val needPing = when (entry.state) {
                    KeyState.DEAD -> staleness >= recheckDeadEveryMs
                    KeyState.HEALTHY, KeyState.UNSTABLE -> staleness >= recheckHealthyEveryMs
                    KeyState.RESERVE -> staleness >= recheckReserveEveryMs
                }
                if (!needPing) continue
                val result = runCatching { pinger.ping(entry) }
                    .getOrElse { PingResult.Transient("exception: ${it.message}") }
                when (result) {
                    is PingResult.Success ->
                        pool.onPingResult(entry.id, success = true, reason = null)
                    is PingResult.PermanentlyDead ->
                        pool.release(entry.id, KeyOperationResult.PermanentError(result.reason))
                    is PingResult.Transient ->
                        pool.onPingResult(entry.id, success = false, reason = result.reason)
                }
                delay(interKeyDelayMs)
            }
        }
    }
}
