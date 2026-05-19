package com.aiagent.android.kiro

import com.aiagent.android.llm.ChatBackend
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.ChatResponse
import com.aiagent.android.llm.Choice
import com.aiagent.android.llm.LlmException
import com.aiagent.android.llm.textMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Direct, no-proxy client for Kiro AI (Amazon Q Developer / CodeWhisperer).
 *
 * The Android Kiro Agent talks to this client through the same
 * [ChatBackend] surface as the OpenAI-compatible [LlmClient], so the rest of
 * the app never has to know which transport is in use.
 *
 * Wire shape (reverse-engineered from `kiro-cli 2.3.0` with `KIRO_API_KEY`):
 *  - Endpoint: `https://q.us-east-1.amazonaws.com/`
 *  - Auth: `Authorization: Bearer ksk_…` + `tokentype: API_KEY`
 *  - Content type: `application/x-amz-json-1.0`
 *  - Operation selected via `X-Amz-Target`, e.g. `AmazonCodeWhispererStreamingService.GenerateAssistantResponse`
 *  - Chat responses come back as AWS Event Stream binary frames; see [EventStreamParser].
 *
 * This client deliberately drops the OpenAI tool-calling surface for now —
 * Kiro's tool format is incompatible and bridging it isn't worth the
 * complexity for the chat-only use case. The OpenAI-compatible transport is
 * still available in the app for full agentic tool use.
 */
class KiroClient(
    baseUrl: String,
    apiKeys: List<String>,
    private val onKeyPromoted: (String) -> Unit = {},
) : ChatBackend {

    /**
     * Convenience constructor that wraps a single `ksk_…` key into a one-element pool.
     * Used by call sites that don't care about multi-key fallback (offline model listing,
     * the model-listing fast path, tests).
     */
    constructor(baseUrl: String, apiKey: String) : this(
        baseUrl = baseUrl,
        apiKeys = listOf(apiKey),
        onKeyPromoted = {},
    )

    /**
     * Ordered key pool. The first entry is the «primary»; subsequent entries are
     * fallbacks tried in order when [primary] returns an auth/quota error. Pasted keys
     * frequently carry a trailing newline or zero-width space when copied from a web page,
     * so we trim each entry and drop blanks. Without this trimming the Kiro endpoint replies
     * 403 «Forbidden» instead of the more informative «Unauthorized» on a stray newline.
     */
    private val keyPool: List<String> = apiKeys.map { it.trim() }.filter { it.isNotBlank() }
    private val baseUrl: String = baseUrl.trim()

    /** Backwards-compatible accessor for the primary key, used by header construction. */
    private val apiKey: String
        get() = keyPool.firstOrNull().orEmpty()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // Kiro's Coral validator REQUIRES fields like `origin`, `modelId`,
        // `chatTriggerType`, `agentTaskType`, and an explicit `history` array — it
        // returns HTTP 400 "Improperly formed request." when they are missing. By
        // default kotlinx-serialization omits any field that equals its declared
        // default, so we must force it to emit them.
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 180_000
        }
    }

    private val rootUrl: String
        get() = baseUrl.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    /**
     * Non-streaming variant — kept for the interface contract and for the few code paths
     * that don't care about per-token deltas (e.g. the title generator). Delegates to
     * [chatStream] with no-op callbacks so we have a single implementation of the wire
     * protocol.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse =
        chatStream(request, onDelta = {}, onReasoning = {})

    /**
     * Streaming variant: opens the CodeWhisperer Streaming `GenerateAssistantResponse`
     * operation, reads the binary AWS Event Stream body chunk-by-chunk as it arrives, and
     * forwards each [assistantResponseEvent] payload to [onDelta] the moment it lands. When
     * the model emits its chain-of-thought as [reasoningContentEvent] frames (native Kiro
     * thinking mode, enabled below via prompt injection), each reasoning fragment is
     * forwarded to [onReasoning] in real time so the UI can stream the reasoning panel
     * exactly like the answer bubble.
     *
     * Native Kiro thinking is opted into by prepending magic `<thinking_mode>enabled</…>`
     * tags to the user content — this is the protocol used by `kiro-gateway` and the
     * `kiro-cli` thinking experiment. The injection is gated on the OpenAI-shape
     * [ChatRequest.reasoningEffort] field, so the same switch that toggles reasoning for
     * OpenAI-compatible backends also toggles it here.
     */
    override suspend fun chatStream(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ChatResponse {
        if (keyPool.isEmpty()) {
            throw LlmException("Kiro API key is empty — paste your ksk_… key in settings")
        }

        val reasoningOn = !request.reasoningEffort.isNullOrBlank()
        val budget = reasoningBudgetFor(request.reasoningEffort)
        val payload = buildGenerateAssistantBody(
            request,
            injectThinking = reasoningOn,
            thinkingBudget = budget,
        )
        val serialized = json.encodeToString(KiroChatRequestBody.serializer(), payload)
        if (DEBUG_LOG) System.err.println("Kiro request body: $serialized")

        val url = rootUrl + "/"
        val target = "AmazonCodeWhispererStreamingService.GenerateAssistantResponse"

        // Multi-key fallback: try each key in the pool until one succeeds. Auth errors
        // (401/403/ExpiredToken/AccessDenied/UnauthorizedException) and persistent quota
        // errors (429 ThrottlingException) trigger rotation to the next key; transport-
        // level retries (network blips, 5xx) stay inside the inner attempt loop so we
        // don't burn through every key on a transient outage.
        var lastAuthError: LlmException? = null
        for ((keyIdx, key) in keyPool.withIndex()) {
            try {
                val response = chatStreamWithKey(
                    activeKey = key,
                    url = url,
                    target = target,
                    serialized = serialized,
                    onDelta = onDelta,
                    onReasoning = onReasoning,
                )
                if (keyIdx > 0) {
                    // A fallback key worked — persist it as the new primary so the next run
                    // doesn't waste a request retrying the dead one. Wrap in runCatching so a
                    // SharedPreferences crash never aborts the actual response.
                    runCatching { onKeyPromoted(key) }
                }
                return response
            } catch (e: LlmException) {
                if (isKeyExhaustedError(e)) {
                    lastAuthError = e
                    // Try the next key in the pool. Don't log noisily — the agent's log will
                    // surface the final error if every key fails.
                    continue
                }
                throw e
            }
        }
        throw lastAuthError ?: LlmException(
            "Kiro: ни один из ${keyPool.size} ключей не сработал. Проверь Настройки →  Провайдер 1».",
        )
    }

    /**
     * Internal: run the existing retry-aware streaming loop using exactly one ksk_… key.
     * Auth errors raise via [LlmException] so the outer caller can rotate to the next
     * key in the pool; transport-level retries (network / 5xx / Retry-After) stay inside.
     */
    private suspend fun chatStreamWithKey(
        activeKey: String,
        url: String,
        target: String,
        serialized: String,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ChatResponse {
        var lastError = ""
        var lastStatus = 0
        repeat(MAX_RETRIES) { attempt ->
            var assembled: ChatResponse? = null
            var transientDelayMs: Long? = null
            try {
                val statement = client.preparePost(url) {
                    kiroHeaders(this, target, activeKey)
                    setBody(serialized)
                }
                statement.execute { response ->
                    if (response.status == HttpStatusCode.OK) {
                        assembled = consumeEventStream(response.bodyAsChannel(), onDelta, onReasoning)
                        return@execute
                    }

                    // Non-200 path: classify auth / validation / transient and either throw
                    // or set a retry delay.
                    val statusCode = response.status.value
                    val errBody = runCatching { response.bodyAsText() }.getOrDefault("").take(800)
                    lastStatus = statusCode
                    lastError = errBody

                    if (statusCode == 401 || statusCode == 403 ||
                        errBody.contains("ExpiredToken", ignoreCase = true) ||
                        errBody.contains("AccessDenied", ignoreCase = true) ||
                        errBody.contains("UnauthorizedException", ignoreCase = true)
                    ) {
                        throw LlmException(
                            "Kiro HTTP $statusCode: ключ ksk_… недействителен или просрочен. " +
                                "Открой Настройки → «Провайдер 1» и вставь свежий ksk_… . " +
                                "AWS вернул: ${errBody.take(200)}",
                        )
                    }
                    if (statusCode == 400) {
                        throw LlmException("Kiro HTTP 400: $errBody")
                    }
                    val retryable = statusCode == 429 || statusCode in 500..599 || statusCode == 408 ||
                        errBody.contains("ThrottlingException", ignoreCase = true) ||
                        errBody.contains("ServiceUnavailable", ignoreCase = true) ||
                        errBody.contains("InternalServerError", ignoreCase = true)
                    if (!retryable || attempt == MAX_RETRIES - 1) {
                        // Friendlier message for sustained per-account throttling so the user
                        // knows they should add a backup key (multi-key fallback) instead of
                        // staring at a raw AWS exception. The outer pool loop catches this and
                        // tries the next key; only when EVERY key is throttled do we surface
                        // this as a final agent error.
                        if (statusCode == 429 || errBody.contains("ThrottlingException", true)) {
                            throw LlmException(
                                "Kiro HTTP 429 (аккаунт временно ограничен Amazon). " +
                                    "Добавь ещё один ksk_… в Настройки → Запасные ключи — " +
                                    "приложение автоматически переключится на рабочий ключ. " +
                                    "Подробнее: ${errBody.take(200)}",
                            )
                        }
                        throw LlmException("Kiro HTTP $statusCode: $errBody")
                    }
                    val retryAfterHeader = response.headers["Retry-After"]
                        ?: response.headers["retry-after"]
                    val delayMs = retryAfterHeader?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                        ?: backoffDelay(attempt)
                    transientDelayMs = delayMs.coerceAtMost(MAX_BACKOFF_MS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // User pressed STOP — propagate to abort the agent turn cleanly.
                throw e
            } catch (e: LlmException) {
                // Already a user-facing error — let it bubble up.
                throw e
            } catch (e: Exception) {
                // Transport-level (socket timeout, DNS, TLS reset, connect refused). Treat
                // as transient and retry with backoff.
                lastError = e.message ?: e::class.java.simpleName
                lastStatus = 0
                if (attempt == MAX_RETRIES - 1) {
                    throw LlmException(
                        "Kiro: сеть до AWS не доходит ($lastError). Проверь интернет и VPN.",
                    )
                }
                delay(backoffDelay(attempt))
                return@repeat
            }

            assembled?.let { return it }
            transientDelayMs?.let { delay(it) }
        }
        throw LlmException("Kiro: исчерпан лимит ретраев (status=$lastStatus): $lastError")
    }

    /**
     * True when the wrapped error is sticky enough that rotating to a different key has
     * a chance of recovering: invalid/expired key, missing permissions, or per-key quota
     * (ThrottlingException after retries were exhausted).
     */
    private fun isKeyExhaustedError(e: LlmException): Boolean {
        val msg = e.message.orEmpty()
        return msg.contains("HTTP 401") ||
            msg.contains("HTTP 403") ||
            msg.contains("HTTP 429") ||
            msg.contains("ExpiredToken", ignoreCase = true) ||
            msg.contains("AccessDenied", ignoreCase = true) ||
            msg.contains("UnauthorizedException", ignoreCase = true) ||
            msg.contains("ThrottlingException", ignoreCase = true) ||
            msg.contains("quota", ignoreCase = true) ||
            msg.contains("недействителен", ignoreCase = true) ||
            msg.contains("просрочен", ignoreCase = true)
    }

    /**
     * Drain an AWS Event Stream binary body off [channel] one buffer at a time. Each
     * complete frame is dispatched immediately — text from `assistantResponseEvent` goes
     * to [onDelta], reasoning text from `reasoningContentEvent` goes to [onReasoning], and
     * `invalidStateEvent` / `errorEvent` frames throw [LlmException]. The final assembled
     * answer is returned for callers that want the snapshot (the [chat] wrapper and the
     * agent's response-parsing path).
     */
    private suspend fun consumeEventStream(
        channel: ByteReadChannel,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
    ): ChatResponse {
        val parser = EventStreamParser()
        val buf = ByteArray(8192)
        val contentBuilder = StringBuilder()
        while (true) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n == -1) break // EOF
            if (n <= 0) continue
            parser.append(if (n == buf.size) buf else buf.copyOfRange(0, n))
            for (msg in parser.drainMessages()) {
                when (msg.eventType) {
                    "assistantResponseEvent" -> {
                        val payloadJson = runCatching {
                            json.parseToJsonElement(msg.payloadAsString())
                        }.getOrNull() as? JsonObject ?: continue
                        val text = (payloadJson["content"] as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            contentBuilder.append(text)
                            onDelta(text)
                        }
                    }
                    "reasoningContentEvent" -> {
                        // Native Kiro thinking output — the model's chain-of-thought is
                        // emitted on a separate event type from the final answer, exactly
                        // like Anthropic's extended thinking. Forward each fragment so the
                        // UI's reasoning panel streams in real time.
                        val payloadJson = runCatching {
                            json.parseToJsonElement(msg.payloadAsString())
                        }.getOrNull() as? JsonObject ?: continue
                        val text = (payloadJson["text"] as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrEmpty()) {
                            onReasoning(text)
                        }
                    }
                    "invalidStateEvent", "errorEvent" -> {
                        val payloadJson = runCatching {
                            json.parseToJsonElement(msg.payloadAsString())
                        }.getOrNull() as? JsonObject
                        val text = (payloadJson?.get("message") as? JsonPrimitive)?.contentOrNull
                        throw LlmException(
                            "Kiro stream error: ${text ?: msg.payloadAsString().take(200)}",
                        )
                    }
                    // Ignore metering / contextUsage / metadata / tool-use frames for now —
                    // the chat-only surface in this app doesn't consume them.
                    else -> Unit
                }
            }
        }
        return ChatResponse(
            id = "kiro-${UUID.randomUUID()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = textMessage(role = "assistant", text = contentBuilder.toString()),
                    finishReason = "stop",
                ),
            ),
        )
    }

    /**
     * Map the OpenAI-shape `reasoning_effort` string (`low` / `medium` / `high`) to a
     * Kiro thinking-token budget. Defaults to a conservative 8000 for any unrecognised
     * value so we don't spend forever on the thinking phase.
     */
    private fun reasoningBudgetFor(effort: String?): Int = when (effort?.lowercase()) {
        "low" -> 4000
        "medium" -> 8000
        "high" -> 16000
        null, "" -> 0
        else -> 8000
    }

    override suspend fun listModels(): List<String> {
        if (apiKey.isBlank()) {
            throw LlmException("Kiro API key is empty — paste your ksk_… key in settings")
        }
        val response: HttpResponse = postWithRetry(
            url = rootUrl + "/?origin=KIRO_CLI",
            body = """{"origin":"KIRO_CLI"}""",
            target = "AmazonCodeWhispererService.ListAvailableModels",
        )
        val body = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
            ?: throw LlmException("Kiro list-models returned non-JSON: ${body.take(200)}")
        val list = obj["models"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            ((item as? JsonObject)?.get("modelId") as? JsonPrimitive)?.contentOrNull
        }.distinct()
    }

    /**
     * Curated catalogue used as a fallback when the live `ListAvailableModels` call can't
     * reach AWS (DNS down, no internet, captive portal, firewall). All of these IDs are
     * what `kiro-cli` ships in its config and they're what the Kiro endpoint accepts on
     * the `modelId` field of `GenerateAssistantResponse`. Keep [DEFAULT_KIRO_MODEL] in sync.
     */
    override fun fallbackModels(): List<String> = KNOWN_MODELS

    override fun close() = client.close()

    private fun kiroHeaders(
        builder: io.ktor.client.request.HttpRequestBuilder,
        target: String,
        activeKey: String = apiKey,
    ) {
        builder.header("Authorization", "Bearer $activeKey")
        builder.header("Content-Type", "application/x-amz-json-1.0")
        builder.header("X-Amz-Target", target)
        builder.header("tokentype", "API_KEY")
        builder.header("x-amzn-codewhisperer-optout", "false")
        builder.header("Accept", "*/*")
    }

    /**
     * POST [body] to [url] with the given [target] header, retrying on transient AWS failures.
     *
     * AWS Coral services advertise transient failures via:
     *  - HTTP 429 ThrottlingException (rate limit — back off and retry)
     *  - HTTP 5xx (InternalServerError, ServiceUnavailable — retry)
     *  - HTTP 408 / connection reset / socket timeout (network blip — retry)
     *
     * Auth failures (401/403, ExpiredTokenException, AccessDeniedException) are NOT retried —
     * the user needs to actually replace the key. We turn them into a clear Russian message.
     */
    private suspend fun postWithRetry(url: String, body: String, target: String): HttpResponse {
        var lastError = ""
        var lastStatus = 0
        repeat(MAX_RETRIES) { attempt ->
            val response: HttpResponse = try {
                client.post(url) {
                    kiroHeaders(this, target)
                    setBody(body)
                }
            } catch (e: Exception) {
                // Transport-level failure: socket timeout, DNS, TLS reset, connect refused, …
                // Treat every one as transient — they're typically network blips — except
                // CancellationException, which must propagate so the user-press-STOP path
                // doesn't get swallowed by the retry loop.
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e.message ?: e::class.java.simpleName
                lastStatus = 0
                if (attempt == MAX_RETRIES - 1) {
                    throw LlmException(
                        "Kiro: сеть до AWS не доходит ($lastError). Проверь интернет и VPN.",
                    )
                }
                delay(backoffDelay(attempt))
                return@repeat
            }

            if (response.status == HttpStatusCode.OK) return response

            val statusCode = response.status.value
            val errBody = runCatching { response.bodyAsText() }.getOrDefault("").take(800)
            lastStatus = statusCode
            lastError = errBody

            // Non-retryable: auth errors stay sticky until the user replaces the key.
            // We hand the user a clear hint instead of a raw AWS JSON blob.
            if (statusCode == 401 || statusCode == 403 ||
                errBody.contains("ExpiredToken", ignoreCase = true) ||
                errBody.contains("AccessDenied", ignoreCase = true) ||
                errBody.contains("UnauthorizedException", ignoreCase = true)
            ) {
                throw LlmException(
                    "Kiro HTTP $statusCode: ключ ksk_… недействителен или просрочен. " +
                        "Открой Настройки → «Провайдер 1» и вставь свежий ksk_… . " +
                        "AWS вернул: ${errBody.take(200)}",
                )
            }

            // Validation error from Coral — model returned a malformed body. Not retryable.
            if (statusCode == 400) {
                throw LlmException("Kiro HTTP 400: $errBody")
            }

            // Retryable: 429 (throttle), 5xx, anything containing ThrottlingException /
            // ServiceUnavailable in the body.
            val retryable = statusCode == 429 || statusCode in 500..599 || statusCode == 408 ||
                errBody.contains("ThrottlingException", ignoreCase = true) ||
                errBody.contains("ServiceUnavailable", ignoreCase = true) ||
                errBody.contains("InternalServerError", ignoreCase = true)
            if (!retryable || attempt == MAX_RETRIES - 1) {
                throw LlmException("Kiro HTTP $statusCode: $errBody")
            }

            val retryAfterHeader = response.headers["Retry-After"]
                ?: response.headers["retry-after"]
            val delayMs = retryAfterHeader?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                ?: backoffDelay(attempt)
            delay(delayMs.coerceAtMost(MAX_BACKOFF_MS))
        }
        throw LlmException("Kiro: исчерпан лимит ретраев (status=$lastStatus): $lastError")
    }

    /** Exponential backoff with jitter. attempt is 0-based. */
    private fun backoffDelay(attempt: Int): Long {
        val base = INITIAL_BACKOFF_MS * (1L shl attempt.coerceAtMost(5))
        val jitter = (Math.random() * 250).toLong()
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Translate OpenAI-shaped messages into Kiro's `conversationState`. We
     * collapse system prompts into the first user turn (Kiro doesn't have a
     * dedicated system role on the wire) and turn assistant/tool turns into
     * `assistantResponseMessage` entries.
     */
    private fun buildGenerateAssistantBody(
        request: ChatRequest,
        injectThinking: Boolean = false,
        thinkingBudget: Int = 0,
    ): KiroChatRequestBody {
        val (history, last) = splitHistory(
            messages = request.messages,
            model = request.model,
            injectThinking = injectThinking,
            thinkingBudget = thinkingBudget,
        )
        return KiroChatRequestBody(
            conversationState = KiroConversationState(
                conversationId = UUID.randomUUID().toString(),
                history = history,
                currentMessage = KiroCurrentMessage(
                    userInputMessage = last,
                ),
                chatTriggerType = "MANUAL",
                agentTaskType = "vibe",
            ),
        )
    }

    private fun splitHistory(
        messages: List<ChatMessage>,
        model: String,
        injectThinking: Boolean = false,
        thinkingBudget: Int = 0,
    ): Pair<List<KiroHistoryEntry>, KiroUserInputMessage> {
        val systemBuf = StringBuilder()
        val turns = ArrayList<ChatMessage>()
        for (m in messages) {
            when (m.role) {
                "system" -> m.contentText?.let { if (it.isNotBlank()) systemBuf.append(it).append('\n') }
                else -> turns.add(m)
            }
        }
        val lastIndex = turns.indexOfLast { it.role == "user" }
        if (lastIndex < 0) {
            // No user turn — fabricate one from the system prompt to keep Kiro happy.
            val sys = systemBuf.toString().ifBlank { "ping" }
            return Pair(
                emptyList(),
                KiroUserInputMessage(content = sys, modelId = model.normalizeModel(), origin = "KIRO_CLI"),
            )
        }

        val historyMessages = turns.subList(0, lastIndex)
        val lastUser = turns[lastIndex]

        val history = ArrayList<KiroHistoryEntry>()
        for (m in historyMessages) {
            val text = m.contentText.orEmpty().trim()
            val images = m.contentImages.mapNotNull { it.toKiroImage() }
            if (text.isEmpty() && images.isEmpty()) continue
            when (m.role) {
                "user" -> history.add(
                    KiroHistoryEntry(
                        userInputMessage = KiroUserInputMessage(
                            content = text,
                            modelId = model.normalizeModel(),
                            origin = "KIRO_CLI",
                            images = images.ifEmpty { null },
                        ),
                    ),
                )
                "assistant", "tool" -> history.add(
                    KiroHistoryEntry(
                        assistantResponseMessage = KiroAssistantMessage(content = text),
                    ),
                )
            }
        }

        val effectiveContent = buildString {
            // Opt-in to native Kiro thinking mode by prepending the agreed magic markers.
            // The model treats these tags as instructions to emit its chain-of-thought as
            // separate `reasoningContentEvent` frames before the final answer. Without
            // them Kiro never returns a reasoning channel, no matter the model.
            if (injectThinking && thinkingBudget > 0) {
                append("<thinking_mode>enabled</thinking_mode>\n")
                append("<max_thinking_length>$thinkingBudget</max_thinking_length>\n\n")
            }
            if (systemBuf.isNotEmpty()) {
                append("[system]\n")
                append(systemBuf.toString().trim())
                append("\n\n")
            }
            append(lastUser.contentText.orEmpty())
        }
        val lastImages = lastUser.contentImages.mapNotNull { it.toKiroImage() }

        return Pair(
            history,
            KiroUserInputMessage(
                content = effectiveContent,
                modelId = model.normalizeModel(),
                origin = "KIRO_CLI",
                images = lastImages.ifEmpty { null },
            ),
        )
    }

    /**
     * Convert an OpenAI-style `image_url.url` into Kiro's `KiroImage { format, source.bytes }`.
     * The URL must be a `data:image/<format>;base64,<payload>` data URI; remote URLs are not
     * supported by Kiro/CodeWhisperer's image input today and we just drop them.
     */
    private fun String.toKiroImage(): KiroImage? {
        if (!startsWith("data:")) return null
        // Expected shape: data:<mime>;base64,<payload>
        val comma = indexOf(',')
        if (comma <= 5) return null
        val header = substring(5, comma) // e.g. "image/png;base64"
        val payload = substring(comma + 1)
        if (payload.isBlank()) return null
        if (!header.contains("base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').trim().lowercase()
        val format = when {
            mime.endsWith("/jpeg") || mime.endsWith("/jpg") -> "jpeg"
            mime.endsWith("/png") -> "png"
            mime.endsWith("/gif") -> "gif"
            mime.endsWith("/webp") -> "webp"
            else -> "png" // safe default; Kiro accepts a small set of formats
        }
        return KiroImage(format = format, source = KiroImageSource(bytes = payload))
    }

    private fun String.normalizeModel(): String = ifBlank { "auto" }

    companion object {
        const val DEFAULT_BASE_URL = "https://q.us-east-1.amazonaws.com"
        /** Flip to true to dump the raw request body on stderr (logcat); off by
         *  default because the body contains the user's conversation. */
        const val DEBUG_LOG = false

        /** Maximum retries against AWS for transient failures (429 / 5xx / network). */
        private const val MAX_RETRIES = 4
        /** Initial backoff between retries — doubles every attempt. */
        private const val INITIAL_BACKOFF_MS = 700L
        /** Hard upper bound on a single backoff sleep (also caps Retry-After). */
        private const val MAX_BACKOFF_MS = 30_000L

        /**
         * Known-good Kiro / Amazon Q model aliases. Matches what `kiro-cli` resolves on
         * the wire. The default in [com.aiagent.android.data.Settings.DEFAULT_KIRO_MODEL]
         * MUST be in this list so the offline picker can pre-select it. Ordered with the
         * default first so it shows on top.
         */
        val KNOWN_MODELS: List<String> = listOf(
            "claude-opus-4.7",
            "claude-sonnet-4.5",
            "claude-sonnet-4",
            "claude-3.7-sonnet",
            "claude-3.5-sonnet",
            "auto",
        )
    }
}

// ---- Kiro wire structs ------------------------------------------------------

@Serializable
private data class KiroChatRequestBody(
    val conversationState: KiroConversationState,
)

@Serializable
private data class KiroConversationState(
    val conversationId: String,
    val history: List<KiroHistoryEntry> = emptyList(),
    val currentMessage: KiroCurrentMessage,
    val chatTriggerType: String = "MANUAL",
    val agentTaskType: String = "vibe",
)

@Serializable
private data class KiroHistoryEntry(
    val userInputMessage: KiroUserInputMessage? = null,
    val assistantResponseMessage: KiroAssistantMessage? = null,
)

@Serializable
private data class KiroCurrentMessage(
    val userInputMessage: KiroUserInputMessage,
)

@Serializable
private data class KiroUserInputMessage(
    val content: String,
    val origin: String = "KIRO_CLI",
    val modelId: String = "auto",
    /**
     * Optional list of images attached to this user turn. The wire shape mirrors the
     * Amazon Q Developer / CodeWhisperer streaming API: each image declares its
     * `format` (`png`, `jpeg`, `gif`, `webp`) and inlines the raw bytes as a base64
     * string under `source.bytes`. Null when the message is text-only.
     */
    val images: List<KiroImage>? = null,
)

@Serializable
private data class KiroImage(
    val format: String,
    val source: KiroImageSource,
)

@Serializable
private data class KiroImageSource(
    val bytes: String,
)

@Serializable
private data class KiroAssistantMessage(
    val content: String,
)
