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
    /**
     * Chain-of-thought / reasoning text streamed by the model BEFORE the final answer.
     * Captured from providers that emit it (OpenAI reasoning models, DeepSeek-R1 via
     * `reasoning_content`, Claude `thinking` blocks, Groq qwen3 `<think>…</think>`, etc.).
     * Rendered as a collapsible panel above the assistant content. Persisted across
     * app restarts via [ChatStorage].
     */
    val reasoning: String = "",
    /** True while [reasoning] is still streaming. */
    val reasoningPending: Boolean = false,
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

/** One provider's model list (or the failure to fetch it). Rendered as a
 *  collapsible section in [ModelPickerSheet].
 *
 *  [warning] is for the "soft success" path — we have a usable list (either fresh
 *  from `/models` or the backend's hard-coded fallback) but want to tell the user
 *  something went sideways. Most common case: device has no DNS / no internet, so
 *  we show the curated Kiro catalogue with "couldn't reach AWS" attached. */
data class ProviderModels(
    val slot: Int,
    val name: String,
    val result: Result<List<String>>,
    val warning: String? = null,
)

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
    /**
     * Approximate size of the LLM-side conversation history right now, in tokens. Computed
     * client-side by counting the total characters in `llmHistory` and dividing by 4 (a
     * reasonable rule-of-thumb for English/Russian text). Surfaces in the header pill so
     * the user can SEE the context grow as the chat progresses — previously the pill
     * always read 0 because nothing wrote into [totalTokens] for Kiro responses.
     */
    val approxContextTokens: Int = 0,
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
    // Two-provider config: each slot has a free-form label, a base URL, and an API key.
    val activeProvider: Int = 1,
    val provider1Name: String = "Kiro AI",
    val provider1BaseUrl: String = "https://q.us-east-1.amazonaws.com",
    val provider1ApiKey: String = "",
    val provider1ExtraApiKeys: String = "",
    val provider1Transport: String = "kiro",
    val provider2Name: String = "OpenAI-совместимый",
    val provider2BaseUrl: String = "",
    val provider2ApiKey: String = "",
    val provider2ExtraApiKeys: String = "",
    val provider2Transport: String = "openai",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048,
    val systemPrompt: String = "",
    val sendScreenshots: Boolean = false,
    val autoScreenshotEachTurn: Boolean = true,
    val recordUserActions: Boolean = true,
    val joystickEnabled: Boolean = false,
    val settingsOverlayEnabled: Boolean = false,

    /**
     * Master "agent is allowed to drive my phone" switch. Default off — the user explicitly
     * asked for accessibility / device control to be on-demand instead of always-on. When
     * off, UI-driving tools return soft errors that the model sees, and floating overlays
     * (STOP, ⚙, joystick, thoughts island) are never shown.
     */
    val deviceControlEnabled: Boolean = false,

    /**
     * When true, the agent runs inside a foreground service while you switch to another
     * app, and you get a system notification when it finishes. Default on so long-running
     * tool loops don't get killed when the user backgrounds the chat.
     */
    val runInBackground: Boolean = true,

    /**
     * True while the user is currently looking at the chat (resumed activity). Mirror of
     * [com.aiagent.android.util.AppForegroundTracker.isAppForeground] surfaced to the UI
     * so the settings sheet can explain why overlays are hidden right now.
     */
    val appForeground: Boolean = true,

    /**
     * When ON, the agent keeps the loop alive after `done` and waits for the next user
     * message instead of terminating. When OFF, the agent shuts itself down as soon as
     * it reports `done` (or finishes its answer). Mirror of
     * [com.aiagent.android.data.Settings.waitForMessages].
     */
    val waitForMessages: Boolean = true,

    /**
     * Master toggle for the "reasoning" panel. When true, the UI shows the assistant's
     * chain-of-thought (streamed from the model) as a collapsible block above the answer
     * and exposes an «Ответить сразу» button so the user can skip reasoning and force
     * the final answer immediately. When false, reasoning is suppressed entirely — the
     * agent runs without requesting it and the UI never shows a reasoning panel.
     * Mirror of [com.aiagent.android.data.Settings.reasoningModeEnabled].
     */
    val reasoningModeEnabled: Boolean = true,

    /**
     * When true, the app brings itself back to the foreground the moment the agent
     * finishes an answer (done / final assistant message) while the user was in another
     * app. Useful for long-running tasks — the user gets to come back to the result
     * without tapping the notification. Off by default. Mirror of
     * [com.aiagent.android.data.Settings.openAppAfterAnswer].
     */
    val openAppAfterAnswer: Boolean = false,
)
