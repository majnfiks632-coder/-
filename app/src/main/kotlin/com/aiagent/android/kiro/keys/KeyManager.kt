package com.aiagent.android.kiro.keys

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Корневой менеджер ключей всех провайдеров. Внутри держит [KeyPool] на
 * каждый [ProviderType]. Это единая точка входа для получения и возврата
 * ключей из приложения — клиенты ([com.aiagent.android.kiro.KiroClient],
 * Soniox-клиент в PR-3 и т.д.) ничего не знают про state-machine, только
 * вызывают [acquire] / [release].
 *
 * Сериализация / десериализация — через [KeyStorage] (EncryptedSharedPreferences).
 * Health-check — через [HealthChecker], запускается фоном из приложения.
 *
 * Жизненный цикл: один экземпляр на процесс (Application-scoped).
 */
class KeyManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val pools: Map<ProviderType, KeyPool> = ProviderType.values()
        .associateWith { type -> KeyPool(type, now = now) }

    /** Состояние всех пулов. Удобно для UI Settings → KeysScreen. */
    val allPoolsState: StateFlow<Map<ProviderType, KeyPoolSnapshot>> = run {
        val flows = pools.map { (type, pool) -> pool.state.map { type to it } }
        combine(flows) { snapshots -> snapshots.toMap() }
            .stateIn(scope, SharingStarted.Eagerly, pools.mapValues { it.value.state.value })
    }

    fun pool(provider: ProviderType): KeyPool =
        pools[provider] ?: error("Unknown provider: $provider")

    suspend fun acquire(provider: ProviderType, forSubagent: Boolean = false): KeyEntry? =
        pool(provider).acquire(forSubagent)

    suspend fun release(provider: ProviderType, id: String, result: KeyOperationResult) {
        pool(provider).release(id, result)
    }

    /**
     * Перезагрузить пул из персистентного хранилища. Вызывается при
     * старте приложения или после ручного импорта ключей.
     */
    suspend fun loadFrom(storage: KeyStorage) {
        ProviderType.values().forEach { type ->
            val entries = storage.load(type)
            pool(type).replaceAll(entries)
        }
    }

    /**
     * Сохранить текущее состояние всех пулов в EncryptedSharedPreferences.
     */
    suspend fun saveTo(storage: KeyStorage) {
        ProviderType.values().forEach { type ->
            storage.save(type, pool(type).state.value.keys)
        }
    }
}
