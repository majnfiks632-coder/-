package com.aiagent.android.kiro.keys

/**
 * Результат завершения операции, выполненной с участием ключа. Передаётся
 * в [KeyPool.release] для обновления state-machine ключа.
 *
 *  - [Success] — операция завершилась штатно. Сбрасывает счётчик
 *    consecutive failures, продвигает [KeyState.UNSTABLE] → [KeyState.HEALTHY]
 *    при достижении [KeyPool.SUCCESSES_TO_HEAL] подряд, [KeyState.DEAD] →
 *    [KeyState.HEALTHY] после успешного re-ping.
 *  - [TransientError] — временная ошибка (5xx, network timeout, rate limit).
 *    Накапливает счётчик; при достижении порогов даунгрейдит состояние.
 *  - [PermanentError] — фатальная ошибка для этого ключа (401, 403, "key
 *    revoked", "quota exhausted"). Сразу переводит в [KeyState.DEAD].
 */
sealed class KeyOperationResult {

    data object Success : KeyOperationResult()

    /** Например: HTTP 5xx, timeout, network unreachable, rate limit 429. */
    data class TransientError(val reason: String) : KeyOperationResult()

    /** Например: HTTP 401/403, "Invalid API key", "Quota exceeded for billing cycle". */
    data class PermanentError(val reason: String) : KeyOperationResult()
}
