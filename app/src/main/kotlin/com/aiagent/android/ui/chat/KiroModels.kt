package com.aiagent.android.ui.chat

/**
 * Curated set of model presets carried over from the original Kiro Mobile
 * Chat (`src/lib/models.ts`). The selector renders this list verbatim so the
 * UI stays visually identical; the underlying calls go through the standard
 * OpenAI-compatible client configured in Settings, so any of these IDs can be
 * proxied through Kiro / OpenRouter / a self-hosted gateway by setting the
 * matching `baseUrl` in Settings → API.
 */
data class KiroModel(
    val id: String,
    val label: String,
    val context: String,
    val description: String,
)

val KIRO_MODELS: List<KiroModel> = listOf(
    KiroModel(
        id = "auto",
        label = "Auto",
        context = "1M",
        description = "Kiro подбирает модель автоматически",
    ),
    KiroModel(
        id = "claude-opus-4.7",
        label = "Claude Opus 4.7",
        context = "1M",
        description = "Самая мощная Claude Opus с большим контекстом",
    ),
    KiroModel(
        id = "claude-opus-4.6",
        label = "Claude Opus 4.6",
        context = "1M",
        description = "Claude Opus с расширенным контекстом",
    ),
    KiroModel(
        id = "claude-opus-4.5",
        label = "Claude Opus 4.5",
        context = "200K",
        description = "Сильная Claude Opus для сложных задач",
    ),
    KiroModel(
        id = "claude-sonnet-4.6",
        label = "Claude Sonnet 4.6",
        context = "1M",
        description = "Быстрая Claude Sonnet с большим контекстом",
    ),
    KiroModel(
        id = "claude-sonnet-4.5",
        label = "Claude Sonnet 4.5",
        context = "200K",
        description = "Универсальная Claude Sonnet",
    ),
    KiroModel(
        id = "claude-sonnet-4",
        label = "Claude Sonnet 4",
        context = "200K",
        description = "Базовая Claude Sonnet",
    ),
    KiroModel(
        id = "claude-haiku-4.5",
        label = "Claude Haiku 4.5",
        context = "200K",
        description = "Самая быстрая и дешёвая Claude",
    ),
    KiroModel(
        id = "qwen3-coder-next",
        label = "Qwen3 Coder Next",
        context = "256K",
        description = "Спец-модель для кода",
    ),
    KiroModel(
        id = "deepseek-3.2",
        label = "DeepSeek 3.2",
        context = "164K",
        description = "DeepSeek общего назначения",
    ),
    KiroModel(
        id = "minimax-m2.5",
        label = "MiniMax M2.5",
        context = "196K",
        description = "MiniMax новой версии",
    ),
    KiroModel(
        id = "minimax-m2.1",
        label = "MiniMax M2.1",
        context = "196K",
        description = "MiniMax стабильной версии",
    ),
)

/**
 * Default model id stored in [com.aiagent.android.data.Settings.model] when the user
 * has never picked one. Matches `Settings.DEFAULT_MODEL` so the picker and the agent
 * agree on day-1 behaviour.
 */
const val DEFAULT_KIRO_MODEL: String = "claude-opus-4.7"

/** Looks up a curated model by id. Returns the entry, or null if the id is a
 *  user-typed free-form name pointing at a custom OpenAI-compatible model. */
fun findKiroModel(id: String): KiroModel? = KIRO_MODELS.firstOrNull { it.id == id }

/** Render label for the pill button — falls back to the raw id for custom models. */
fun kiroModelLabel(id: String): String = findKiroModel(id)?.label ?: id.ifBlank { "Auto" }
