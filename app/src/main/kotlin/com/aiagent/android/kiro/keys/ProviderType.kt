package com.aiagent.android.kiro.keys

/**
 * Все поддерживаемые провайдеры API-ключей. Каждый имеет свой [HealthChecker]
 * для пинга и свой формат "сырого" ключа.
 *
 * Имена строго соответствуют тому, что пользователь видит в UI настроек.
 */
enum class ProviderType(val displayName: String, val keyPrefixHint: String) {
    KIRO(displayName = "Kiro AI", keyPrefixHint = "ksk_"),
    SONIOX(displayName = "Soniox STT", keyPrefixHint = ""),
    DEEPGRAM(displayName = "Deepgram STT", keyPrefixHint = ""),
    YANDEX(displayName = "Yandex SpeechKit", keyPrefixHint = "AQVN"),
    OPENAI(displayName = "OpenAI", keyPrefixHint = "sk-"),
    ELEVENLABS(displayName = "ElevenLabs", keyPrefixHint = ""),
}
