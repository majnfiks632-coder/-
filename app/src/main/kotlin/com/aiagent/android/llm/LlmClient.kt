package com.aiagent.android.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Thin OpenAI-compatible chat-completions client. */
@OptIn(ExperimentalSerializationApi::class)
class LlmClient(
    private val baseUrl: String,
    apiKeys: List<String>,
    private val onKeyPromoted: (String) -> Unit = {},
) : ChatBackend {
    /**
     * Convenience constructor for the single-key case (used by [ChatBackends.forSlot] and
     * any older call site that didn't migrate to the pool API).
     */
    constructor(baseUrl: String, apiKey: String) : this(
        baseUrl = baseUrl,
        apiKeys = listOf(apiKey),
        onKeyPromoted = {},
    )

    /** Ordered list of API keys to try; first one is primary. Blanks trimmed, dedupéd. */
    private val keyPool: List<String> = apiKeys.map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Currently "active" key. Starts at the head of [keyPool] and is promoted forwards
     * inside the request loop when a fallback succeeds, so subsequent retries inside the
     * same call use the working key directly. The persistent rotation is handled by
     * [onKeyPromoted] which the [ChatBackends] factory wires to [Settings.promoteApiKey].
     */
    private val apiKey: String
        get() = keyPool.firstOrNull().orEmpty()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        if (keyPool.isEmpty()) {
            throw LlmException("API key is empty — вставь ключ в Настройки.")
        }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        var lastAuthError: LlmException? = null
        for ((keyIdx, key) in keyPool.withIndex()) {
            try {
                val response = chatWithKey(url, request, key)
                if (keyIdx > 0) runCatching { onKeyPromoted(key) }
                return response
            } catch (e: LlmException) {
                if (isKeyExhaustedError(e)) {
                    lastAuthError = e
                    continue
                }
                throw e
            }
        }
        throw lastAuthError ?: LlmException("API: ни один ключ не сработал. Проверь Настройки.")
    }

    private suspend fun chatWithKey(url: String, request: ChatRequest, key: String): ChatResponse {
        var lastError: String = ""
        repeat(MAX_RETRIES) { attempt ->
            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                if (key.isNotBlank()) {
                    header("Authorization", "Bearer $key")
                }
                setBody(request)
            }
            if (response.status == HttpStatusCode.OK) {
                return response.body()
            }
            val body = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString(ApiErrorBody.serializer(), body) }
                .getOrNull()
            lastError = parsed?.error?.message ?: body.take(500)

            // Honour Retry-After / retry-after for 429 and 5xx, else throw.
            val statusCode = response.status.value
            val retryable = statusCode == 429 || statusCode in 500..599
            if (!retryable || attempt == MAX_RETRIES - 1) {
                throw LlmException("HTTP $statusCode: $lastError")
            }
            val retryAfterHeader = response.headers["Retry-After"] ?: response.headers["retry-after"]
            val delaySec = retryAfterHeader?.toDoubleOrNull() ?: extractRetryDelay(lastError) ?: DEFAULT_RETRY_DELAY_SEC
            delay((delaySec * 1000).toLong().coerceAtMost(60_000))
        }
        throw LlmException("HTTP retries exhausted: $lastError")
    }

    /** True when the error is sticky enough to warrant trying the next key. */
    private fun isKeyExhaustedError(e: LlmException): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 401") ||
            msg.contains("HTTP 403") ||
            msg.contains("HTTP 429") ||
            msg.contains("invalid_api_key", ignoreCase = true) ||
            msg.contains("insufficient_quota", ignoreCase = true) ||
            msg.contains("quota", ignoreCase = true) ||
            msg.contains("unauthorized", ignoreCase = true) ||
            msg.contains("forbidden", ignoreCase = true) ||
            msg.contains("expired", ignoreCase = true)
    }

    /** Some providers (e.g. Groq) embed "try again in N.Ns" inside the error message body. */
    private fun extractRetryDelay(message: String): Double? {
        val regex = Regex("""try again in ([0-9]+(?:\.[0-9]+)?)s""", RegexOption.IGNORE_CASE)
        return regex.find(message)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * GET `{baseUrl}/models` (OpenAI-compatible). Returns model IDs.
     */
    override suspend fun listModels(): List<String> {
        val url = baseUrl.trimEnd('/') + "/models"
        val response: HttpResponse = client.get(url) {
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer $apiKey")
            }
        }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            throw LlmException("HTTP ${response.status.value}: ${body.take(500)}")
        }
        val parsed = response.body<ModelListResponse>()
        return parsed.data.map { it.id }.distinct().sorted()
    }

    /**
     * Streaming variant: POST `{baseUrl}/chat/completions` with `stream:true` and parse the
     * SSE `data:` events into incremental content/tool_call deltas. [onDelta] is called with
     * each text chunk as it arrives — wire it into the UI to get real-time typing.
     *
     * Returns the fully assembled non-streaming-shaped [ChatResponse] so callers that don't
     * care about deltas (the rest of the Agent loop) keep working unchanged.
     */
    override suspend fun chatStream(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ChatResponse {
        if (keyPool.isEmpty()) {
            throw LlmException("API key is empty — вставь ключ в Настройки.")
        }
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        // Inject `stream: true` into the request body. We do it via raw JSON because the
        // ChatRequest data class doesn't declare the field (and adding it would force every
        // caller to set it). Serialise the original ChatRequest, parse to a JsonObject,
        // append the streaming flag, encode back to string.
        val baseJson = json.encodeToJsonElement(ChatRequest.serializer(), request).jsonObject
        val streamingJson = buildJsonObject {
            baseJson.forEach { (k, v) -> put(k, v) }
            put("stream", JsonPrimitive(true))
        }
        val body = streamingJson.toString()

        var lastAuthError: LlmException? = null
        for ((keyIdx, key) in keyPool.withIndex()) {
            try {
                val response = chatStreamWithKey(url, body, key, onDelta, onReasoning)
                if (keyIdx > 0) runCatching { onKeyPromoted(key) }
                return response
            } catch (e: LlmException) {
                if (isKeyExhaustedError(e)) {
                    lastAuthError = e
                    continue
                }
                throw e
            }
        }
        throw lastAuthError ?: LlmException("API: ни один ключ не сработал. Проверь Настройки.")
    }

    private suspend fun chatStreamWithKey(
        url: String,
        body: String,
        key: String,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ChatResponse {
        var lastError = ""
        repeat(MAX_RETRIES) { attempt ->
            // Open the SSE stream. ktor's `preparePost` gives us a streaming statement we
            // can read the body line-by-line from.
            val statement = client.preparePost(url) {
                contentType(ContentType.Application.Json)
                header("Accept", "text/event-stream")
                if (key.isNotBlank()) header("Authorization", "Bearer $key")
                setBody(body)
            }
            // Outcome holders so we can break out of `repeat` cleanly. `assembled` is the
            // happy-path response; a non-null `transientErr` means we should retry after a
            // backoff; throwing an LlmException inside the block aborts immediately.
            var assembled: ChatResponse? = null
            var transientErr: Long? = null
            statement.execute { response ->
                if (response.status == HttpStatusCode.OK) {
                    val channel = response.bodyAsChannel()
                    assembled = consumeSseChunks(channel, onDelta, onReasoning)
                    return@execute
                }
                val errBody = response.bodyAsText()
                val parsed = runCatching {
                    json.decodeFromString(ApiErrorBody.serializer(), errBody)
                }.getOrNull()
                lastError = parsed?.error?.message ?: errBody.take(500)
                val statusCode = response.status.value
                val retryable = statusCode == 429 || statusCode in 500..599
                if (!retryable || attempt == MAX_RETRIES - 1) {
                    throw LlmException("HTTP $statusCode: $lastError")
                }
                val retryAfter = response.headers["Retry-After"] ?: response.headers["retry-after"]
                val delaySec = retryAfter?.toDoubleOrNull()
                    ?: extractRetryDelay(lastError)
                    ?: DEFAULT_RETRY_DELAY_SEC
                transientErr = (delaySec * 1000).toLong().coerceAtMost(60_000)
            }
            assembled?.let { return it }
            transientErr?.let { delay(it) }
        }
        throw LlmException("HTTP retries exhausted (stream): $lastError")
    }

    /**
     * Drain an OpenAI-compatible Server-Sent-Events body off [channel], forwarding each
     * `delta.content` to [onDelta], and assemble the final [ChatResponse] for the caller.
     *
     * SSE wire format (each event is separated by a blank line):
     *
     *     data: {"id":"…","choices":[{"index":0,"delta":{"content":"hi"},...}]}
     *     data: {"id":"…","choices":[{"index":0,"delta":{"content":" there"},...}]}
     *     data: [DONE]
     *
     * We collect deltas per-choice so the assembled response preserves multi-choice replies
     * (rare in chat mode but supported by OpenAI). Tool-call deltas are also merged so the
     * agent's tool loop continues to work \u2014 OpenAI streams `tool_calls[i].function.arguments`
     * one fragment at a time.
     */
    internal suspend fun consumeSseChunks(
        channel: ByteReadChannel,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
    ): ChatResponse {
        // Per-choice running state. Index is preserved as map key.
        val contents = mutableMapOf<Int, StringBuilder>()
        val reasonings = mutableMapOf<Int, StringBuilder>()
        val toolCalls = mutableMapOf<Int, MutableMap<Int, StreamedToolCall>>()
        val finishReasons = mutableMapOf<Int, String?>()
        var id: String? = null
        // Per-choice <think>…</think> splitter. Some providers (notably Groq/qwen3 and a few
        // self-hosted vLLM setups for R1-style models) don't expose `reasoning_content` and
        // instead inline the chain-of-thought as a `<think>…</think>` block at the start of
        // the regular content stream. We detect those blocks across chunk boundaries and
        // route their inner text to [onReasoning] so the UI shows it in the reasoning panel
        // — the final answer streams as usual to [onDelta] without the markup.
        val thinkSplitters = mutableMapOf<Int, ThinkSplitter>()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue
            val chunk: JsonObject = runCatching {
                json.parseToJsonElement(payload).jsonObject
            }.getOrNull() ?: continue
            id = id ?: (chunk["id"] as? JsonPrimitive)?.contentOrNull
            val choicesArr = (chunk["choices"] as? JsonArray) ?: continue
            for (choiceElem in choicesArr) {
                val choice = (choiceElem as? JsonObject) ?: continue
                val idx = (choice["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val delta = (choice["delta"] as? JsonObject)
                if (delta != null) {
                    // Reasoning fragments — OpenAI shape uses `reasoning_content` (DeepSeek-R1),
                    // OpenRouter / some self-hosted servers use `reasoning`. We accept either.
                    val reasoningFrag = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                        ?: (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
                    if (!reasoningFrag.isNullOrEmpty()) {
                        reasonings.getOrPut(idx) { StringBuilder() }.append(reasoningFrag)
                        onReasoning(reasoningFrag)
                    }
                    val contentFrag = (delta["content"] as? JsonPrimitive)?.contentOrNull
                    if (!contentFrag.isNullOrEmpty()) {
                        // Some providers inline reasoning as `<think>…</think>` inside the
                        // content stream. Run the chunk through a stateful splitter so the
                        // outer answer goes to [onDelta] and the inner reasoning to
                        // [onReasoning]. Across chunk boundaries the splitter keeps its
                        // "inside <think>" state so a tag straddling two SSE events still
                        // routes correctly.
                        val splitter = thinkSplitters.getOrPut(idx) { ThinkSplitter() }
                        val (answerPart, reasoningPart) = splitter.feed(contentFrag)
                        if (answerPart.isNotEmpty()) {
                            contents.getOrPut(idx) { StringBuilder() }.append(answerPart)
                            onDelta(answerPart)
                        }
                        if (reasoningPart.isNotEmpty()) {
                            reasonings.getOrPut(idx) { StringBuilder() }.append(reasoningPart)
                            onReasoning(reasoningPart)
                        }
                    }
                    val tcArr = delta["tool_calls"] as? JsonArray
                    if (tcArr != null) {
                        val bucket = toolCalls.getOrPut(idx) { mutableMapOf() }
                        for (tcElem in tcArr) {
                            val tc = (tcElem as? JsonObject) ?: continue
                            val tcIdx = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0
                            val state = bucket.getOrPut(tcIdx) { StreamedToolCall() }
                            (tc["id"] as? JsonPrimitive)?.contentOrNull?.let { state.id = it }
                            (tc["type"] as? JsonPrimitive)?.contentOrNull?.let { state.type = it }
                            val fn = tc["function"] as? JsonObject
                            if (fn != null) {
                                (fn["name"] as? JsonPrimitive)?.contentOrNull?.let { state.name.append(it) }
                                (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { state.arguments.append(it) }
                            }
                        }
                    }
                }
                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let {
                    finishReasons[idx] = it
                }
            }
        }

        // Assemble a synthetic non-streaming response from the buffered deltas. Each choice
        // becomes one Choice with role="assistant" and the merged content + tool_calls.
        val allIndices = (contents.keys + toolCalls.keys + finishReasons.keys).distinct().sorted()
        val choicesOut = allIndices.map { idx ->
            val text = contents[idx]?.toString().orEmpty()
            val toolCallList = toolCalls[idx]?.toSortedMap()?.values
                ?.filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                ?.map { tc ->
                    ToolCall(
                        id = tc.id.ifEmpty { "call_${idx}_$idx" },
                        type = tc.type.ifEmpty { "function" },
                        function = FunctionCall(
                            name = tc.name.toString(),
                            arguments = tc.arguments.toString(),
                        ),
                    )
                }
                ?.takeIf { it.isNotEmpty() }
            Choice(
                index = idx,
                message = ChatMessage(
                    role = "assistant",
                    content = if (text.isNotEmpty()) JsonPrimitive(text) else null,
                    toolCalls = toolCallList,
                ),
                finishReason = finishReasons[idx] ?: "stop",
            )
        }
        return ChatResponse(id = id, choices = choicesOut.ifEmpty { listOf(emptyChoice()) })
    }

    private fun emptyChoice(): Choice = Choice(
        index = 0,
        message = ChatMessage(role = "assistant", content = JsonPrimitive("")),
        finishReason = "stop",
    )

    override fun close() = client.close()

    companion object {
        private const val MAX_RETRIES = 4
        private const val DEFAULT_RETRY_DELAY_SEC = 5.0
    }
}

/** Mutable accumulator for a single tool_call as its `name` and `arguments` fields
 *  arrive one SSE chunk at a time. */
private class StreamedToolCall(
    var id: String = "",
    var type: String = "",
    val name: StringBuilder = StringBuilder(),
    val arguments: StringBuilder = StringBuilder(),
)

/**
 * Stateful splitter that pulls `<think>…</think>` blocks out of a streaming content feed.
 * Designed for SSE chunks: each call to [feed] returns the *answer* portion and the
 * *reasoning* portion of the current chunk, and the splitter remembers whether the cursor
 * is currently inside a `<think>` block so a tag straddling chunk boundaries still
 * routes correctly.
 *
 * Recognised tags (case-insensitive): `<think>`, `</think>`, `<thought>`, `</thought>`,
 * `<thinking>`, `</thinking>`. The opening tag itself, the closing tag, and any leading
 * whitespace immediately after the closing tag are all dropped — so a stream like
 * `"<think>plan…</think>\n\nHello"` produces reasoning `"plan…"` and answer `"Hello"`.
 *
 * NOTE: We buffer up to [PARTIAL_TAG_MAX] bytes of a partially-matched tag. If a chunk
 * ends mid-tag we hold the bytes back until the next [feed] call resolves it. On stream
 * close, anything still buffered is flushed verbatim to the answer side so the user
 * doesn't lose text.
 */
internal class ThinkSplitter {
    private var insideThink = false
    private val pendingTag = StringBuilder()
    private val openTags = listOf("<think>", "<thought>", "<thinking>")
    private val closeTags = listOf("</think>", "</thought>", "</thinking>")

    /** Split [chunk] into (answer, reasoning) text. Either may be empty. */
    fun feed(chunk: String): Pair<String, String> {
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var i = 0
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == '<' || pendingTag.isNotEmpty()) {
                pendingTag.append(c)
                val current = pendingTag.toString().lowercase()
                val matchedOpen = openTags.firstOrNull { current == it }
                val matchedClose = closeTags.firstOrNull { current == it }
                when {
                    matchedOpen != null -> {
                        insideThink = true
                        pendingTag.setLength(0)
                    }
                    matchedClose != null -> {
                        insideThink = false
                        pendingTag.setLength(0)
                        // Eat one optional leading newline / whitespace right after the
                        // closing tag so the answer doesn't start with stray padding.
                        while (i + 1 < chunk.length && chunk[i + 1].isWhitespace()) {
                            i++
                        }
                    }
                    couldStillMatch(current) -> {
                        // Wait for more characters; pendingTag retains them.
                    }
                    else -> {
                        // No tag will form — flush the pending bytes verbatim to whichever
                        // side we're currently routing to.
                        val flushed = pendingTag.toString()
                        pendingTag.setLength(0)
                        if (insideThink) reasoning.append(flushed) else answer.append(flushed)
                    }
                }
                i++
                if (pendingTag.length > PARTIAL_TAG_MAX) {
                    val flushed = pendingTag.toString()
                    pendingTag.setLength(0)
                    if (insideThink) reasoning.append(flushed) else answer.append(flushed)
                }
            } else {
                if (insideThink) reasoning.append(c) else answer.append(c)
                i++
            }
        }
        return answer.toString() to reasoning.toString()
    }

    private fun couldStillMatch(current: String): Boolean {
        return openTags.any { it.startsWith(current) } || closeTags.any { it.startsWith(current) }
    }

    private companion object {
        const val PARTIAL_TAG_MAX = 16
    }
}

class LlmException(message: String) : RuntimeException(message)
