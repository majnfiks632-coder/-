package com.aiagent.android.llm

import com.aiagent.android.data.Settings

/**
 * Common interface for both transport flavours the app supports:
 *  - OpenAI-compatible HTTP API ([LlmClient]) — for OpenAI, OpenRouter, vLLM, …
 *  - Kiro AI native transport ([com.aiagent.android.kiro.KiroClient]) — talks the
 *    AWS CodeWhisperer Streaming protocol directly with the user's `ksk_…` key,
 *    no third-party proxy or gateway.
 *
 * The agent, the UI and the STT layer all consume this interface, so swapping
 * the backend is a matter of changing `Settings.transport`.
 */
interface ChatBackend {
    suspend fun chat(request: ChatRequest): ChatResponse

    /**
     * Streaming variant of [chat]: invokes [onDelta] with each new assistant content
     * chunk as it arrives from the wire, and returns the assembled final [ChatResponse]
     * when the stream completes. The default implementation falls back to [chat] and
     * emits the entire response as a single delta — backends that genuinely stream
     * (OpenAI SSE / Kiro Event Stream) override this.
     *
     * [onReasoning] (optional) is invoked with each new chain-of-thought / reasoning
     * fragment, when the provider emits one separately from the answer. OpenAI-shape
     * SSE servers usually expose this via `delta.reasoning_content` (DeepSeek-R1 style)
     * or `delta.reasoning`. When the model embeds reasoning inline as `<think>…</think>`
     * inside the regular content stream, the backend may split it client-side and route
     * the inner text here instead — but that's an implementation detail of each backend.
     * Default no-op for callers that don't care about reasoning.
     */
    suspend fun chatStream(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): ChatResponse {
        val response = chat(request)
        val text = response.choices.firstOrNull()?.message?.contentText.orEmpty()
        if (text.isNotEmpty()) onDelta(text)
        return response
    }

    suspend fun listModels(): List<String>

    /**
     * Hard-coded «these definitely work» model IDs that the picker can show when
     * [listModels] is unreachable — e.g. when the device has no DNS / no internet,
     * the endpoint is firewalled, or the user hasn't filled in a Base URL yet.
     *
     * Default is an empty list (no offline knowledge). Override for backends with a
     * well-known fixed catalogue (Kiro AI / Amazon Q).
     */
    fun fallbackModels(): List<String> = emptyList()

    fun close()
}

object ChatBackends {
    fun forActiveProvider(settings: Settings): ChatBackend {
        val pool = settings.apiKeyPool
        return when (settings.transport) {
            Settings.TRANSPORT_KIRO -> com.aiagent.android.kiro.KiroClient(
                baseUrl = settings.baseUrl.ifBlank { Settings.DEFAULT_KIRO_BASE_URL },
                apiKeys = pool.ifEmpty { listOf(settings.apiKey) },
                onKeyPromoted = { newPrimary -> settings.promoteApiKey(newPrimary) },
            )
            else -> LlmClient(
                baseUrl = settings.baseUrl,
                apiKeys = pool.ifEmpty { listOf(settings.apiKey) },
                onKeyPromoted = { newPrimary -> settings.promoteApiKey(newPrimary) },
            )
        }
    }

    fun forSlot(slot: ProviderSlot): ChatBackend =
        when (slot.transport) {
            Settings.TRANSPORT_KIRO -> com.aiagent.android.kiro.KiroClient(
                baseUrl = slot.baseUrl.ifBlank { Settings.DEFAULT_KIRO_BASE_URL },
                apiKey = slot.apiKey,
            )
            else -> LlmClient(baseUrl = slot.baseUrl, apiKey = slot.apiKey)
        }
}

/** Snapshot of one configured provider slot, used by the Settings UI. */
data class ProviderSlot(
    val index: Int,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val transport: String,
)
