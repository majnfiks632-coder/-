package com.aiagent.android.ui.chat

/** Author of a chat bubble. */
enum class ChatRole { USER, ASSISTANT, SYSTEM }

/**
 * One visible message in the chat transcript. Tool calls and intermediate
 * thinking steps are folded into the assistant message that immediately
 * follows them (see [ChatMessage.toolEvents]) instead of producing extra
 * bubbles — that's what Kiro's UI does and it keeps the timeline compact.
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    /** Markdown body. Streamed responses mutate this field in place. */
    val content: String = "",
    val attachments: List<UiAttachment> = emptyList(),
    /** Tool-calls / system notes piped through this message while it was streaming. */
    val toolEvents: List<ToolEvent> = emptyList(),
    /** Optional usage stats (assistant messages only). */
    val usage: TurnUsage? = null,
    /** Model id that produced this assistant message. */
    val model: String? = null,
    /** True while the assistant message is still streaming. */
    val pending: Boolean = false,
    /** Set if the turn ended with an error. */
    val error: String? = null,
    /** When the assistant declared `done`, the human-readable summary. */
    val doneSummary: String? = null,
    /** When the agent is blocked on a user reply, the question text lives here. */
    val pendingQuestion: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A single tool invocation rendered as a collapsible card inside the bubble. */
data class ToolEvent(
    val id: String,
    val name: String,
    val arguments: String,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** File / image attached to a user message. The `dataUri` carries either a
 *  `content://` URI for arbitrary docs or a `data:image/...;base64,...` URL
 *  for an inline preview. */
data class UiAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    /** `data:image/...;base64,...` for images, or `content://` for docs. */
    val dataUri: String,
)

enum class AttachmentKind { IMAGE, FILE }

/** Single-turn token usage (Kiro / OpenAI shape). */
data class TurnUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

/** Aggregated usage across all turns of a chat. */
data class UsageStats(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val messages: Int = 0,
    val byModel: Map<String, Int> = emptyMap(),
) {
    fun add(turn: TurnUsage, model: String): UsageStats = copy(
        promptTokens = promptTokens + turn.promptTokens,
        completionTokens = completionTokens + turn.completionTokens,
        totalTokens = totalTokens + turn.totalTokens,
        messages = messages + 1,
        byModel = byModel.toMutableMap().apply {
            put(model, (get(model) ?: 0) + turn.totalTokens)
        },
    )
}

/** UI state surfaced by [ChatViewModel] to the Composable tree. */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val model: String = DEFAULT_KIRO_MODEL,
    val usage: UsageStats = UsageStats(),
    val quotaCap: Int = 0,
    val isStreaming: Boolean = false,
    /** Set while the agent is blocked on a `ask_user` tool call. */
    val pendingQuestionMessageId: String? = null,

    // Permission status (mirrors what the legacy AI Agent surfaced).
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val manageStorageGranted: Boolean = false,
    val micGranted: Boolean = false,

    // System-bar visibility for settings popups.
    val settingsOpen: Boolean = false,
    val modelPickerOpen: Boolean = false,
    val quotaPanelOpen: Boolean = false,

    // Mirror of [com.aiagent.android.data.Settings] for the settings sheet.
    val apiKey: String = "",
    val baseUrl: String = "",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048,
    val systemPrompt: String = "",
    val sendScreenshots: Boolean = false,
    val autoScreenshotEachTurn: Boolean = true,
    val recordUserActions: Boolean = true,
    val joystickEnabled: Boolean = false,
    val settingsOverlayEnabled: Boolean = false,
)
