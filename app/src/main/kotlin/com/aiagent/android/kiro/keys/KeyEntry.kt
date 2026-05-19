package com.aiagent.android.kiro.keys

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Запись о ключе внутри [KeyPool]. Иммутабельная — изменения отражены
 * созданием нового экземпляра через [copy]. Это нужно, чтобы UI
 * подписанный на [kotlinx.coroutines.flow.StateFlow] корректно
 * перерисовывался при изменении состояния.
 *
 * @param id Стабильный идентификатор для UI (key-карточки). Привязан
 *   к моменту добавления ключа; даже если raw поменяется (revoke
 *   старого, ввод нового с тем же label), id остаётся.
 * @param label Человекочитаемое имя ("Ключ 1", "Soniox account #3"). Можно
 *   менять в Settings.
 * @param raw Сам секрет (ksk_..., 64-hex и т.п.). Хранится в
 *   EncryptedSharedPreferences через [KeyStorage].
 * @param state Текущее состояние, см. [KeyState].
 * @param lastSuccessAtMs / [lastFailureAtMs] Метки времени `System.currentTimeMillis()`
 *   последнего успеха и последней ошибки. Используются health-check'ом
 *   и UI для индикации "когда последний раз отвечал".
 * @param consecutiveFailures Количество последних подряд ошибок (без
 *   успехов между ними). Сбрасывается на 0 после [KeyOperationResult.Success].
 * @param consecutiveSuccesses Аналогично для подряд успехов. Используется
 *   для подъёма [KeyState.UNSTABLE] обратно в [KeyState.HEALTHY] и
 *   [KeyState.DEAD] в [KeyState.HEALTHY] через health-check.
 * @param cooldownUntilMs До этой метки времени ключ не выдаётся
 *   из [KeyPool.acquire], даже если [state] == [KeyState.HEALTHY].
 *   Cooldown растёт экспоненциально для повторных transient-ошибок:
 *   30с / 60с / 2мин / 5мин / 10мин.
 * @param recentErrors Кольцевой список из последних [MAX_RECENT_ERRORS]
 *   причин ошибок — для UI диагностики.
 */
@Serializable
data class KeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val raw: String,
    val state: KeyState = KeyState.HEALTHY,
    val lastSuccessAtMs: Long = 0L,
    val lastFailureAtMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val cooldownUntilMs: Long = 0L,
    val recentErrors: List<String> = emptyList(),
) {

    /**
     * Маскированное представление для логов/UI: показывает первые
     * 4 символа после префикса и последние 4. Сам ключ никогда не
     * логируется.
     */
    fun masked(): String {
        if (raw.length <= 8) return "***"
        val head = raw.take(4)
        val tail = raw.takeLast(4)
        return "$head…$tail"
    }

    /** Можно ли выдать ключ сейчас, не считая cooldown. */
    fun isUsable(): Boolean = state == KeyState.HEALTHY || state == KeyState.UNSTABLE

    /** Не на cooldown'е и в выдаваемом состоянии. */
    fun isReadyToAcquire(nowMs: Long): Boolean =
        isUsable() && nowMs >= cooldownUntilMs

    companion object {
        const val MAX_RECENT_ERRORS = 5
    }
}
