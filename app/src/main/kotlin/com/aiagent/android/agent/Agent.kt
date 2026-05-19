package com.aiagent.android.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.util.Log
import com.aiagent.android.audio.MicRecorder
import com.aiagent.android.data.Settings
import com.aiagent.android.device.DeviceInfo
import com.aiagent.android.files.FileShareHelper
import com.aiagent.android.files.FileTools
import com.aiagent.android.llm.ChatMessage
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.ChatBackend
import com.aiagent.android.llm.LlmException
import com.aiagent.android.llm.FunctionCall
import com.aiagent.android.llm.ToolCall
import com.aiagent.android.llm.textMessage
import com.aiagent.android.llm.multimodalUserMessage
import com.aiagent.android.llm.userImageMessage
import com.aiagent.android.ocr.OcrEngine
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenCaptureService
import com.aiagent.android.service.ScreenState
import com.aiagent.android.stt.SpeechToText
import com.aiagent.android.tts.TtsManager
import com.aiagent.android.web.WebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the LLM <-> device interaction loop.
 *
 *  1. Send the user's instruction + system prompt to the LLM with the available tool schemas.
 *  2. The LLM returns one or more tool calls.
 *  3. Each tool call is executed against the AccessibilityService / OS APIs.
 *  4. The tool results are appended to the conversation and we loop until the LLM calls `done`
 *     (or we exceed [Settings.maxSteps]).
 */
class Agent(
    private val context: Context,
    private val settings: Settings,
    private val askUser: suspend (String) -> String,
    private val startScreenRecording: suspend () -> String,
    private val stopScreenRecording: suspend () -> String,
    /**
     * Called when the Accessibility `takeScreenshot()` API can't deliver a bitmap (e.g. on
     * Realme / Vivo / older API ROMs that block it for non-system services). The implementer is
     * expected to ask the user for MediaProjection consent and start the [ScreenCaptureService].
     * Returns true if a capture service is now running, false if the user denied consent or the
     * device doesn't support MediaProjection at all. Subsequent screenshots will be served from
     * [ScreenCaptureService.captureFrame].
     */
    private val ensureCaptureService: suspend () -> Boolean,
    /**
     * True iff this run was launched in background-execution mode (foreground service active).
     * Affects the system prompt: when set, the model is told it can take a bit longer and is
     * reminded that the user is not necessarily watching the screen.
     */
    private val inBackground: Boolean = false,
    private val onLog: suspend (AgentLog) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Lazy-initialised heavy components.
    private val tts: TtsManager by lazy { TtsManager(context) }
    private val stt: SpeechToText by lazy { SpeechToText(context, settings) }
    private val fileTools: FileTools by lazy { FileTools(context, settings) }
    private val micRecorder = MicRecorder()

    @Volatile private var lastScreenshotMs: Long = 0L

    /** Set to true the first time the user denies MediaProjection consent in a run. We don't
     *  pester them again on subsequent screenshot requests within the same run. */
    @Volatile private var projectionDenied: Boolean = false

    /**
     * Run the agent loop using an externally-owned conversation list. The caller (typically
     * MainViewModel) keeps this list across runs so the user can press "Продолжить" without
     * losing previous context. When [conversation] is empty we seed it with system+user; when
     * it already has content we just append the new user instruction and continue.
     *
     * The loop is "user-only-exit": the agent never auto-terminates the conversation. When the
     * model calls `done` or stops producing tool calls we simply pause (return from this fun);
     * the conversation stays in memory so the user can press «Продолжить» with a new instruction
     * to resume, or tap the floating STOP overlay to truly terminate. The only ways out are a
     * CancellationException (user pressed STOP) or the [Settings.maxSteps] hard cap.
     */
    suspend fun run(
        userInstruction: String,
        conversation: MutableList<ChatMessage>,
        userImages: List<String> = emptyList(),
    ) {
        // Device control is now opt-in. When the user turns "Управлять телефоном" OFF we still
        // run the agent loop — it just can't reach the AccessibilityService, so device-control
        // tools return soft errors instead of nothing. When it's ON we still tolerate the
        // service being missing (the user might have toggled it but not granted accessibility
        // yet); the model is told via a status note so it doesn't repeatedly call tools that
        // will fail with the same error.
        val service: AgentAccessibilityService? = AgentAccessibilityService.instance
        val deviceControlOn = settings.deviceControlEnabled
        val accessibilityOn = service != null
        if (settings.apiKey.isBlank() &&
            settings.provider1ApiKey.isBlank() &&
            settings.provider2ApiKey.isBlank()
        ) {
            onLog(
                AgentLog.Error(
                    "API-ключ не задан. Открой настройки → введи ksk_… (Kiro) или sk-… (OpenAI). " +
                        "Я не могу отправить запрос в модель без ключа.",
                ),
            )
            return
        }

        val client = com.aiagent.android.llm.ChatBackends.forActiveProvider(settings)
        val systemPrompt = buildSystemPrompt(
            userSystemPrompt = settings.systemPrompt,
            deviceControlOn = deviceControlOn,
            accessibilityOn = accessibilityOn,
            inBackground = inBackground,
            // Kiro's native transport doesn't support OpenAI-shape tool calling, so when the
            // active provider is Kiro we add an explicit «emit tool calls as JSON in your
            // reply» instruction. [recoverToolCallFromText] picks them back up and feeds them
            // through the normal tool dispatch path — makes Kiro a real multi-step agent
            // instead of a single-turn chat bot.
            isKiroTransport = settings.transport == Settings.TRANSPORT_KIRO,
        )
        if (conversation.isEmpty()) {
            conversation.add(textMessage(role = "system", text = systemPrompt))
        } else {
            // The user can flip these toggles between runs — re-tell the model the latest state
            // so its previous turn's understanding doesn't go stale.
            conversation.add(
                textMessage(
                    role = "system",
                    text = "Обновление состояния: device_control_enabled=$deviceControlOn, " +
                        "accessibility_service=$accessibilityOn, run_in_background=$inBackground.",
                ),
            )
        }
        if (userInstruction.isNotBlank() || userImages.isNotEmpty()) {
            // If the user attached pictures AND the controller is vision-capable, send the
            // message as a real multimodal turn (text + image_url parts) so the model can
            // actually see them. For text-only controllers, fall back to a plain text turn
            // plus a system note explaining we dropped the images.
            if (userImages.isNotEmpty() && supportsVision(settings.model)) {
                val textPart = if (userInstruction.isNotBlank()) userInstruction else "Посмотри на эти картинки."
                conversation.add(
                    multimodalUserMessage(text = textPart, imageDataUrls = userImages),
                )
            } else {
                if (userInstruction.isNotBlank()) {
                    conversation.add(textMessage(role = "user", text = userInstruction))
                }
                if (userImages.isNotEmpty()) {
                    onLog(
                        AgentLog.System(
                            "К сообщению приложено ${userImages.size} изображений, но выбранная модель (${settings.model}) " +
                                "не поддерживает vision — картинки пропущены. Выбери vision-модель в пилюле " +
                                "выбора модели (Claude Sonnet/Opus, GPT-4o, Llama-4, …).",
                        ),
                    )
                }
            }
        }
        val messages = conversation
        var lastScreenState: ScreenState? = null

        try {
            for (step in 1..settings.maxSteps) {
                onLog(AgentLog.Thinking(step))
                if (settings.deviceControlEnabled &&
                    !com.aiagent.android.util.AppForegroundTracker.isAppForeground
                ) {
                    OverlayService.showThought(
                        context,
                        title = "ИИ думает — шаг $step",
                        body = "Обрабатываю историю и планирую следующее действие…",
                    )
                }
                // Drain any pending voice/text interruptions the user pushed via the floating
                // overlay while the agent was busy. Each becomes a fresh user message that the
                // controller sees on this step.
                while (true) {
                    val interruption = userInterrupts.poll() ?: break
                    if (interruption.isNotBlank()) {
                        messages.add(textMessage(role = "user", text = interruption))
                        onLog(AgentLog.System("Получено сообщение от пользователя: $interruption"))
                    }
                }
                // Drain user-action memory: everything the human did on the device since the
                // previous turn (taps, scrolls, text input, app switches — see UserActionLog).
                // We append it as a system note so the controller sees "what happened while I
                // was waiting" without confusing it with a fresh user instruction.
                if (settings.recordUserActions) {
                    val recent = UserActionLog.drain()
                    if (recent.isNotEmpty()) {
                        val rendered = UserActionLog.renderForPrompt(recent)
                        messages.add(
                            textMessage(
                                role = "system",
                                text = "Действия пользователя с момента твоего прошлого ответа (они же будут " +
                                    "очищены после чтения). Выведи выводы, но не пересказывай список вслух:\n" +
                                    rendered,
                            ),
                        )
                        onLog(
                            AgentLog.System(
                                "Память действий пользователя: ${recent.size} событий влито в контекст.",
                            ),
                        )
                    }
                }
                // Game-mode: get a fresh view of the screen before each model call.
                // Two paths:
                //  (a) Single-model: inject the raw image straight into the controller's
                //      history. Requires a vision-capable controller model.
                //  (b) Two-model (vision describer): ask a separate vision model to write a
                //      textual description of the screenshot and inject the *text* — not the
                //      image — into the controller's history. Lets a powerful but text-only
                //      controller (e.g. gpt-oss-120b) drive the agent while a smaller vision
                //      model handles pixel parsing.
                if (settings.autoScreenshotEachTurn && service != null) {
                    val frame = throttleAndCapture(service)
                    if (frame != null) {
                        if (settings.useVisionDescriber && settings.visionDescriberModel.isNotBlank()) {
                            val description = describeScreenshotWithVisionModel(client, frame)
                            if (!description.isNullOrBlank()) {
                                messages.add(
                                    textMessage(
                                        role = "user",
                                        text = "Описание экрана от модели-наблюдателя (шаг $step):\n$description",
                                    ),
                                )
                            }
                        } else if (visionEnabled()) {
                            val dataUrl = bitmapToDataUrl(frame)
                            messages.add(
                                userImageMessage(
                                    text = "Текущий кадр экрана (шаг $step):",
                                    imageDataUrl = dataUrl,
                                ),
                            )
                        }
                    }
                }
                // Only send reasoning_effort to models that actually accept it. Per the Groq
                // docs (https://console.groq.com/docs/reasoning) the supported set is:
                //   openai/gpt-oss-20b, openai/gpt-oss-120b, openai/gpt-oss-safeguard-20b,
                //   qwen/qwen3-32b — plus OpenAI's own o1 / o3 / o4 series. Sending it to any
                // other model (e.g. llama-3.x, llama-4-scout) yields HTTP 400.
                //
                // For Kiro AI (native CodeWhisperer transport) the situation is different:
                // Kiro's `GenerateAssistantResponse` opts into chain-of-thought via inline
                // `<thinking_mode>enabled</thinking_mode>` markers prepended to the user
                // content, not via the OpenAI-shape `reasoning_effort` field. We piggyback
                // on the same parameter so the rest of the agent stays transport-agnostic:
                // when the master reasoning toggle is on and the Kiro transport is active,
                // KiroClient sees a non-null `reasoningEffort` and injects the thinking
                // tags. For Kiro we default to "medium" if the user hasn't picked a level.
                val isKiroTransport = settings.transport == Settings.TRANSPORT_KIRO
                val rawEffort = settings.reasoningEffort.ifBlank {
                    if (settings.reasoningModeEnabled && isKiroTransport) "medium" else ""
                }
                val effort = rawEffort.takeIf {
                    // When the master reasoning toggle is off we drop `reasoning_effort`
                    // entirely so the model never tries to spend tokens on a thinking
                    // phase the user explicitly hid.
                    it.isNotBlank() && settings.reasoningModeEnabled &&
                        (isKiroTransport || supportsReasoningEffort(settings.model))
                }
                // The controller may be text-only (e.g. `openai/gpt-oss-120b`). In ANY scenario
                // where the controller can't accept images we strip ALL multimodal content
                // from history so the provider doesn't return HTTP 400 'content must be a
                // string'. The vision describer (two-model mode) is the supported way to feed
                // pixel info to a text controller — it converts the screenshot to plain text
                // BEFORE adding to history. In single-model + vision-capable mode we keep
                // only the last N images to stay under Groq's 5-images-per-request cap.
                val controllerIsTextOnly = !supportsVision(settings.model)
                val keepImages = if (settings.useVisionDescriber || controllerIsTextOnly) {
                    0
                } else {
                    MAX_IMAGES_IN_HISTORY
                }
                trimOldScreenshots(messages, keep = keepImages)
                val baseRequest = ChatRequest(
                    model = settings.model,
                    messages = messages,
                    tools = Tools.toolList(),
                    toolChoice = "auto",
                    temperature = settings.temperature.toDouble(),
                    maxCompletionTokens = settings.maxTokens.takeIf { it > 0 },
                    reasoningEffort = effort,
                )
                // Stream the assistant response so the UI types it out chunk-by-chunk
                // instead of jumping to the full message all at once. Each delta becomes
                // an [AgentLog.AssistantDelta] which the ChatViewModel splices into the
                // currently-streaming bubble. The default [ChatBackend.chatStream]
                // implementation falls back to non-streaming for backends that don't
                // override it, so this is safe for every provider.
                // Per-turn reasoning bookkeeping. We assemble the full reasoning text in
                // [reasoningBuf] so we can emit one terminal [AgentLog.Reasoning] event at
                // the end — handy for downstream collectors that want a snapshot rather
                // than the chunk-by-chunk stream. Reasoning streaming is suppressed if the
                // user has the master switch off; in that case [onReasoning] is a no-op so
                // the panel never shows up. The agent's `reasoning_effort` is also already
                // forced empty by ChatViewModel when the toggle is off (see
                // [Settings.reasoningEffort] in chatStream call site).
                val reasoningBuf = StringBuilder()
                val reasoningOn = settings.reasoningModeEnabled
                // Honour the "Ответить сразу" button: if the UI pressed it while the
                // previous reasoning was streaming, the flag is already set when we get
                // here. We clear it before each turn so the next turn starts fresh.
                val skipReasoningThisTurn = forceAnswerRequested.also {
                    forceAnswerRequested = false
                }
                val effectiveRequest = if (skipReasoningThisTurn) {
                    baseRequest.copy(reasoningEffort = null)
                } else {
                    baseRequest
                }
                // Tool-call-as-text detection: Kiro's transport (and some text-only models)
                // emit tool invocations as a single bare JSON object inside the assistant
                // content. Streaming that JSON character-by-character to the user shows them
                // a wall of `{"name":"write_file","arguments":{...}}` which is exactly what
                // they complained about. So we sniff the first non-whitespace character of
                // the stream: if it's `{` we suspect a tool call and suppress AssistantDelta
                // forwarding entirely. The full content is still available on `msg.contentText`
                // after the stream finishes; [recoverToolCallFromText] extracts the call and
                // the [AgentLog.ToolCall] event surfaces it as a collapsible card.
                val streamTextBuf = StringBuilder()
                var streamLooksLikeToolCall = false
                val response = try {
                    client.chatStream(
                        effectiveRequest,
                        onDelta = { delta ->
                            if (delta.isEmpty()) return@chatStream
                            streamTextBuf.append(delta)
                            if (!streamLooksLikeToolCall) {
                                val trimmed = streamTextBuf.toString().trimStart()
                                if (trimmed.startsWith("{")) {
                                    streamLooksLikeToolCall = true
                                } else {
                                    onLog(AgentLog.AssistantDelta(delta))
                                }
                            }
                        },
                        onReasoning = { delta ->
                            // Suppress mid-stream once the user taps «Ответить сразу»: we
                            // can't cancel the in-flight HTTP body, but we can stop
                            // forwarding reasoning fragments to the UI so the user sees
                            // the reasoning panel freeze while the answer keeps streaming.
                            if (!reasoningOn || skipReasoningThisTurn || forceAnswerRequested) {
                                return@chatStream
                            }
                            if (delta.isEmpty()) return@chatStream
                            reasoningBuf.append(delta)
                            onLog(AgentLog.ReasoningDelta(delta))
                        },
                    )
                } catch (e: LlmException) {
                    val msg = e.message.orEmpty()
                    when {
                        // Some Groq models (and most non-OpenAI providers) reject
                        // `reasoning_effort`. Disable it for this run AND persist the change so the
                        // next runs don't fail the same way, then retry the same step.
                        msg.startsWith("HTTP 400") &&
                            msg.contains("reasoning_effort", ignoreCase = true) -> {
                            onLog(
                                AgentLog.Error(
                                    "Модель не поддерживает reasoning_effort — отключаю и пробую снова. " +
                                        "Параметр выключен в Настройках, чтобы это не повторялось.",
                                ),
                            )
                            settings.reasoningEffort = ""
                            client.chat(baseRequest.copy(reasoningEffort = null))
                        }
                        msg.startsWith("HTTP 400") && msg.contains("Parsing", ignoreCase = true) -> {
                            onLog(AgentLog.Error("Модель сгенерировала некорректный tool-call. Пробую ещё раз с подсказкой быть короче."))
                            messages.add(
                                textMessage(
                                    role = "system",
                                    text = "Your previous response was rejected by the API as malformed. " +
                                        "Reply with a SINGLE short tool call. Do not embed long text or newlines " +
                                        "in tool arguments. Keep `text` arguments under 500 characters and " +
                                        "without literal newline characters.",
                                ),
                            )
                            client.chat(baseRequest.copy(temperature = 0.0))
                        }
                        msg.startsWith("HTTP 400") &&
                            (msg.contains("Too many images", ignoreCase = true) ||
                                msg.contains("image", ignoreCase = true) &&
                                msg.contains("limit", ignoreCase = true)) -> {
                            onLog(AgentLog.Error("Слишком много картинок в истории — выкидываю все, кроме последней, и пробую снова."))
                            trimOldScreenshots(messages, keep = 1)
                            client.chat(baseRequest.copy(messages = messages))
                        }
                        else -> throw e
                    }
                }
                val choice = response.choices.firstOrNull()
                    ?: run {
                        onLog(AgentLog.Error("Пустой ответ от модели"))
                        return
                    }
                val msg = choice.message
                // Surface the assembled reasoning text as a terminal event before the
                // assistant content so collectors that only care about snapshots (not
                // chunk-by-chunk deltas) still get the final reasoning. UI does not
                // render this directly — it already received the streaming deltas — but
                // the persistence layer uses it to write the final value to disk.
                if (reasoningOn && !skipReasoningThisTurn && reasoningBuf.isNotEmpty()) {
                    onLog(AgentLog.Reasoning(reasoningBuf.toString()))
                }
                // Recovery FIRST: detect tool-call-as-JSON before emitting the visible
                // assistant text. We want this order so a tool-call message never reaches
                // the UI as raw `{"name":"...","arguments":{...}}` — only the collapsible
                // tool-event card surfaces what happened.
                val rawToolCalls = msg.toolCalls.orEmpty()
                val recoveredCall = if (rawToolCalls.isEmpty()) {
                    msg.contentText?.let { recoverToolCallFromText(it) }
                } else null
                msg.contentText?.takeIf { it.isNotBlank() }?.let { contentText ->
                    when {
                        recoveredCall != null -> {
                            // Tool-call only message — finalize the streaming bubble with
                            // empty visible content. The [AgentLog.ToolCall] event below
                            // (collapsible card) is what the user actually sees.
                            onLog(AgentLog.Assistant(""))
                        }
                        streamLooksLikeToolCall -> {
                            // Started with `{` but recovery failed — the model wrote literal
                            // JSON-looking prose. Flush the buffered text now so the user
                            // still sees it instead of an empty bubble.
                            onLog(AgentLog.AssistantDelta(contentText))
                            onLog(AgentLog.Assistant(contentText))
                        }
                        else -> {
                            onLog(AgentLog.Assistant(contentText))
                        }
                    }
                    if (recoveredCall == null &&
                        settings.deviceControlEnabled &&
                        !com.aiagent.android.util.AppForegroundTracker.isAppForeground
                    ) {
                        OverlayService.showThought(
                            context,
                            title = "ИИ говорит — шаг $step",
                            body = contentText.take(140) + if (contentText.length > 140) "…" else "",
                        )
                    }
                }
                val finalMsg = if (recoveredCall != null) {
                    // Replace the assistant message with one that has a proper tool_calls field
                    // so subsequent tool messages (with matching tool_call_id) link correctly.
                    msg.copy(content = null, toolCalls = listOf(recoveredCall))
                } else msg
                messages.add(finalMsg)
                // Only nudge non-Kiro models toward the structured tool_calls API. Kiro's
                // native transport doesn't carry that field on the wire, so for Kiro the
                // JSON-in-text protocol is the intended path — don't lecture the model.
                if (recoveredCall != null && rawToolCalls.isEmpty() &&
                    settings.transport != Settings.TRANSPORT_KIRO
                ) {
                    messages.add(
                        textMessage(
                            role = "system",
                            text = "REMINDER: Use the structured `tool_calls` API field for all " +
                                "tool invocations. Do NOT serialize tool calls as JSON inside the " +
                                "`content` of an assistant message — they will not execute. Just " +
                                "call the tool the normal way.",
                        ),
                    )
                }
                val toolCalls = if (recoveredCall != null) listOf(recoveredCall) else rawToolCalls
                if (toolCalls.isEmpty()) {
                    // Three modes here, in priority order:
                    //  - waitForMessages = true       → suspend until the user sends a fresh
                    //                                   chat / voice message via the overlay,
                    //                                   then loop again with full memory. This
                    //                                   wins over autoPauseOnIdle so the user's
                    //                                   «Не останавливаться» toggle actually
                    //                                   keeps the agent alive.
                    //  - autoPauseOnIdle = true       → return; user resumes with «Продолжить».
                    //                                   This is the default — the agent
                    //                                   answers and stops cleanly.
                    //  - both off (legacy game mode)  → don't exit, nudge the model and keep
                    //                                   going; only the overlay STOP can stop us.
                    if (settings.waitForMessages) {
                        waitForNextMessage()
                        continue
                    }
                    if (settings.autoPauseOnIdle) {
                        onLog(
                            AgentLog.Error(
                                "Жду новое указание. Нажми «Продолжить» чтобы продолжить, или «Стоп» в overlay.",
                            ),
                        )
                        return
                    }
                    messages.add(
                        textMessage(
                            role = "system",
                            text = "Я не получил от тебя tool call. Продолжай помогать игроку: " +
                                "посмотри на текущий скриншот выше и решай — нужно ли что-то " +
                                "делать (read_screen / tap / swipe / speak / ask_user_overlay). " +
                                "Если ничего полезного сделать нельзя прямо сейчас — просто молчи, " +
                                "ответь одним словом 'жду'. НЕ повторяй одну и ту же фразу типа " +
                                "«если нужно что-то конкретное, скажите» — это спам.",
                        ),
                    )
                    continue
                }
                for (call in toolCalls) {
                    // Each tool is wrapped in its own try/catch so a buggy tool argument or an
                    // exception inside the implementation cannot kill the loop. The error is
                    // returned to the model as a normal tool result so it can recover.
                    //
                    // We also flip the UserActionLog "agent driving" flag around the call so
                    // taps / setText / swipes the agent itself dispatches don't show up in the
                    // user-action memory — only true human-initiated events do.
                    UserActionLog.markAgentStart()
                    // The "thoughts island" overlay is itself a floating button — only show it
                    // when device control is on AND the app is not currently in the foreground.
                    if (settings.deviceControlEnabled &&
                        !com.aiagent.android.util.AppForegroundTracker.isAppForeground
                    ) {
                        OverlayService.showThought(
                            context,
                            title = "ИИ делает — шаг $step",
                            body = renderToolCallForIsland(call),
                        )
                    }
                    val result = try {
                        dispatchTool(service, call, lastScreenState)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Propagate cancellation up so the user-press-STOP flow ends cleanly
                        // instead of the loop keeping going with a fake "exception" tool result.
                        UserActionLog.markAgentEnd()
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Tool ${call.function.name} threw", e)
                        ToolResult.error("Исключение в инструменте ${call.function.name}: ${e.message ?: e::class.java.simpleName}")
                    } finally {
                        UserActionLog.markAgentEnd()
                    }
                    lastScreenState = result.newScreenState ?: lastScreenState
                    onLog(AgentLog.ToolCall(call.function.name, call.function.arguments, result.summary))
                    messages.add(
                        textMessage(
                            role = "tool",
                            text = result.toolContent,
                            toolCallId = call.id,
                            name = call.function.name,
                        ),
                    )
                    // Tool produced a screenshot → expose it to the controller. Two paths:
                    //  - two-model mode: run the describer over the image and inject its text
                    //    output. The controller is text-only by design.
                    //  - single-model + vision-capable controller: attach raw image.
                    if (result.imageDataUrl != null) {
                        if (settings.useVisionDescriber && settings.visionDescriberModel.isNotBlank()) {
                            val description = describeDataUrlWithVisionModel(client, result.imageDataUrl)
                            if (!description.isNullOrBlank()) {
                                messages.add(
                                    textMessage(
                                        role = "user",
                                        text = "Описание скриншота от модели-наблюдателя (после ${call.function.name}):\n$description",
                                    ),
                                )
                            }
                        } else if (visionEnabled()) {
                            messages.add(
                                userImageMessage(
                                    text = "Текущий скриншот (после инструмента ${call.function.name}):",
                                    imageDataUrl = result.imageDataUrl,
                                ),
                            )
                        }
                    }
                    if (result.done != null) {
                        onLog(AgentLog.Done(result.done.summary, result.done.success))
                        // waitForMessages wins over autoPauseOnIdle so «Не останавливаться»
                        // actually overrides the new auto-stop default — see the
                        // toolCalls.isEmpty() branch above for the same priority order.
                        if (settings.waitForMessages) {
                            // Don't exit the loop — the user wants the agent to keep listening.
                            // Suspend cooperatively until a fresh chat/voice message arrives.
                            OverlayService.showThought(
                                context,
                                title = "Жду новое сообщение",
                                body = "Скажи / напиши через ⚙️ — я сразу проснусь и прочитаю всё что ты делал.",
                            )
                            waitForNextMessage()
                            continue
                        }
                        if (settings.autoPauseOnIdle) {
                            // Pause and wait for the next user instruction.
                            return
                        }
                        // Legacy game mode: don't let the model exit; remind it to keep helping.
                        messages.add(
                            textMessage(
                                role = "system",
                                text = "User has set the agent to user-only-exit. You CANNOT stop. " +
                                    "Don't call `done` again. Wait for the next user message " +
                                    "or proactively read_screen / take_screenshot to see what " +
                                    "the user is doing now and react.",
                            ),
                        )
                    }
                }
            }
            onLog(AgentLog.Error("Достигнут лимит шагов (${settings.maxSteps}) без завершения."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User pressed STOP. Propagate so the viewmodel sees the run as cancelled instead of
            // failed; we don't log it as an error because that's the intended exit path.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop failed", e)
            onLog(AgentLog.Error(e.message ?: e.toString()))
        } finally {
            client.close()
            runCatching { tts.shutdown() }
            OverlayService.hideThought(context)
            returnToApp()
        }
    }

    /** One-line summary of a tool call for the floating "thoughts island". */
    private fun renderToolCallForIsland(call: ToolCall): String {
        val name = call.function.name
        val raw = call.function.arguments?.trim().orEmpty()
        val args = if (raw.length > 90) raw.take(88) + "…" else raw
        return when (name) {
            "tap_at" -> "👆 жму: $args"
            "tap" -> "👆 тап: $args"
            "swipe" -> "👆 свайп: $args"
            "long_press" -> "✨ долго жму: $args"
            "type_text" -> "✏️ пишу: $args"
            "speak" -> "🗣 говорю: $args"
            "take_screenshot" -> "📸 скрин"
            "read_screen" -> "👁 читаю экран"
            "open_app", "launch_app" -> "🚀 открываю: $args"
            "request_demonstration" -> "⚠️ прошу показать"
            "recall_user_actions" -> "🧠 вспоминаю твои действия"
            "done" -> "✅ готово"
            else -> "⚡ $name $args"
        }
    }

    /**
     * Suspend the agent loop until a fresh user message lands in [userInterrupts] (pushed by
     * the floating overlay's voice / text button via [pushInterrupt]). Cooperatively
     * cancellable — the STOP button cancels the agent's coroutine, which makes
     * `newInterruptSignal.receive()` throw [kotlinx.coroutines.CancellationException].
     */
    private suspend fun waitForNextMessage() {
        onLog(
            AgentLog.System(
                "Жду новое сообщение… все твои действия в это время запоминаются " +
                    "и всплывут в контексте когда ты ответишь.",
            ),
        )
        // Drop any stale signal from a previous round so receive() truly suspends until a
        // brand-new push lands. CONFLATED channel collapses bursts into one item so this is
        // bounded: one tryReceive() is enough.
        while (newInterruptSignalChannel.tryReceive().isSuccess) Unit
        // If a message was already enqueued between the previous turn and now (race window),
        // do not block — there's nothing to wait for.
        if (userInterrupts.isNotEmpty()) return
        newInterruptSignalChannel.receive()
    }

    /** Bring the AI Agent app back to the foreground so the user can see the result log. */
    private fun returnToApp() {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                context.startActivity(launch)
            }
        }
    }

    /**
     * Thin gate that lets the agent loop call tools without first having to know if the
     * AccessibilityService is up or the "Управлять телефоном" master switch is on.
     *
     *   - Tools that touch the UI (read_screen / tap / swipe / type_text / joystick_move /
     *     press_* / start_screen_recording / stop_screen_recording) require both flags.
     *   - All other tools (web_search, fetch_url, share_file, open_file, list_files,
     *     read_file, write_file, ask_user, speak, etc.) work regardless.
     *
     * Failures are returned as soft [ToolResult.error] replies so the model can recover — the
     * user explicitly asked for «сделай все разрешения необязательными. Просто будет оповещать
     * нейронку что пользователь не выдал разрешение».
     */
    private suspend fun dispatchTool(
        service: AgentAccessibilityService?,
        call: ToolCall,
        lastScreenState: ScreenState?,
    ): ToolResult {
        val name = call.function.name
        if (name in DEVICE_CONTROL_TOOLS) {
            if (!settings.deviceControlEnabled) {
                return ToolResult.error(
                    "Управление телефоном выключено пользователем (переключатель «Управлять " +
                        "телефоном» в настройках). Инструмент «$name» сейчас недоступен. Если " +
                        "для задачи действительно нужен доступ к экрану — попроси пользователя " +
                        "включить этот переключатель. Иначе попробуй решить задачу без " +
                        "управления устройством (web_search / read_file / write_file / share_file).",
                )
            }
            if (service == null) {
                return ToolResult.error(
                    "Служба Спецвозможностей отключена. Инструмент «$name» не может работать. " +
                        "Попроси пользователя зайти в «Настройки Android → Спецвозможности → " +
                        "AI Agent» и включить её — или предложи альтернативу без управления экраном.",
                )
            }
        }
        return executeTool(service, call, lastScreenState)
    }

    private suspend fun executeTool(
        service: AgentAccessibilityService?,
        call: ToolCall,
        lastScreenState: ScreenState?,
    ): ToolResult {
        val args = parseArgs(call.function.arguments)
        return when (call.function.name) {
            "read_screen" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val state = svc.captureScreenState()
                // Grab a bitmap so a vision-capable model — or the two-model describer — can SEE
                // the screen. Accessibility alone misses everything drawn into a SurfaceView
                // (games / video / WebGL).
                val needsBitmap = visionEnabled() || settings.useVisionDescriber
                val bitmap = if (needsBitmap) throttleAndCapture(svc) else null
                val hint = if (state.nodes.isEmpty()) {
                    "\n[hint] Accessibility tree пуст — приложение скорее всего рендерит в SurfaceView " +
                        "(игра / видео / WebGL). Вызови read_screen_text для OCR или " +
                        "take_screenshot если включён vision-режим."
                } else ""
                ToolResult(
                    toolContent = "Foreground app: ${state.packageName}\n${state.description}$hint",
                    summary = "экран считан → ${state.nodes.size} элементов",
                    newScreenState = state,
                    imageDataUrl = bitmap?.let { bitmapToDataUrl(it) },
                )
            }
            "read_screen_text" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val bitmap = throttleAndCapture(svc)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить изображение экрана. Пользователь отказал в разрешении захвата экрана.")
                } else {
                    val text = try {
                        OcrEngine.extractText(bitmap)
                    } catch (e: Exception) {
                        "[ошибка OCR: ${e.message}]"
                    }
                    ToolResult(
                        toolContent = "Foreground app: ${svc.captureScreenState().packageName}\n--- OCR ---\n$text",
                        summary = "OCR: ${text.length} символов",
                        imageDataUrl = if (visionEnabled() || settings.useVisionDescriber) bitmapToDataUrl(bitmap) else null,
                    )
                }
            }
            "take_screenshot" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val bitmap = throttleAndCapture(svc)
                if (bitmap == null) {
                    ToolResult.error("Не удалось получить скриншот.")
                } else {
                    val path = saveBitmapToPng(bitmap)
                    ToolResult(
                        toolContent = "Saved screenshot to $path",
                        summary = "сохранён скриншот: $path",
                        imageDataUrl = if (visionEnabled() || settings.useVisionDescriber) bitmapToDataUrl(bitmap) else null,
                    )
                }
            }
            "tap" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val nodeId = args.intOf("node_id") ?: return ToolResult.error("Missing node_id")
                val node = lastScreenState?.nodes?.getOrNull(nodeId)
                    ?: return ToolResult.error("Unknown node_id $nodeId. Call read_screen first.")
                val ok = svc.tapNode(node)
                ToolResult(
                    toolContent = if (ok) "Tapped node $nodeId" else "Tap dispatch failed",
                    summary = if (ok) "нажат элемент #$nodeId" else "не удалось нажать элемент #$nodeId",
                )
            }
            "tap_at" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val x = args.intOf("x") ?: return ToolResult.error("Missing x")
                val y = args.intOf("y") ?: return ToolResult.error("Missing y")
                if (isInsideStopButton(x, y)) {
                    return ToolResult.error("Точка ($x,$y) попадает в кнопку СТОП. Только пользователь может её нажать. Выбери другую цель.")
                }
                val ok = svc.tap(x, y)
                ToolResult(
                    toolContent = if (ok) "Tapped at ($x,$y)" else "Tap dispatch failed",
                    summary = if (ok) "нажато по координатам ($x, $y)" else "не удалось нажать ($x, $y)",
                )
            }
            "swipe" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val direction = args.stringOf("direction") ?: return ToolResult.error("Missing direction")
                val distance = args.stringOf("distance") ?: "medium"
                val (x1, y1, x2, y2) = computeSwipe(direction, distance)
                val ok = svc.swipe(x1, y1, x2, y2)
                val dirRu = when (direction) {
                    "up" -> "вверх"; "down" -> "вниз"; "left" -> "влево"; "right" -> "вправо"; else -> direction
                }
                val distRu = when (distance) {
                    "short" -> "коротко"; "long" -> "длинно"; else -> "средне"
                }
                ToolResult(
                    toolContent = if (ok) "Swiped $direction" else "Swipe failed",
                    summary = if (ok) "свайп $dirRu, $distRu" else "не удалось свайпнуть $dirRu",
                )
            }
            "swipe_at" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val x1 = args.intOf("x1") ?: return ToolResult.error("Missing x1")
                val y1 = args.intOf("y1") ?: return ToolResult.error("Missing y1")
                val x2 = args.intOf("x2") ?: return ToolResult.error("Missing x2")
                val y2 = args.intOf("y2") ?: return ToolResult.error("Missing y2")
                if (isInsideStopButton(x1, y1) || isInsideStopButton(x2, y2)) {
                    return ToolResult.error("Свайп пересекает кнопку СТОП — это запрещено.")
                }
                val duration = args.intOf("duration_ms")?.toLong() ?: 300L
                val ok = svc.swipe(x1, y1, x2, y2, duration)
                ToolResult(
                    toolContent = if (ok) "Swiped ($x1,$y1)→($x2,$y2)" else "Swipe failed",
                    summary = if (ok) "свайп ($x1,$y1) → ($x2,$y2)" else "не удалось свайпнуть",
                )
            }
            "type_text" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val text = args.stringOf("text") ?: return ToolResult.error("Missing text")
                val nodeId = args.intOf("node_id")
                val ok = if (nodeId != null) {
                    val node = lastScreenState?.nodes?.getOrNull(nodeId)
                        ?: return ToolResult.error("Unknown node_id $nodeId")
                    svc.typeTextInNode(node, text)
                } else {
                    svc.typeText(text)
                }
                ToolResult(
                    toolContent = if (ok) "Typed '$text'" else "Could not find an editable field",
                    summary = if (ok) "введён текст (${text.length} симв.)" else "нет активного поля ввода",
                )
            }
            "press_back" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val ok = svc.pressBack()
                ToolResult(
                    toolContent = if (ok) "Back pressed" else "Back failed",
                    summary = if (ok) "нажата Назад" else "не удалось нажать Назад",
                )
            }
            "press_home" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val ok = svc.pressHome()
                ToolResult(
                    toolContent = if (ok) "Home pressed" else "Home failed",
                    summary = if (ok) "переход на Главный экран" else "не удалось перейти на Главный экран",
                )
            }
            "press_recents" -> {
                val svc = service ?: return ToolResult.error("Accessibility off")
                val ok = svc.pressRecents()
                ToolResult(
                    toolContent = if (ok) "Recents opened" else "Recents failed",
                    summary = if (ok) "открыты Недавние приложения" else "не удалось открыть Недавние",
                )
            }
            "open_app" -> {
                val pkg = args.stringOf("package_name") ?: return ToolResult.error("Missing package_name")
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent == null) {
                    ToolResult.error("App $pkg not installed or no launcher entry.")
                } else {
                    launchIntent.flags = launchIntent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    delay(500)
                    ToolResult(
                        toolContent = "Launched $pkg",
                        summary = "запущено приложение $pkg",
                    )
                }
            }
            "wait" -> {
                val ms = (args.intOf("ms") ?: 500).coerceIn(0, 5000)
                delay(ms.toLong())
                ToolResult(
                    toolContent = "Waited ${ms}ms",
                    summary = "пауза ${ms} мс",
                )
            }
            "ask_user" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                onLog(AgentLog.AskUser(question))
                val answer = askUser(question)
                ToolResult(
                    toolContent = answer,
                    summary = "вопрос «$question» → «$answer»",
                )
            }
            "ask_user_overlay" -> {
                val question = args.stringOf("question") ?: return ToolResult.error("Missing question")
                val options = args.stringListOf("options")
                val labelHint = if (options != null) " [${options.joinToString(" / ")}]" else ""
                onLog(AgentLog.AskUser("[overlay] $question$labelHint"))
                val deferred = CompletableDeferred<String>()
                OverlayService.Pending.deferred = deferred
                OverlayService.showQuestion(context, question, options)
                val answer = try {
                    deferred.await()
                } finally {
                    OverlayService.Pending.deferred = null
                    OverlayService.hide(context)
                }
                ToolResult(
                    toolContent = answer,
                    summary = "overlay «$question» → «$answer»",
                )
            }
            "speak" -> {
                val rawText = args.stringOf("text") ?: return ToolResult.error("Missing text")
                // Cap TTS payload. The user complains about speak() reading whole-screen
                // descriptions like a wall of text. Force the model toward short remarks: hard
                // truncate to MAX_SPEAK_CHARS, strip newlines, log a hint back so the model
                // learns next time.
                val sanitized = rawText.replace(Regex("\\s+"), " ").trim()
                val text = if (sanitized.length > MAX_SPEAK_CHARS) {
                    sanitized.take(MAX_SPEAK_CHARS - 1).trimEnd { it == ',' || it == '.' || it.isWhitespace() } + "…"
                } else {
                    sanitized
                }
                val rate = args.floatOf("rate") ?: settings.ttsRate
                val ok = tts.speak(text, rate)
                val truncatedHint = if (sanitized.length > MAX_SPEAK_CHARS)
                    " (исходник был ${sanitized.length} симв., обрезан до $MAX_SPEAK_CHARS — в следующий раз говори короче, одно предложение)"
                else ""
                ToolResult(
                    toolContent = if (ok) "Spoke ${text.length} chars$truncatedHint" else "TTS failed",
                    summary = if (ok) "озвучено: «${text.take(40)}»" else "не удалось озвучить",
                )
            }
            "listen" -> {
                val lang = args.stringOf("language")
                val transcript = stt.listenLive(language = lang)
                ToolResult(
                    toolContent = transcript,
                    summary = "услышано: «${transcript.take(80)}»",
                )
            }
            "joystick_move" -> {
                if (!com.aiagent.android.overlay.JoystickOverlayService.isActive()) {
                    return ToolResult.error(
                        "Виртуальный джойстик не включён. Попроси пользователя включить его в " +
                            "приложении (вкладка «Агент» → переключатель «Виртуальный джойстик») " +
                            "и расположить поверх внутриигрового джойстика.",
                    )
                }
                val direction = args.stringOf("direction")?.lowercase()
                val angleArg = args.floatOf("angle")
                val angle = angleArg ?: when (direction) {
                    "east", "right", "восток" -> 0f
                    "southeast" -> 45f
                    "south", "down", "юг" -> 90f
                    "southwest" -> 135f
                    "west", "left", "запад" -> 180f
                    "northwest" -> 225f
                    "north", "up", "север" -> 270f
                    "northeast" -> 315f
                    else -> return ToolResult.error(
                        "Укажи direction (north/south/east/west/...) или angle в градусах.",
                    )
                }
                val magnitude = (args.floatOf("magnitude") ?: 1.0f).coerceIn(0.05f, 1f)
                val durationMs = (args.intOf("duration_ms") ?: 600).coerceIn(50, 5000).toLong()
                com.aiagent.android.overlay.JoystickOverlayService.push(
                    context = context,
                    angleDeg = angle,
                    magnitude = magnitude,
                    durationMs = durationMs,
                )
                ToolResult(
                    toolContent = "Joystick pushed at angle ${angle}° magnitude $magnitude for ${durationMs}ms",
                    summary = "джойстик ${direction ?: "${angle}°"} ($magnitude × $durationMs мс)",
                )
            }
            "record_audio" -> {
                val seconds = (args.intOf("seconds") ?: 6).coerceIn(1, 60)
                val lang = args.stringOf("language")
                val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "audio").apply { mkdirs() }
                val outFile = File(outDir, "rec-${System.currentTimeMillis()}.wav")
                val started = micRecorder.start(outFile, maxMs = seconds * 1000L)
                if (!started) return ToolResult.error("Не удалось начать запись (нет разрешения RECORD_AUDIO?).")
                delay(seconds * 1000L)
                val finalFile = micRecorder.stop()
                if (finalFile == null || !finalFile.exists()) {
                    return ToolResult.error("Запись не удалась.")
                }
                val text = stt.transcribeFile(finalFile, language = lang)
                ToolResult(
                    toolContent = text,
                    summary = "${seconds}с → «${text.take(80)}»",
                )
            }
            "device_info" -> {
                val info = DeviceInfo.gather(context)
                ToolResult(
                    toolContent = info,
                    summary = "device_info (${info.length} симв.)",
                )
            }
            "list_files" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.listEntries(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "list $path")
            }
            "read_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val maxBytes = args.intOf("max_bytes") ?: 65536
                val out = try {
                    fileTools.readText(path, maxBytes)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "read $path (${out.length} симв.)")
            }
            "write_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val content = args.stringOf("content") ?: return ToolResult.error("Missing content")
                val mime = args.stringOf("mime_type") ?: "text/plain"
                val out = try {
                    fileTools.writeText(path, content, mime)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "write $path")
            }
            "make_dir" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.makeDir(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "mkdir $path")
            }
            "delete_file" -> {
                val path = args.stringOf("path") ?: return ToolResult.error("Missing path")
                val out = try {
                    fileTools.deletePath(path)
                } catch (e: SecurityException) {
                    "[доступ запрещён: ${e.message}]"
                }
                ToolResult(toolContent = out, summary = "rm $path")
            }
            "start_screen_recording" -> {
                val res = startScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = "запись экрана: $res",
                )
            }
            "stop_screen_recording" -> {
                val res = stopScreenRecording()
                ToolResult(
                    toolContent = res,
                    summary = res,
                )
            }
            "list_apps" -> {
                val includeSystem = args.boolOf("include_system") ?: false
                val filter = args.stringOf("filter")?.lowercase()?.takeIf { it.isNotBlank() }
                val pm = context.packageManager
                val launchablePackages: Set<String> = pm
                    .queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                        0,
                    )
                    .map { it.activityInfo.packageName }
                    .toSet()
                val pkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val rows = pkgs.mapNotNull { app ->
                    val launchable = app.packageName in launchablePackages
                    if (!includeSystem && !launchable) return@mapNotNull null
                    val display = pm.getApplicationLabel(app).toString()
                    if (filter != null) {
                        val hay = (display + " " + app.packageName).lowercase()
                        if (!hay.contains(filter)) return@mapNotNull null
                    }
                    "$display | ${app.packageName} | ${if (launchable) "launchable" else "background"}"
                }.sorted()
                val out = if (rows.isEmpty()) "(приложений не найдено)"
                else rows.joinToString("\n")
                ToolResult(
                    toolContent = out,
                    summary = "${rows.size} прил.",
                )
            }
            "get_clipboard" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context).toString()
                } else ""
                ToolResult(
                    toolContent = if (text.isEmpty()) "(буфер пуст)" else text,
                    summary = "буфер: ${text.take(40)}",
                )
            }
            "set_clipboard" -> {
                val text = args.stringOf("text") ?: return ToolResult.error("set_clipboard: text required")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ai-agent", text))
                ToolResult(
                    toolContent = "OK (${text.length} симв.)",
                    summary = "буфер ← «${text.take(40)}»",
                )
            }
            "set_volume" -> {
                val streamName = args.stringOf("stream") ?: "music"
                val absolute = args.intOf("level")
                val relative = args.intOf("relative")
                val streamId = when (streamName.lowercase()) {
                    "music" -> AudioManager.STREAM_MUSIC
                    "ring" -> AudioManager.STREAM_RING
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "alarm" -> AudioManager.STREAM_ALARM
                    "voice_call", "call", "voice" -> AudioManager.STREAM_VOICE_CALL
                    "system" -> AudioManager.STREAM_SYSTEM
                    else -> AudioManager.STREAM_MUSIC
                }
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(streamId)
                val target = when {
                    absolute != null -> absolute.coerceIn(0, max)
                    relative != null -> {
                        val cur = am.getStreamVolume(streamId)
                        val delta = (relative * max / 100f).toInt()
                        (cur + delta).coerceIn(0, max)
                    }
                    else -> return ToolResult.error("set_volume: укажите level или relative")
                }
                runCatching {
                    am.setStreamVolume(streamId, target, AudioManager.FLAG_SHOW_UI)
                }.onFailure {
                    return ToolResult.error("set_volume: ${it.message ?: "не удалось"} (стрим $streamName может требовать notification policy access)")
                }
                ToolResult(
                    toolContent = "stream=$streamName уровень=$target/$max",
                    summary = "$streamName: $target/$max",
                )
            }
            "set_brightness" -> {
                val level = args.intOf("level") ?: return ToolResult.error("set_brightness: level required")
                // We can only adjust the per-window brightness. The system brightness needs
                // WRITE_SETTINGS, which we deliberately don't request.
                val activityBrightness = if (level < 0) -1f else (level.coerceIn(0, 100) / 100f)
                // The accessibility service can't change activity window params directly; we
                // ask the overlay service if it's running, otherwise just acknowledge.
                OverlayService.applyBrightness(context, activityBrightness)
                ToolResult(
                    toolContent = "overlay-brightness=$activityBrightness (системная яркость не меняется)",
                    summary = "яркость overlay: $activityBrightness",
                )
            }
            "recall_user_actions" -> {
                val limit = (args.intOf("limit") ?: 50).coerceIn(1, 200)
                val entries = UserActionLog.snapshot(limit = limit)
                if (entries.isEmpty()) {
                    ToolResult(
                        toolContent = "Журнал пуст: пользователь не делал действий с момента включения службы Спецвозможностей.",
                        summary = "память: 0 действий",
                    )
                } else {
                    val rendered = UserActionLog.renderForPrompt(entries)
                    ToolResult(
                        toolContent = "Последние ${entries.size} действий пользователя:\n$rendered",
                        summary = "память: ${entries.size} действий",
                    )
                }
            }
            "request_demonstration" -> {
                val prompt = args.stringOf("prompt")
                    ?: return ToolResult.error("Missing prompt: short description of what you want the user to demonstrate.")
                onLog(AgentLog.AskUser("[демонстрация] $prompt"))
                // Phase 1: ask the user to confirm they're ready to demonstrate. Nothing is
                // recorded yet — the user might still be in some other app, doing something
                // unrelated. We do NOT touch UserActionLog until they tap "Готов показывать".
                val confirmDef = CompletableDeferred<String>()
                OverlayService.DemoPending.deferred = confirmDef
                OverlayService.showDemonstrationConfirm(context, prompt)
                val confirm = try {
                    confirmDef.await()
                } finally {
                    OverlayService.DemoPending.deferred = null
                }
                if (confirm != "ready") {
                    OverlayService.hideDemonstration(context)
                    ToolResult(
                        toolContent = "Пользователь отменил демонстрацию (ответ: $confirm). Действуй сам.",
                        summary = "демо отменено",
                    )
                } else {
                    // Phase 2: actually record what the user does until they tap «Готово». While
                    // we are in the loop we release `agentDriving` so the AccessibilityService's
                    // record() path will accept events, and we drop into demonstration mode so
                    // events go to the dedicated demo buffer.
                    UserActionLog.startDemonstration()
                    UserActionLog.markAgentEnd()
                    val doneDef = CompletableDeferred<String>()
                    OverlayService.DemoPending.deferred = doneDef
                    OverlayService.showDemonstrationActive(context)
                    val outcome = try {
                        doneDef.await()
                    } finally {
                        OverlayService.DemoPending.deferred = null
                        OverlayService.hideDemonstration(context)
                        // Re-assert that the agent is driving so the *next* tool we run
                        // doesn't log itself; the surrounding markAgentStart already wrapped
                        // this call.
                        UserActionLog.markAgentStart()
                    }
                    val captured = UserActionLog.stopDemonstrationAndDrain()
                    if (captured.isEmpty()) {
                        ToolResult(
                            toolContent = "Пользователь нажал «$outcome», но ничего не нажимал / не вводил во время демонстрации. " +
                                "Спроси ещё раз словами что нужно сделать.",
                            summary = "демо: 0 действий",
                        )
                    } else {
                        val rendered = UserActionLog.renderForPrompt(captured)
                        ToolResult(
                            toolContent = "Пользователь показал ${captured.size} действий (демонстрация по запросу «$prompt»):\n" +
                                "$rendered\n\nИспользуй это как пример: какие именно элементы UI он трогал, какие тексты вводил и в каком порядке.",
                            summary = "демо: ${captured.size} действий",
                        )
                    }
                }
            }
            "done" -> {
                val summary = args.stringOf("summary") ?: "(без описания)"
                val success = args.boolOf("success") ?: true
                ToolResult(
                    toolContent = "Acknowledged: $summary",
                    summary = if (success) "завершено: $summary" else "прекращено: $summary",
                    done = DoneSignal(summary, success),
                )
            }
            "web_search" -> {
                val query = args.stringOf("query")
                    ?: return ToolResult.error("web_search: укажи параметр `query` (что искать).")
                val maxResults = (args.intOf("max_results") ?: 8).coerceIn(1, 15)
                val out = try {
                    WebSearch.search(query, maxResults)
                } catch (e: Exception) {
                    return ToolResult.error(
                        "web_search не дотянулся до интернета: ${e.message ?: e::class.java.simpleName}. " +
                            "Проверь, что у устройства есть сеть и DuckDuckGo не заблокирован.",
                    )
                }
                ToolResult(
                    toolContent = out,
                    summary = "поиск: «${query.take(40)}»",
                )
            }
            "fetch_url" -> {
                val url = args.stringOf("url")
                    ?: return ToolResult.error("fetch_url: укажи параметр `url`.")
                val out = try {
                    WebSearch.fetch(url)
                } catch (e: Exception) {
                    return ToolResult.error(
                        "fetch_url не загрузил $url: ${e.message ?: e::class.java.simpleName}.",
                    )
                }
                ToolResult(
                    toolContent = out,
                    summary = "загрузка $url (${out.length} симв.)",
                )
            }
            "share_file" -> {
                val path = args.stringOf("path")
                    ?: return ToolResult.error("share_file: укажи параметр `path`.")
                val mime = args.stringOf("mime_type")
                val msg = FileShareHelper.share(context, path, mime)
                ToolResult(
                    toolContent = msg,
                    summary = "поделено: ${path.takeLast(40)}",
                )
            }
            "open_file" -> {
                val path = args.stringOf("path")
                    ?: return ToolResult.error("open_file: укажи параметр `path`.")
                val mime = args.stringOf("mime_type")
                val msg = FileShareHelper.open(context, path, mime)
                ToolResult(
                    toolContent = msg,
                    summary = "открыто: ${path.takeLast(40)}",
                )
            }
            "current_time" -> {
                val zoneArg = args.stringOf("timezone")
                val zone = if (zoneArg.isNullOrBlank()) {
                    java.time.ZoneId.systemDefault()
                } else {
                    runCatching { java.time.ZoneId.of(zoneArg) }
                        .getOrElse { java.time.ZoneId.systemDefault() }
                }
                val now = java.time.ZonedDateTime.now(zone)
                val iso = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val dow = now.dayOfWeek
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale("ru"))
                val ts = now.toEpochSecond()
                ToolResult(
                    toolContent = "now=$iso\ntimezone=${zone.id}\nweekday=$dow\nepoch_seconds=$ts",
                    summary = "время: $iso",
                )
            }
            "spawn_subagent" -> {
                val task = args.stringOf("task")
                    ?: return ToolResult.error("spawn_subagent: укажи параметр `task`.")
                val label = args.stringOf("label")
                val id = SubAgentManager.spawn(settings, task, label)
                ToolResult(
                    toolContent = "Spawned sub-agent id=$id. Use list_subagents / " +
                        "get_subagent_result(id) to check on it. It runs in parallel, you can " +
                        "spawn more without waiting.",
                    summary = "запущен суб-агент $id",
                )
            }
            "list_subagents" -> {
                val list = SubAgentManager.list()
                val text = if (list.isEmpty()) {
                    "(no sub-agents in this session)"
                } else buildString {
                    appendLine("sub_agents:")
                    val now = System.currentTimeMillis()
                    for (j in list) {
                        val ageMs = (j.finishedAt ?: now) - j.startedAt
                        appendLine(
                            "- id=${j.id}  status=${j.status.name.lowercase()}  age=${ageMs}ms  label=${j.label}"
                        )
                    }
                }
                ToolResult(
                    toolContent = text,
                    summary = "суб-агенты: ${list.size}",
                )
            }
            "get_subagent_result" -> {
                val id = args.stringOf("id")
                    ?: return ToolResult.error("get_subagent_result: укажи параметр `id`.")
                val waitMs = (args.intOf("wait_ms") ?: 0).coerceIn(0, 10_000)
                if (waitMs > 0) {
                    val deadline = System.currentTimeMillis() + waitMs
                    while (System.currentTimeMillis() < deadline &&
                        SubAgentManager.get(id)?.status == SubAgentManager.Status.RUNNING
                    ) {
                        delay(150)
                    }
                }
                val j = SubAgentManager.get(id)
                    ?: return ToolResult.error("Unknown sub-agent id=$id (maybe never spawned, or cleared).")
                val text = when (j.status) {
                    SubAgentManager.Status.RUNNING ->
                        "[running] sub-agent $id still working. Use wait_ms=… or come back later."
                    SubAgentManager.Status.DONE -> j.result.orEmpty()
                    SubAgentManager.Status.ERROR -> "[error] ${j.error ?: "unknown error"}"
                    SubAgentManager.Status.CANCELLED -> "[cancelled] sub-agent was cancelled."
                }
                ToolResult(
                    toolContent = text,
                    summary = "суб-агент $id → ${j.status.name.lowercase()}",
                )
            }
            "cancel_subagent" -> {
                val id = args.stringOf("id")
                    ?: return ToolResult.error("cancel_subagent: укажи параметр `id`.")
                val ok = SubAgentManager.cancel(id)
                ToolResult(
                    toolContent = if (ok) "Cancelled sub-agent $id." else "Sub-agent $id was already finished or unknown.",
                    summary = if (ok) "отменён суб-агент $id" else "нечего отменять для $id",
                )
            }
            else -> ToolResult.error("Unknown tool: ${call.function.name}")
        }
    }

    /**
     * Capture a screen bitmap, throttled by `Settings.screenFps`.
     *
     * Tries the Accessibility `takeScreenshot()` API first (no extra permission, no notification).
     * If that fails — which happens on Android < 11 and on a number of OEM ROMs (Realme, Vivo,
     * Xiaomi MIUI in particular) — we fall back to MediaProjection: ask the user for one-time
     * consent, start a long-lived [ScreenCaptureService], and pull frames from its ImageReader.
     */
    private suspend fun throttleAndCapture(service: AgentAccessibilityService): Bitmap? {
        val fps = settings.screenFps
        if (fps > 0f) {
            val minIntervalMs = (1000f / fps).toLong()
            val sinceLast = System.currentTimeMillis() - lastScreenshotMs
            if (sinceLast < minIntervalMs) {
                delay(minIntervalMs - sinceLast)
            }
        }
        lastScreenshotMs = System.currentTimeMillis()

        // Fast path: Accessibility takeScreenshot.
        if (!ScreenCaptureService.isRunning) {
            val bitmap = service.captureBitmap()
            if (bitmap != null) return bitmap
        }

        // Slow path: MediaProjection. Start the capture service if it isn't running yet.
        if (!ScreenCaptureService.isRunning) {
            // The user already declined this run; don't pop the system dialog again.
            if (projectionDenied) return null
            val ok = try {
                ensureCaptureService()
            } catch (e: Exception) {
                onLog(AgentLog.Error("Не удалось запустить захват экрана: ${e.message}"))
                false
            }
            if (!ok) {
                projectionDenied = true
                return null
            }
        }
        return ScreenCaptureService.captureFrame()
    }

    private fun saveBitmapToPng(bitmap: Bitmap): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshots").apply { mkdirs() }
        val name = "shot-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }

    /**
     * True if the given model id is known to accept the `reasoning_effort` parameter.
     *
     * Sources: https://console.groq.com/docs/reasoning (Groq supports it on gpt-oss-* and
     * qwen3-32b) and the OpenAI reasoning models (o1 / o3 / o4 / gpt-5 with reasoning).
     */
    private fun supportsReasoningEffort(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("gpt-oss") ||
            id.contains("qwen3") ||
            id.startsWith("o1") || id.contains("/o1") ||
            id.startsWith("o3") || id.contains("/o3") ||
            id.startsWith("o4") || id.contains("/o4")
    }

    /**
     * True if the given model id is known to accept image inputs (multimodal). Includes Groq's
     * llama-3.2-vision, llama-4 Scout/Maverick lineup, OpenAI gpt-4o / gpt-4-turbo / gpt-4-vision /
     * gpt-5, Anthropic claude-3+, and Gemini.
     */
    private fun supportsVision(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("vision") ||
            id.contains("llama-4") ||
            id.contains("llama4") ||
            id.contains("scout") ||
            id.contains("maverick") ||
            id.contains("gpt-4o") ||
            id.contains("gpt-4-turbo") ||
            id.contains("gpt-4-vision") ||
            id.contains("gpt-5") ||
            id.contains("claude-3") ||
            id.contains("claude-4") ||
            id.contains("claude-sonnet") ||
            id.contains("claude-opus") ||
            id.contains("claude-haiku") ||
            id.contains("gemini") ||
            id.contains("pixtral")
    }

    /**
     * True if the agent should attach raw screenshots to controller-LLM messages this run.
     *
     * IMPORTANT: we only attach images when the configured *controller* model is known to be
     * multimodal. If a user enables "send screenshots" while the controller is a text-only
     * model (e.g. `openai/gpt-oss-120b`), Groq returns
     * `HTTP 400: messages[N].content must be a string`. The two-model "vision describer" path
     * is the right way to feed pixel info to a text controller — it converts the image to text
     * via a separate vision model BEFORE the controller sees it.
     */
    private fun visionEnabled(): Boolean =
        settings.sendScreenshots && supportsVision(settings.model)

    /**
     * Walk the conversation in reverse and keep only the [keep] most recent multimodal
     * (image-bearing) user messages. Older multimodal messages are rewritten in place to
     * plain-text so the API stops counting them as images. Llama-4 on Groq currently caps
     * inputs at 5 images per request — without this we hit
     * `HTTP 400: Too many images provided` after a handful of read_screen calls.
     */
    private fun trimOldScreenshots(messages: MutableList<ChatMessage>, keep: Int) {
        var imagesRemaining = keep
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val content = msg.content ?: continue
            if (content !is JsonArray) continue
            // Multimodal message. If we still have budget, leave it; otherwise replace with text.
            if (imagesRemaining > 0) {
                imagesRemaining--
                continue
            }
            val plainText = extractTextFromMultimodal(content)
            messages[i] = ChatMessage(
                role = msg.role,
                content = JsonPrimitive(
                    if (plainText.isBlank()) "[старый скриншот опущен для экономии токенов]"
                    else "$plainText [скриншот опущен]",
                ),
                toolCalls = msg.toolCalls,
                toolCallId = msg.toolCallId,
                name = msg.name,
            )
        }
    }

    /**
     * Best-effort recovery for the case where a text-only model (e.g. gpt-oss-120b) decides
     * to serialize a tool invocation as JSON in the assistant `content` field instead of
     * using the proper `tool_calls` API. We accept several common shapes:
     *
     *   1. `{"toolName": {...args}}`             ← the most common form we see in the wild
     *   2. `{"name": "toolName", "arguments": ...}` ← OpenAI's older function-call shape
     *   3. `{"tool": "toolName", "args": {...}}` ← occasional variant
     *
     * Returns a synthetic [ToolCall] if exactly one known tool name is detected; null
     * otherwise. Knowingly conservative — we only attempt parses that look like JSON, never
     * try to coerce arbitrary natural-language text into a tool call.
     */
    private fun recoverToolCallFromText(raw: String): ToolCall? {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!trimmed.startsWith("{")) return null
        val knownTools: Set<String> = com.aiagent.android.agent.Tools.toolList()
            .map { it.function.name }
            .toSet()
        val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            as? JsonObject ?: return null

        // Shape #2: explicit name + arguments.
        val explicitName = parsed["name"]?.jsonPrimitive?.contentOrNull
            ?: parsed["tool"]?.jsonPrimitive?.contentOrNull
        if (explicitName != null && explicitName in knownTools) {
            val argsElem = parsed["arguments"] ?: parsed["args"] ?: JsonObject(emptyMap())
            val argsString = if (argsElem is JsonPrimitive && argsElem.isString) {
                argsElem.contentOrNull.orEmpty()
            } else {
                argsElem.toString()
            }
            return ToolCall(
                id = "recovered_${System.nanoTime()}",
                type = "function",
                function = FunctionCall(name = explicitName, arguments = argsString),
            )
        }

        // Shape #1: top-level key is the tool name.
        val matchedName = parsed.keys.firstOrNull { it in knownTools } ?: return null
        val args = parsed[matchedName] as? JsonObject ?: return null
        return ToolCall(
            id = "recovered_${System.nanoTime()}",
            type = "function",
            function = FunctionCall(name = matchedName, arguments = args.toString()),
        )
    }

    private fun extractTextFromMultimodal(content: JsonElement): String {
        if (content !is JsonArray) return ""
        return content.joinToString(" ") { part ->
            val obj = (part as? JsonObject) ?: return@joinToString ""
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
        }.trim()
    }

    /** True if the given screen-pixel point falls inside the persistent overlay STOP button.
     *  Used to refuse tap_at / swipe_at calls so the AI cannot click its own kill switch. */
    private fun isInsideStopButton(x: Int, y: Int): Boolean {
        val r = OverlayService.stopButtonBounds ?: return false
        val pad = 16
        return x in (r.left - pad)..(r.right + pad) &&
            y in (r.top - pad)..(r.bottom + pad)
    }

    /**
     * Encode a bitmap as a `data:image/jpeg;base64,...` URL after downscaling so the longest side
     * is at most [Settings.screenshotMaxDim] pixels. JPEG is used for ~10× smaller payload than
     * PNG at quality 80, which dramatically reduces upload size and token usage.
     */
    /**
     * Two-model mode: ask the configured vision describer to write a textual summary of the
     * given screenshot. The result is plain Russian prose suitable to drop into the controller
     * model's history. Returns null if the call fails — the agent keeps going without the
     * description so a transient describer error doesn't kill the whole step.
     */
    private suspend fun describeScreenshotWithVisionModel(
        controller: ChatBackend,
        bitmap: Bitmap,
    ): String? = describeDataUrlWithVisionModel(controller, bitmapToDataUrl(bitmap))

    private suspend fun describeDataUrlWithVisionModel(
        controller: ChatBackend,
        dataUrl: String,
    ): String? {
        val req = ChatRequest(
            model = settings.visionDescriberModel,
            messages = listOf(
                textMessage(
                    role = "system",
                    text = "Ты — модель-наблюдатель для агента-помощника в играх. Тебе показывают " +
                        "скриншот экрана Android-устройства. Опиши его подробно (5-10 предложений) " +
                        "так, чтобы текстовая модель-контроллер могла принять решение БЕЗ доступа к " +
                        "пикселям:\n" +
                        "- какое приложение / игра / экран сейчас открыт;\n" +
                        "- ключевые UI-элементы (кнопки, поля, тексты, иконки) — где они расположены, " +
                        "как выглядят, какого цвета, на что похожи (если иконка — на что она похожа);\n" +
                        "- состояние игры если это игра: HP/мана/таймер/счёт, какие враги/предметы " +
                        "видны, в каком углу что лежит, цветные индикаторы;\n" +
                        "- модальные окна / диалоги / уведомления — что там написано;\n" +
                        "- что вообще происходит на экране СЕЙЧАС.\n" +
                        "Пиши сразу описание, простыми фразами на русском, без вступлений типа " +
                        "'я вижу' или 'на скриншоте'.",
                ),
                userImageMessage(
                    text = "Опиши, что на этом скриншоте.",
                    imageDataUrl = dataUrl,
                ),
            ),
            // No tools — the describer just writes prose.
            tools = null,
            toolChoice = null,
            temperature = 0.2,
            maxCompletionTokens = 700,
            reasoningEffort = null,
        )
        return try {
            val resp = controller.chat(req)
            resp.choices.firstOrNull()?.message?.contentText?.trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Vision describer failed: ${e.message}")
            onLog(AgentLog.Error("Модель-наблюдатель не ответила: ${e.message ?: e::class.java.simpleName}. Шаг продолжается без описания."))
            null
        }
    }

    private fun bitmapToDataUrl(bitmap: Bitmap): String {
        val maxDim = settings.screenshotMaxDim.coerceAtLeast(256)
        val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else bitmap
        val out = java.io.ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun computeSwipe(direction: String, distance: String): IntArray {
        val service = AgentAccessibilityService.instance
        val metrics = service?.resources?.displayMetrics
        val w = metrics?.widthPixels ?: 1080
        val h = metrics?.heightPixels ?: 1920
        val dist = when (distance) {
            "short" -> 0.25
            "long" -> 0.75
            else -> 0.5
        }
        val cx = w / 2
        val cy = h / 2
        return when (direction) {
            "up" -> intArrayOf(cx, (h * (0.5 + dist / 2)).toInt(), cx, (h * (0.5 - dist / 2)).toInt())
            "down" -> intArrayOf(cx, (h * (0.5 - dist / 2)).toInt(), cx, (h * (0.5 + dist / 2)).toInt())
            "left" -> intArrayOf((w * (0.5 + dist / 2)).toInt(), cy, (w * (0.5 - dist / 2)).toInt(), cy)
            "right" -> intArrayOf((w * (0.5 - dist / 2)).toInt(), cy, (w * (0.5 + dist / 2)).toInt(), cy)
            else -> intArrayOf(cx, cy, cx, cy)
        }
    }

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun JsonObject.intOf(key: String): Int? =
        (get(key) as? JsonPrimitive)?.intOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolOf(key: String): Boolean? =
        runCatching { (get(key) as? JsonPrimitive)?.boolean }.getOrNull()

    private fun JsonObject.floatOf(key: String): Float? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    private fun JsonObject.stringListOf(key: String): List<String>? =
        (get(key) as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "Agent"

        /**
         * Set to true when the user taps the «Ответить сразу» button in the chat UI to
         * skip the model's reasoning phase. Checked at the start of every turn (to drop
         * `reasoning_effort` from the request) AND inside the streaming reasoning
         * callback (to stop forwarding reasoning fragments to the UI mid-stream). Cleared
         * by the agent at the start of each turn after it has been consumed.
         *
         * Volatile because the toggle is set from the UI thread while the agent reads it
         * from a background coroutine — JVM atomicity for boolean is enough.
         */
        @Volatile
        var forceAnswerRequested: Boolean = false

        /** Voice / text messages pushed by the user from the floating ⚙️ overlay while the
         *  agent is mid-step. Drained at the start of each turn and injected as user messages
         *  so the controller sees them and can react. Thread-safe queue. */
        val userInterrupts: java.util.concurrent.ConcurrentLinkedQueue<String> =
            java.util.concurrent.ConcurrentLinkedQueue()

        /** Wakes a [waitForNextMessage] call when a fresh user message is pushed. CONFLATED
         *  so multiple back-to-back pushes collapse into a single signal — we only care
         *  that *something* arrived, not how many. Internal: callers should use
         *  [pushInterrupt] / [waitForNextMessage] instead of touching this directly. */
        internal val newInterruptSignalChannel: Channel<Unit> = Channel(Channel.CONFLATED)

        /**
         * Push a fresh user message into the queue AND wake any agent that is currently
         * suspended in [waitForNextMessage]. Use this from anywhere outside the agent (the
         * floating overlay listener, voice STT callback, etc.) instead of touching
         * [userInterrupts] directly — otherwise a wait-for-message agent will not unblock
         * until the next signal happens to fire.
         */
        fun pushInterrupt(message: String) {
            if (message.isBlank()) return
            userInterrupts.offer(message)
            newInterruptSignalChannel.trySend(Unit)
        }

        /**
         * Emergency wipe. Used by the "Дед инсайд" panic button: drops every queued user
         * interrupt so a previously-running wait-for-message agent that gets cancelled doesn't
         * surface old messages on the next launch.
         */
        fun shutdown() {
            userInterrupts.clear()
            // Wake any suspended waiter so it observes cancellation immediately.
            newInterruptSignalChannel.trySend(Unit)
        }
        /** Vision-capable models on Groq cap inputs at 5 images/request; 3 keeps headroom for
         *  the model's own returned tool messages without hitting the limit. */
        private const val MAX_IMAGES_IN_HISTORY = 3
        /** Hard cap on text passed to the TTS engine. The model frequently tries to read full
         *  multi-paragraph screen descriptions; we silently truncate to keep TTS short and
         *  game-friendly. */
        private const val MAX_SPEAK_CHARS = 220
        private const val SYSTEM_PROMPT = """You are an AI agent that lives on the user's Android phone and helps them — especially during gameplay. You can observe the screen, listen to audio, speak, write files, and control the UI through Accessibility.

You have these tools:

VISION
- read_screen        → list the active app and its interactive UI nodes (use first; fast). When vision-mode is enabled the same call also delivers the screen as an image to the next turn, so you can really SEE the pixels — even of games / SurfaceView apps where the a11y tree is empty.
- read_screen_text   → on-device OCR of the current screen pixels (slower; use when read_screen returns nothing useful, e.g. inside games / video players that draw to a SurfaceView).
- take_screenshot    → save a PNG of the current screen to disk and return its path. With vision-mode it also delivers the screen image to the next turn.

ACTUATION
- tap / tap_at / swipe / swipe_at / type_text → interact with the UI.
- press_back / press_home / press_recents     → system navigation.
- open_app(package_name)                      → launch an app by package.
- wait(ms)                                    → pause for animations.

VOICE / AUDIO
- speak(text, rate?)              → say something out loud through the device speaker. **Keep it under ~200 chars (one short sentence).** Anything longer is silently truncated. NEVER read out a whole screen description — summarise in one phrase.
- listen(language?)               → one-shot live mic listen via the platform recogniser.
- record_audio(seconds, language?) → record N seconds of mic audio and transcribe via Whisper.

USER INTERACTION
- ask_user(question)         → ask a free-form question; the answer comes back as text.
- ask_user_overlay(question) → display a 50%-transparent overlay on top of the current app (works during gameplay) with Yes/No/Open/Dismiss buttons. Result: 'yes' / 'no' / 'dismiss' / the user's typed answer.

DEVICE / FILES
- device_info  → model, OS, screen, RAM, battery, network, hardware features.
- list_apps(include_system?, filter?)  → installed applications on the device, one per line as `display_name | package | launchable`.
- list_files(path), read_file(path), write_file(path, content), make_dir(path), delete_file(path)
   Path rules: 'content://...' or 'name/sub/path' relative to one of the user's allowed folders, or — only when 'all-files' mode is enabled in Settings — an absolute path like '/storage/emulated/0/...'.

CLIPBOARD / SYSTEM
- get_clipboard / set_clipboard(text)  → read or replace the system clipboard.
- set_volume(stream, level | relative) → adjust media / ring / notification / alarm / voice_call / system volume.
- set_brightness(level)                → adjust the overlay brightness (0-100; -1 = follow system).

VIDEO RECORDING
- start_screen_recording / stop_screen_recording → MP4 of the screen via MediaProjection. The first call pauses for the system consent dialog.

USER ACTION MEMORY (когда «Запоминать действия пользователя» включено в Настройках)
- The system silently observes everything the user does on the device while you are NOT
  driving — taps, scrolls, text input, app switches — and at the start of every turn
  injects a `Действия пользователя…` system message describing those events in order.
- Use that summary as ground truth for what the user did between messages. Reference it when
  responding (e.g. «вижу, ты открыл чат и написал X — вот мой ответ»). Do NOT read the raw
  list out loud via `speak`; summarise.
- recall_user_actions(limit?) → fetch the last N user actions explicitly (without clearing).
  Use this if you need older history that was already cleared from the auto-injected note.

DEMONSTRATION FALLBACK
- request_demonstration(prompt) → use this when the task needs a specific UI interaction you
  cannot figure out from read_screen / OCR alone (unlabelled game buttons, custom menus,
  in-game chat boxes, registration forms with non-obvious flow, etc.). The user gets a
  two-phase overlay: first they confirm they are ready, then everything they tap / type / swipe
  is recorded and returned to you when they hit «Готово». Treat the result as a script — replay
  the same coordinates / texts when you take over again. Costs user attention, so try
  read_screen + tap_at first; reach for request_demonstration only after you've actually
  failed once on your own.

WAIT-FOR-MESSAGE (когда «Ждать новое сообщение» включено в Настройках)
- After you finish your reply (no more tool calls, OR you call `done`), the loop will
  cooperatively suspend. It does NOT exit. It waits in the background for a fresh user
  message to arrive via the floating overlay (voice or typed). When that happens, the next
  turn starts automatically with the full conversation history + the user-action memory of
  everything that happened while you were waiting.
- Concretely: just answer the user's current question, take whatever in-game actions are
  asked of you, and stop. The system will route the user's next message back to you.

DONE
- done(summary) → report progress on the current sub-task. **This does NOT end the agent.**
  Only the user can stop the agent, by tapping the floating red "🛑 СТОП" button that the app
  renders over every screen. Do not try to tap that button — `tap_at` / `swipe_at` will refuse
  any coordinate that lands inside it.

Workflow rules:
1. For typical UI tasks, START with `read_screen`. If the foreground is a game / SurfaceView, also call `read_screen_text` for OCR.
2. After every UI mutation (tap / type / swipe / open_app / press_*), re-call `read_screen` (and `read_screen_text` for games) BEFORE deciding the next action.
3. Prefer `tap(node_id)` over `tap_at(x,y)` whenever a node id is available.
4. If the user is in a game and asked you to comment / coach: prefer `speak` for short remarks (one sentence), and `ask_user_overlay` for yes/no questions so the game stays in focus.
5. If the user asked you to read out chat or a system message that is rendered in a game / image, use `read_screen_text` to get the text first, then `speak` it.
6. Keep `type_text` payloads under 1000 characters and avoid embedded newlines unless absolutely required.
7. When file writes / deletions are destructive, confirm with `ask_user_overlay` first.
8. When you finish a sub-task and there is nothing else to do RIGHT NOW, call `done(summary)`.
   Behaviour depends on user's settings:
   - Default (chatbot mode): the loop pauses after `done` and waits for the user's next
     message. Just answer and call `done` — the user resumes manually with «Продолжить».
   - If "Не останавливаться" is ON (live-coach mode): you stay running. Don't spam tools
     just to "stay busy". If nothing meaningful happens on screen, output exactly `жду`
     (one word) and return no tool calls — the loop will then nudge you with a system
     message; do NOT keep repeating "если нужно что-то конкретное, скажите", that's spam.
9. **NEVER claim you can see the screen unless you actually called `read_screen` / `take_screenshot` / `read_screen_text` in THIS turn, OR a fresh screenshot was injected by the system at the top of this turn (look for «Текущий кадр экрана»).** When the user asks "что ты видишь" / "what do you see", look at the latest screenshot in your context and describe ONLY what's in it; do not invent content.
10. Reply in the user's language (default Russian) for user-facing strings (`speak`, `ask_user`, `ask_user_overlay`, `done.summary`).
"""

        /**
         * Tools that physically drive the device through Accessibility / overlays /
         * MediaProjection. These require the user to have "Управлять телефоном" enabled in
         * settings AND the AccessibilityService to be running. Anything outside this set
         * (web_search, fetch_url, share_file, list_files, read_file, write_file, ask_user,
         * speak, …) works regardless of those flags.
         */
        val DEVICE_CONTROL_TOOLS: Set<String> = setOf(
            "read_screen",
            "read_screen_text",
            "take_screenshot",
            "tap",
            "tap_at",
            "swipe",
            "swipe_at",
            "type_text",
            "press_back",
            "press_home",
            "press_recents",
            "joystick_move",
            "start_screen_recording",
            "stop_screen_recording",
            "ask_user_overlay",
            "request_demonstration",
        )

        /**
         * Build the system prompt for a run. The base prompt is the long [SYSTEM_PROMPT]
         * constant; we then prepend a short, dynamic block describing the current state of the
         * user-controlled toggles so the model knows up-front which tools will work this run.
         *
         * If the user supplied a custom prompt in Settings → "Системный промпт" we still use
         * that as the base — but we ALWAYS prepend the status block. Otherwise the model has
         * to discover via tool errors that device control is off, which wastes a tool call.
         */
        fun buildSystemPrompt(
            userSystemPrompt: String,
            deviceControlOn: Boolean,
            accessibilityOn: Boolean,
            inBackground: Boolean,
            isKiroTransport: Boolean = false,
        ): String {
            val base = userSystemPrompt.takeIf { it.isNotBlank() } ?: SYSTEM_PROMPT
            val ctl = if (deviceControlOn) {
                if (accessibilityOn) {
                    "Управление устройством: ВКЛЮЧЕНО, Спецвозможности активны. " +
                        "Можешь использовать UI-инструменты (read_screen, tap, swipe, type_text, …)."
                } else {
                    "Управление устройством: включено в настройках, но Спецвозможности не " +
                        "запущены. UI-инструменты вернут «accessibility off» — попроси пользователя " +
                        "включить службу или работай без неё (web_search / read_file / write_file / share_file)."
                }
            } else {
                "Управление устройством: ВЫКЛЮЧЕНО пользователем. UI-инструменты (read_screen, " +
                    "tap, swipe, type_text, joystick_move, take_screenshot, press_*) вернут soft-ошибку. " +
                    "Решай задачи без управления экраном: web_search для поиска, fetch_url для чтения " +
                    "сайтов, read_file/write_file для работы с файлами, share_file/open_file для " +
                    "передачи файлов пользователю, ask_user для уточнений."
            }
            val bg = if (inBackground) {
                "Фоновый режим: ВКЛ. Пользователь может быть в другом приложении — твоё " +
                    "уведомление с прогрессом видно в шторке. Когда закончишь (done или " +
                    "истечение шагов), пользователю прилетит уведомление о завершении. " +
                    "Не торопись, проверяй результат каждого шага через read_screen/read_file."
            } else {
                "Фоновый режим: ВЫКЛ. Пользователь сейчас в чате — отвечай быстро и кратко."
            }
            // Inject the wall-clock time at prompt-build time so the model never has to guess
            // the current date from its training cut-off. The `current_time` tool is still
            // available for queries that need a different timezone or a refreshed timestamp
            // mid-run, but the everyday "what year/day is it" question is now answered upfront.
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            val iso = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val dow = now.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL,
                Locale("ru"),
            )
            val timeLine = "Текущее время устройства: $iso ($dow, ${zone.id}). " +
                "Используй это значение, не угадывай дату из обучения. Для пересчёта в другой " +
                "часовой пояс или повторного запроса — вызови `current_time`."
            return buildString {
                appendLine("# Текущее состояние устройства")
                appendLine(ctl)
                appendLine(bg)
                appendLine(timeLine)
                if (isKiroTransport) {
                    // Kiro's native CodeWhisperer transport does NOT speak the OpenAI
                    // function-calling protocol — there's no `tool_calls` array on the
                    // response. To make Kiro behave like an actual agent (use tools across
                    // multiple turns) we ask the model to emit every tool invocation as a
                    // standalone JSON object that the agent's [recoverToolCallFromText]
                    // helper can parse. The format mirrors what other Claude/Anthropic
                    // agent harnesses use, so the model already understands it.
                    appendLine("# Агентный режим (Kiro AI)")
                    appendLine(KIRO_AGENT_PROTOCOL)
                }
                appendLine("# Базовая инструкция")
                append(base)
            }
        }

        /**
         * Static block injected into the system prompt when the active transport is Kiro.
         * Tells the model how to invoke tools through inline JSON since Kiro doesn't carry
         * the OpenAI `tool_calls` array on its wire format. The agent's text recovery path
         * (`recoverToolCallFromText`) parses both forms below.
         */
        private const val KIRO_AGENT_PROTOCOL = """Этот транспорт (Kiro / Amazon Q) НЕ поддерживает OpenAI tool calls. Чтобы вызвать инструмент, верни в своём ответе РОВНО ОДИН JSON-объект (без markdown-фенсов, без бектиков, без лишнего текста вокруг):

  Формат1: {"name":"<tool_name>","arguments":{...}}
  Формат2: {"<tool_name>":{...args...}}

Примеры:
  {"name":"web_search","arguments":{"query":"погода в Москве"}}
  {"read_screen":{}}
  {"tap":{"node_id":"btn_42"}}
  {"done":{"summary":"Готово: нашёл 5 результатов"}}

Правила:
1. Если нужен инструмент — ответ ДОЛЖЕН быть только таким JSON-объектом, ничего после и до него не пиши. Результат придёт в следующем ходе как system-сообщение (`tool_result: <name> -> <вывод>`).
2. Один вызов за ход. Не объединяй несколько tools в одном реплае — жди результат первого и решай что дальше.
3. После получения результата зови следующий инструмент так же — одним JSON в следующем ходе. Не пересказывай вывод tool'а пользователю.
4. Когда всё готово и больше вызывать нечего — вызови done: {"done":{"summary":"<короткий итог>"}} (это НЕ завершает агента, только отмечает конец текущей подзадачи). Пользователь продолжит диалог кнопкой «Продолжить» или новым сообщением.
5. Обычный текстовый ответ (простые вопросы, реплики) всё ещё разрешён — если инструмент НЕ нужен, пиши обычный ответ без JSON. Но если задача требует действий (web_search, read_screen, tap, write_file и т.д.) — вызови инструмент через JSON вместо отписок «я не могу» / «у меня нет доступа».
"""
    }
}

sealed class AgentLog {
    data class Thinking(val step: Int) : AgentLog()
    data class Assistant(val text: String) : AgentLog()
    /**
     * One streaming content fragment from the LLM. Emitted as the network response is
     * received chunk-by-chunk; the UI appends [text] to the currently-streaming assistant
     * bubble so the user sees the answer typed out live.
     *
     * Multiple [AssistantDelta]s are followed by a single terminal [Assistant] event with
     * the fully-assembled text, so a downstream collector that only listens to [Assistant]
     * still gets the final message.
     */
    data class AssistantDelta(val text: String) : AgentLog()
    /**
     * One streaming reasoning fragment (chain-of-thought) from the LLM. Sourced from
     * `delta.reasoning_content` / `delta.reasoning` (OpenAI-shape SSE) or from a
     * `<think>…</think>` block extracted client-side. The UI appends [text] to the
     * currently-streaming assistant bubble's reasoning panel.
     */
    data class ReasoningDelta(val text: String) : AgentLog()
    /**
     * Final assembled reasoning text for this turn (terminal event matching [ReasoningDelta]
     * the same way [Assistant] matches [AssistantDelta]). Always followed by either an
     * [Assistant], [Done], or [Error] event for the same turn.
     */
    data class Reasoning(val text: String) : AgentLog()
    data class ToolCall(val name: String, val arguments: String, val summary: String) : AgentLog()
    data class AskUser(val question: String) : AgentLog()
    data class Done(val summary: String, val success: Boolean) : AgentLog()
    data class Error(val message: String) : AgentLog()
    data class System(val message: String) : AgentLog()
}

internal data class ToolResult(
    val toolContent: String,
    val summary: String,
    val newScreenState: ScreenState? = null,
    val done: DoneSignal? = null,
    /** Optional `data:image/...;base64,...` URL. When set AND settings.sendScreenshots is true,
     *  the agent loop appends an extra user message with this image so vision models see it. */
    val imageDataUrl: String? = null,
) {
    companion object {
        fun error(message: String): ToolResult = ToolResult(
            toolContent = "Error: $message",
            summary = "ошибка: $message",
        )
    }
}

internal data class DoneSignal(val summary: String, val success: Boolean)
