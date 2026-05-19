package com.aiagent.android.ui.chat

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiagent.android.agent.Agent
import com.aiagent.android.agent.AgentLog
import com.aiagent.android.data.Settings
import com.aiagent.android.llm.ChatMessage as LlmChatMessage
import com.aiagent.android.llm.LlmClient
import com.aiagent.android.llm.textMessage
import com.aiagent.android.stt.SpeechToText
import com.aiagent.android.overlay.JoystickOverlayService
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.AgentForegroundService
import com.aiagent.android.service.ScreenCaptureService
import com.aiagent.android.service.ScreenRecorderService
import com.aiagent.android.util.AppForegroundTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bridges the original agent's [Agent] runtime, [Settings], permission state and OS services
 * into a chat-shaped UI state for Compose. Replaces the legacy `MainViewModel` (which exposed
 * a tabs + log + form layout) with a transcript that looks like Kiro Mobile Chat.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val storage = ChatStorage(app)

    /** LLM-side conversation history — survives across `runAgent` invocations so the user can
     *  keep talking to the same agent thread the way `MainViewModel` did. Cleared on reset.
     *  Persisted to [ChatStorage] on every [persistMessages] call so the model keeps full
     *  context across app restarts (otherwise the user sees their bubbles but the model has
     *  no idea what was said before, which felt like «the AI answers once and forgets»).
     *
     *  Declared BEFORE [_state] because [loadInitialState] reads it to seed the context-size
     *  approximation. Kotlin runs property initialisers in source order, so reversing this
     *  declaration would NPE on app start (`llmHistory` would still be null when the state
     *  initialiser fires). */
    private val llmHistory: MutableList<LlmChatMessage> =
        storage.loadLlmHistory().toMutableList()

    // Bootstrap the UI state directly at declaration. `_state` MUST be initialised before
    // `foregroundListener` (declared below) so Kotlin's flow analysis can prove the lambda
    // captures a valid reference. The rest of [init] only wires listeners / overlays.
    private val _state: MutableStateFlow<ChatUiState> = MutableStateFlow(loadInitialState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var pendingAnswerChannel: Channel<String>? = null
    private var pendingProjectionChannel: Channel<ProjectionGrant>? = null

    /** True while the agent is suspended waiting for the system MediaProjection consent dialog.
     *  [MainActivity] observes this and pops the dialog automatically when it flips on. */
    private val _projectionRequests = MutableStateFlow(0L)
    val projectionRequests: StateFlow<Long> = _projectionRequests.asStateFlow()

    /**
     * Mirror of [AppForegroundTracker] state. Declared BEFORE [init] so it's already
     * initialised when the constructor registers it as a listener — Kotlin runs property
     * initialisers and init blocks in source order.
     *
     * When the user opens the chat we hide every floating overlay; when they leave back
     * to the launcher with an active run, we re-show STOP / ⚙ / joystick / thoughts
     * (subject to [Settings.deviceControlEnabled] inside the show() functions).
     */
    private val foregroundListener: (Boolean) -> Unit = { foreground ->
        _state.update { it.copy(appForeground = foreground) }
        val ctx = getApplication<Application>()
        if (foreground) {
            // App is on top now — kill every floating overlay so they don't cover the chat.
            OverlayService.hide(ctx)
            OverlayService.hideStop(ctx)
            OverlayService.hideSettings(ctx)
            OverlayService.hideThought(ctx)
            OverlayService.hideDemonstration(ctx)
            JoystickOverlayService.hide(ctx)
        } else {
            // App went to background. Re-arm the overlays the user explicitly enabled. The
            // show() functions internally honour deviceControlEnabled so these are no-ops
            // when the master switch is off.
            if (settings.joystickEnabled) JoystickOverlayService.show(ctx)
            if (settings.settingsOverlayEnabled) OverlayService.showSettings(ctx)
            if (_state.value.isStreaming) OverlayService.showStop(ctx)
        }
    }

    /**
     * Build the initial [ChatUiState] from persisted storage + [Settings] + foreground tracker.
     * Pulled out of `init {}` so it can run during the [_state] property initialiser, which
     * lets us declare [foregroundListener] (capturing `_state`) after `_state`.
     */
    private fun loadInitialState(): ChatUiState {
        val savedMessages = storage.loadMessages()
        val savedModel = storage.loadModel(default = settings.model.ifBlank { DEFAULT_KIRO_MODEL })
        // Seed the context-size approximation from the loaded LLM history so the header
        // pill reads a meaningful number immediately on app start, before the user has
        // sent anything in the new session.
        val seedApprox = llmHistory.sumOf { msg ->
            (msg.contentText?.length ?: 0) / 4 + 4
        }
        val savedUsage = storage.loadUsage().copy(approxContextTokens = seedApprox)
        return ChatUiState(
            messages = savedMessages,
            model = savedModel,
            usage = savedUsage,
            quotaCap = storage.loadQuotaCap(),
            activeProvider = settings.activeProvider,
            provider1Name = settings.provider1Name,
            provider1BaseUrl = settings.provider1BaseUrl,
            provider1ApiKey = settings.provider1ApiKey,
            provider1ExtraApiKeys = settings.provider1ExtraApiKeys,
            provider1Transport = settings.provider1Transport,
            provider2Name = settings.provider2Name,
            provider2BaseUrl = settings.provider2BaseUrl,
            provider2ApiKey = settings.provider2ApiKey,
            provider2ExtraApiKeys = settings.provider2ExtraApiKeys,
            provider2Transport = settings.provider2Transport,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            systemPrompt = settings.systemPrompt,
            sendScreenshots = settings.sendScreenshots,
            autoScreenshotEachTurn = settings.autoScreenshotEachTurn,
            recordUserActions = settings.recordUserActions,
            joystickEnabled = settings.joystickEnabled,
            settingsOverlayEnabled = settings.settingsOverlayEnabled,
            deviceControlEnabled = settings.deviceControlEnabled,
            runInBackground = settings.runInBackground,
            appForeground = AppForegroundTracker.isAppForeground,
            waitForMessages = settings.waitForMessages,
            reasoningModeEnabled = settings.reasoningModeEnabled,
            openAppAfterAnswer = settings.openAppAfterAnswer,
        )
    }

    init {
        // Persist model selection back to legacy Settings the first time so the Agent's
        // OpenAI-compatible request uses the curated model id.
        val initialModel = _state.value.model
        if (settings.model.isBlank()) settings.model = initialModel

        refreshPermissionStatus()

        // Wire any panic / stop hooks from the overlay so they map to our chat methods.
        OverlayService.stopListener = { cancelAgent() }
        OverlayService.panicListener = { panicShutdown() }
        OverlayService.startListener = { transcript ->
            viewModelScope.launch {
                if (_state.value.pendingQuestionMessageId != null) {
                    submitAnswer(transcript)
                } else if (!_state.value.isStreaming) {
                    send(transcript, emptyList())
                } else {
                    Agent.pushInterrupt(transcript)
                }
            }
        }
        // The foreground-service "Стоп" notification action funnels back here, mirroring
        // OverlayService.stopListener so the user can cancel the agent from outside the app.
        AgentForegroundService.stopListener = { cancelAgent() }

        // The overlay companion-object now gates show() on (deviceControl && !foreground),
        // so calling these is safe even when one of the conditions is off — it's just a no-op.
        // We still call them so they pop up the moment the user re-enables device control.
        if (settings.joystickEnabled) JoystickOverlayService.show(app)
        if (settings.settingsOverlayEnabled) OverlayService.showSettings(app)

        // Mirror foreground/background transitions into UI state AND hide overlays the
        // moment the chat opens — matches «убрать кнопки когда ты в приложении».
        AppForegroundTracker.addListener(foregroundListener)
    }

    override fun onCleared() {
        super.onCleared()
        OverlayService.stopListener = null
        OverlayService.startListener = null
        OverlayService.panicListener = null
        AgentForegroundService.stopListener = null
        AppForegroundTracker.removeListener(foregroundListener)
    }

    // -----------------------------------------------------------------------------------------
    // Public API: send / cancel / reset / quotas
    // -----------------------------------------------------------------------------------------

    fun send(text: String, attachments: List<UiAttachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_state.value.isStreaming) return

        // Clear any stale «Ответить сразу» flag from a previous turn so it doesn't
        // accidentally suppress reasoning on the fresh request the user just sent.
        Agent.forceAnswerRequested = false

        refreshServiceStatus()

        val userMessage = ChatMessage(
            id = newId("u"),
            role = ChatRole.USER,
            content = trimmed,
            attachments = attachments,
        )
        val assistantMessage = ChatMessage(
            id = newId("a"),
            role = ChatRole.ASSISTANT,
            content = "",
            pending = true,
            model = _state.value.model,
        )
        _state.update {
            it.copy(
                messages = it.messages + userMessage + assistantMessage,
                isStreaming = true,
            )
        }
        persistMessages()

        OverlayService.agentRunning = true
        OverlayService.showStop(getApplication())

        // Optional foreground service: keeps the OS from killing the agent loop when the
        // user backgrounds the app, and feeds a persistent "Агент работает" notification
        // with a Stop action. We pass the flag to Agent so its system prompt mentions
        // background mode and the controller doesn't hurry its answers.
        val backgroundEnabled = settings.runInBackground
        if (backgroundEnabled) {
            AgentForegroundService.start(
                getApplication(),
                title = "AI Agent работает",
                body = trimmed.take(120).ifBlank { "Обрабатываю запрос\u2026" },
            )
        }

        val agent = Agent(
            getApplication(),
            settings,
            askUser = { question -> waitForUserAnswer(assistantMessage.id, question) },
            startScreenRecording = { startScreenRecording() },
            stopScreenRecording = { stopScreenRecording() },
            ensureCaptureService = { ensureScreenCaptureService() },
            inBackground = backgroundEnabled,
        ) { entry -> handleAgentLog(assistantMessage.id, entry) }

        val instruction = buildInstructionText(trimmed, attachments)
        // Collect image data URLs (base64-encoded by [MessageInput.encodeImageAsDataUri]).
        // These are forwarded to [Agent.run] so a vision-capable controller actually sees the
        // pixels instead of just a `[Прикреплено изображение: ...]` placeholder.
        val attachedImages = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val userImages = attachedImages
            .filter { it.dataUri.startsWith("data:image") || it.dataUri.startsWith("data:") }
            .map { it.dataUri }
        // Surface what we actually sent so the user can tell at a glance whether the
        // multimodal payload reached the model. If a picker returned a URI we couldn't
        // decode, post a visible system note instead of letting the bot claim it sees no
        // image at all.
        if (attachedImages.isNotEmpty()) {
            val sentBytes = userImages.sumOf { dataUri ->
                // Rough size estimate from base64 length: 3 bytes per 4 chars.
                val b64 = dataUri.substringAfter("base64,", "")
                (b64.length * 3L / 4L)
            }
            val note = if (userImages.size == attachedImages.size) {
                "Отправил ${userImages.size} изображени${pluralEndingRu(userImages.size)} модели " +
                    "(${sentBytes / 1024} KB base64). Если модель пишет «не вижу картинку» — " +
                    "это уже галлюцинация на её стороне, а не пропавший аттач."
            } else {
                val dropped = attachedImages.size - userImages.size
                "Не удалось закодировать $dropped из ${attachedImages.size} картинок — отправляю " +
                    "только ${userImages.size}. Проверь логи (тег `MessageInput`) или попробуй " +
                    "другое изображение."
            }
            updateAssistant(assistantMessage.id) { msg ->
                msg.copy(
                    toolEvents = msg.toolEvents + ToolEvent(
                        id = newId("t"),
                        name = "attachments",
                        arguments = "",
                        summary = note,
                    ),
                )
            }
        }

        currentJob = viewModelScope.launch {
            var runError: Throwable? = null
            try {
                agent.run(instruction, llmHistory, userImages)
            } catch (t: Throwable) {
                runError = t
                // Translate any unexpected runtime error into an inline assistant error so
                // the user sees it in the bubble instead of just a logcat trace.
                updateAssistant(assistantMessage.id) { msg ->
                    msg.copy(
                        pending = false,
                        error = t.message ?: t::class.java.simpleName,
                    )
                }
            } finally {
                _state.update { it.copy(isStreaming = false, pendingQuestionMessageId = null) }
                pendingAnswerChannel?.close()
                pendingAnswerChannel = null
                OverlayService.agentRunning = false
                OverlayService.hideStop(getApplication())
                // Final "pending=false" sweep in case the assistant never emitted a textual chunk
                // (e.g. it only called tools and then `done`).
                updateAssistant(assistantMessage.id) { msg ->
                    if (msg.pending) msg.copy(pending = false) else msg
                }
                persistMessages()
                // Tear down the foreground service if we started it. Post the completion
                // notification only when the user can't already see the result (app is
                // backgrounded — otherwise the chat bubble itself is enough).
                if (backgroundEnabled) {
                    val ctx = getApplication<Application>()
                    AgentForegroundService.finish(ctx)
                    if (!AppForegroundTracker.isAppForeground) {
                        val finalMsg = _state.value.messages.firstOrNull { it.id == assistantMessage.id }
                        val summary = when {
                            runError != null -> "Ошибка: ${runError?.message ?: runError?.javaClass?.simpleName}"
                            finalMsg?.error != null -> "Ошибка: ${finalMsg.error}"
                            !finalMsg?.doneSummary.isNullOrBlank() -> finalMsg!!.doneSummary!!
                            !finalMsg?.content.isNullOrBlank() -> finalMsg!!.content.take(200)
                            else -> "Готово"
                        }
                        AgentForegroundService.notifyDone(ctx, summary, success = runError == null)
                    }
                }
                // After EVERY successful turn (whether or not it ended with an explicit
                // `done`), try to bring the app to the foreground if the user opted in.
                // Handles the chatbot-mode case where the agent returns a plain text reply
                // and exits without emitting AgentLog.Done.
                if (runError == null) {
                    maybeOpenAppAfterAnswer()
                }
            }
        }
    }

    fun cancelAgent() {
        currentJob?.cancel()
        currentJob = null
        pendingAnswerChannel?.close()
        pendingAnswerChannel = null
        pendingProjectionChannel?.close()
        pendingProjectionChannel = null
        _state.update {
            it.copy(
                isStreaming = false,
                pendingQuestionMessageId = null,
                messages = it.messages.map { m -> if (m.pending) m.copy(pending = false) else m },
            )
        }
        OverlayService.agentRunning = false
        OverlayService.hideStop(getApplication())
        // Drop the persistent "Агент работает" notification — the user stopped us explicitly
        // so there's no point posting a completion banner. The foreground job's finally{}
        // block also calls finish() but cancellation might short-circuit it on some ROMs.
        AgentForegroundService.finish(getApplication())
        persistMessages()
    }

    fun clearChat() {
        cancelAgent()
        llmHistory.clear()
        storage.clearAll()
        _state.update {
            it.copy(
                messages = emptyList(),
                usage = UsageStats(),
                pendingQuestionMessageId = null,
                isStreaming = false,
            )
        }
    }

    /** "Дед инсайд" full shutdown — mirrors what the legacy MainViewModel did. */
    fun panicShutdown() {
        cancelAgent()
        Agent.shutdown()
        val ctx = getApplication<Application>()
        OverlayService.hide(ctx); OverlayService.hideStop(ctx); OverlayService.hideSettings(ctx)
        OverlayService.hideThought(ctx); OverlayService.hideDemonstration(ctx)
        JoystickOverlayService.hide(ctx)
        runCatching {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            })
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
        runCatching {
            ctx.stopService(Intent(ctx, ScreenRecorderService::class.java).apply {
                action = ScreenRecorderService.ACTION_STOP
            })
            ctx.stopService(Intent(ctx, ScreenRecorderService::class.java))
        }
        com.aiagent.android.agent.UserActionLog.clear()
        settings.recordUserActions = false
        settings.joystickEnabled = false
        settings.settingsOverlayEnabled = false
        settings.autoScreenshotEachTurn = false
        llmHistory.clear()
        _state.update {
            it.copy(
                isStreaming = false,
                pendingQuestionMessageId = null,
                recordUserActions = false,
                joystickEnabled = false,
                settingsOverlayEnabled = false,
                autoScreenshotEachTurn = false,
            )
        }
    }

    fun submitAnswer(text: String) {
        val ch = pendingAnswerChannel ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { ch.send(trimmed) }
    }

    fun setQuotaCap(value: Int) {
        val safe = value.coerceAtLeast(0)
        storage.saveQuotaCap(safe)
        _state.update { it.copy(quotaCap = safe) }
    }

    // -----------------------------------------------------------------------------------------
    // Model + settings updates (UI ↔ legacy Settings bridge)
    // -----------------------------------------------------------------------------------------

    /**
     * Fetches the model list from BOTH configured providers in parallel. Returns one
     * [ProviderModels] entry per provider — each carries either a success list or the
     * failure that caused it (which the picker renders as an inline error). A provider
     * with a blank Base URL is reported as a soft failure ("Base URL пустой") rather
     * than being silently skipped, so the user sees that nothing was queried for it.
     */
    suspend fun loadModels(): List<ProviderModels> = coroutineScope {
        val s = _state.value
        val slots = listOf(
            com.aiagent.android.llm.ProviderSlot(
                1, s.provider1Name, s.provider1BaseUrl, s.provider1ApiKey, s.provider1Transport,
            ),
            com.aiagent.android.llm.ProviderSlot(
                2, s.provider2Name, s.provider2BaseUrl, s.provider2ApiKey, s.provider2Transport,
            ),
        )
        slots.map { slot ->
            async(Dispatchers.IO) { fetchProviderModels(slot) }
        }.map { it.await() }
    }

    private suspend fun fetchProviderModels(
        slot: com.aiagent.android.llm.ProviderSlot,
    ): ProviderModels {
        // Empty Base URL: for Kiro that's fine — the transport falls back to the AWS default.
        // For an OpenAI-shaped slot it's a hard failure because we literally have nowhere to call.
        if (slot.baseUrl.isBlank() && slot.transport != Settings.TRANSPORT_KIRO) {
            // Even though we can't reach a remote /models, we still surface the backend's
            // hard-coded fallback list (if any) so the user can pick something without
            // having to first type a URL just to discover model IDs.
            val fallback = runCatching {
                val tmp = com.aiagent.android.llm.ChatBackends.forSlot(slot)
                try {
                    tmp.fallbackModels()
                } finally {
                    tmp.close()
                }
            }.getOrDefault(emptyList())
            return if (fallback.isNotEmpty()) {
                ProviderModels(
                    slot = slot.index,
                    name = slot.name,
                    result = Result.success(fallback),
                    warning = "Base URL пустой — показан встроенный список (заполни URL чтобы увидеть актуальные модели провайдера).",
                )
            } else {
                ProviderModels(
                    slot = slot.index,
                    name = slot.name,
                    result = Result.failure(IllegalStateException("Base URL пустой")),
                )
            }
        }
        val client = com.aiagent.android.llm.ChatBackends.forSlot(slot)
        return try {
            val models = client.listModels()
            ProviderModels(
                slot = slot.index,
                name = slot.name,
                result = Result.success(models),
            )
        } catch (t: Throwable) {
            // Network is down, DNS dead, endpoint 403/500 — whatever the reason, prefer
            // showing the backend's known-good list so the picker stays usable offline.
            // The original failure is surfaced as a warning so the user still understands
            // why it isn't the live catalogue.
            val fallback = runCatching { client.fallbackModels() }.getOrDefault(emptyList())
            if (fallback.isNotEmpty()) {
                ProviderModels(
                    slot = slot.index,
                    name = slot.name,
                    result = Result.success(fallback),
                    warning = "Не удалось получить актуальный список (" +
                        (t.message ?: t::class.java.simpleName) +
                        "). Показан встроенный список известных моделей — можно работать оффлайн.",
                )
            } else {
                ProviderModels(
                    slot = slot.index,
                    name = slot.name,
                    result = Result.failure(t),
                )
            }
        } finally {
            client.close()
        }
    }

    /** Picks a model and atomically flips the active provider so subsequent
     *  chat / STT calls go to the right endpoint. */
    fun selectModel(slot: Int, id: String) {
        setActiveProvider(slot)
        setModel(id)
    }

    fun setModel(id: String) {
        settings.model = id
        storage.saveModel(id)
        _state.update { it.copy(model = id, modelPickerOpen = false) }
    }

    fun setModelPickerOpen(open: Boolean) { _state.update { it.copy(modelPickerOpen = open) } }
    fun setQuotaPanelOpen(open: Boolean) { _state.update { it.copy(quotaPanelOpen = open) } }
    fun setSettingsOpen(open: Boolean) { _state.update { it.copy(settingsOpen = open) } }

    fun updateProvider1Name(v: String) {
        settings.provider1Name = v
        _state.update { it.copy(provider1Name = v) }
    }
    fun updateProvider1BaseUrl(v: String) {
        settings.provider1BaseUrl = v
        _state.update { it.copy(provider1BaseUrl = v) }
    }
    fun updateProvider1ApiKey(v: String) {
        settings.provider1ApiKey = v
        _state.update { it.copy(provider1ApiKey = v) }
    }
    fun updateProvider1ExtraApiKeys(v: String) {
        settings.provider1ExtraApiKeys = v
        _state.update { it.copy(provider1ExtraApiKeys = v) }
    }
    fun updateProvider1Transport(v: String) {
        settings.provider1Transport = v
        _state.update { it.copy(provider1Transport = v) }
    }
    fun updateProvider2Name(v: String) {
        settings.provider2Name = v
        _state.update { it.copy(provider2Name = v) }
    }
    fun updateProvider2BaseUrl(v: String) {
        settings.provider2BaseUrl = v
        _state.update { it.copy(provider2BaseUrl = v) }
    }
    fun updateProvider2ApiKey(v: String) {
        settings.provider2ApiKey = v
        _state.update { it.copy(provider2ApiKey = v) }
    }
    fun updateProvider2ExtraApiKeys(v: String) {
        settings.provider2ExtraApiKeys = v
        _state.update { it.copy(provider2ExtraApiKeys = v) }
    }
    fun updateProvider2Transport(v: String) {
        settings.provider2Transport = v
        _state.update { it.copy(provider2Transport = v) }
    }
    fun setActiveProvider(slot: Int) {
        val clamped = slot.coerceIn(1, 2)
        settings.activeProvider = clamped
        _state.update { it.copy(activeProvider = clamped) }
    }
    fun updateTemperature(v: Float) { settings.temperature = v; _state.update { it.copy(temperature = v) } }
    fun updateMaxTokens(v: Int) { settings.maxTokens = v; _state.update { it.copy(maxTokens = v) } }
    fun updateSystemPrompt(v: String) { settings.systemPrompt = v; _state.update { it.copy(systemPrompt = v) } }
    fun updateSendScreenshots(v: Boolean) {
        settings.sendScreenshots = v; _state.update { it.copy(sendScreenshots = v) }
    }
    fun updateAutoScreenshot(v: Boolean) {
        settings.autoScreenshotEachTurn = v; _state.update { it.copy(autoScreenshotEachTurn = v) }
    }
    fun updateRecordUserActions(v: Boolean) {
        settings.recordUserActions = v; _state.update { it.copy(recordUserActions = v) }
    }
    fun updateJoystickEnabled(v: Boolean) {
        settings.joystickEnabled = v
        _state.update { it.copy(joystickEnabled = v) }
        val ctx = getApplication<Application>()
        if (v) JoystickOverlayService.show(ctx) else JoystickOverlayService.hide(ctx)
    }
    fun updateSettingsOverlayEnabled(v: Boolean) {
        settings.settingsOverlayEnabled = v
        _state.update { it.copy(settingsOverlayEnabled = v) }
        val ctx = getApplication<Application>()
        if (v) OverlayService.showSettings(ctx) else OverlayService.hideSettings(ctx)
    }

    /**
     * "Управлять телефоном" master switch. Persists, rebuilds the system prompt on the
     * next run, and immediately collapses every floating overlay when the user turns it
     * off — the show()-side gate handles the opposite direction.
     */
    fun updateDeviceControlEnabled(v: Boolean) {
        settings.deviceControlEnabled = v
        _state.update { it.copy(deviceControlEnabled = v) }
        val ctx = getApplication<Application>()
        if (!v) {
            OverlayService.hide(ctx)
            OverlayService.hideStop(ctx)
            OverlayService.hideSettings(ctx)
            OverlayService.hideThought(ctx)
            OverlayService.hideDemonstration(ctx)
            JoystickOverlayService.hide(ctx)
        } else if (!AppForegroundTracker.isAppForeground) {
            // User just opted in while the chat is in the background — re-arm the
            // overlays they had enabled.
            if (settings.joystickEnabled) JoystickOverlayService.show(ctx)
            if (settings.settingsOverlayEnabled) OverlayService.showSettings(ctx)
            if (_state.value.isStreaming) OverlayService.showStop(ctx)
        }
    }

    /** «Работать в фоне» toggle (foreground service + completion notification). */
    fun updateRunInBackground(v: Boolean) {
        settings.runInBackground = v
        _state.update { it.copy(runInBackground = v) }
    }

    /**
     * Master switch for the reasoning panel. When flipped off mid-stream we ALSO clear
     * any in-flight reasoning buffers and drop the panel from the currently-streaming
     * assistant bubble — the user's intent is "don't show me reasoning", not "start
     * hiding it from the next turn".
     */
    fun updateReasoningMode(v: Boolean) {
        settings.reasoningModeEnabled = v
        _state.update { state ->
            state.copy(
                reasoningModeEnabled = v,
                messages = if (v) state.messages else state.messages.map { m ->
                    if (m.reasoning.isEmpty() && !m.reasoningPending) m
                    else m.copy(reasoning = "", reasoningPending = false)
                },
            )
        }
        if (!v) {
            reasoningBuffers.clear()
        }
        persistMessages()
    }

    /** «Открывать приложение после ответа» toggle. */
    fun updateOpenAppAfterAnswer(v: Boolean) {
        settings.openAppAfterAnswer = v
        _state.update { it.copy(openAppAfterAnswer = v) }
    }

    /**
     * Signal that the user tapped the «Ответить сразу» button next to the reasoning
     * panel. Tells the agent to stop forwarding further reasoning fragments for the
     * current turn AND to drop `reasoning_effort` from subsequent retries. Idempotent.
     */
    fun forceAnswerNow() {
        Agent.forceAnswerRequested = true
        // Visually freeze the reasoning panel so the user knows their tap was registered
        // even before the next reasoning chunk arrives (the model may briefly keep
        // streaming on the wire while we wait).
        _state.update { state ->
            state.copy(
                messages = state.messages.map { m ->
                    if (m.reasoningPending) m.copy(reasoningPending = false) else m
                },
            )
        }
    }

    /**
     * «Сам завершаться после ответа» toggle. Stored as the inverse of [Settings.waitForMessages]
     * so the persisted semantics («wait for next message») don't flip: OFF here means the
     * agent will [Agent.AgentLog.Done] and exit the loop the moment it sends `done`.
     */
    fun updateWaitForMessages(v: Boolean) {
        settings.waitForMessages = v
        _state.update { it.copy(waitForMessages = v) }
    }

    // -----------------------------------------------------------------------------------------
    // Permission status
    // -----------------------------------------------------------------------------------------

    fun refreshServiceStatus() {
        _state.update { it.copy(accessibilityEnabled = AgentAccessibilityService.isRunning()) }
        refreshPermissionStatus()
    }

    fun refreshPermissionStatus() {
        val app = getApplication<Application>()
        val overlay = AndroidSettings.canDrawOverlays(app)
        val manageStorage =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
        val mic = ContextCompat.checkSelfPermission(
            app, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        _state.update {
            it.copy(
                overlayGranted = overlay,
                manageStorageGranted = manageStorage,
                micGranted = mic,
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Activity-result callbacks (forwarded from MainActivity)
    // -----------------------------------------------------------------------------------------

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val ch = pendingProjectionChannel ?: return
        viewModelScope.launch { ch.send(ProjectionGrant(resultCode, data)) }
    }

    fun onFolderPicked(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val newSet = settings.allowedFolders + uri.toString()
        settings.allowedFolders = newSet
    }

    fun isProjectionPending(): Boolean = pendingProjectionChannel != null

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private fun buildInstructionText(text: String, attachments: List<UiAttachment>): String {
        // Image attachments are forwarded as real multimodal parts via [Agent.run], so we
        // don't have to mention them in the text at all — vision-capable models will see
        // the pixels directly. Non-image attachments still need a one-line note so the
        // agent knows they exist (it can read them via the file tools).
        val notes = attachments
            .filter { it.kind != AttachmentKind.IMAGE }
            .joinToString("\n") {
                "[Прикреплён файл: ${it.name} (${it.mimeType}, ${(it.sizeBytes / 1024.0).toInt()} KB)]"
            }
        return listOf(text, notes).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * Per-assistant-message streaming buffer. While the LLM is still emitting chunks the
     * bubble's `content` is rebuilt as `committed + buffer` on every delta so the user sees
     * a live "typing" effect. When the agent loop emits the terminal [AgentLog.Assistant]
     * with the same text we just promote the buffer to `committed` instead of appending
     * again (otherwise we'd duplicate the answer).
     */
    private val streamBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
    /** Content already finalised for the bubble (everything before the current stream). */
    private val committedContent: MutableMap<String, String> = mutableMapOf()
    /** Streaming reasoning buffer per assistant message. Mirrors [streamBuffers] but for
     *  the chain-of-thought panel — each [AgentLog.ReasoningDelta] appends to the same
     *  buffer and we rebuild the bubble's `reasoning` field on every chunk. Cleared when
     *  the terminal [AgentLog.Reasoning] / [AgentLog.Done] arrives. */
    private val reasoningBuffers: MutableMap<String, StringBuilder> = mutableMapOf()

    private fun handleAgentLog(assistantId: String, entry: AgentLog) {
        when (entry) {
            is AgentLog.Thinking -> {
                // Render as a typing/spinner state — we keep the assistant message in `pending`
                // mode and append a system tool event so the user can see step progression.
                // A new step also resets the stream buffer so the next AssistantDelta starts
                // fresh below whatever text we've already shown for prior steps.
                streamBuffers.remove(assistantId)
                committedContent[assistantId] = _state.value.messages
                    .firstOrNull { it.id == assistantId }?.content.orEmpty()
                updateAssistant(assistantId) { msg ->
                    msg.copy(
                        pending = true,
                        toolEvents = msg.toolEvents + ToolEvent(
                            id = newId("t"),
                            name = "thinking",
                            arguments = "",
                            summary = "Думаю — шаг ${entry.step}",
                        ),
                    )
                }
            }
            is AgentLog.AssistantDelta -> {
                // Live streaming: append this chunk to the per-message buffer and rewrite the
                // bubble's content as `<committed prior steps> + <streaming buffer>`. Keep
                // `pending=true` so the typing indicator stays visible — the terminal
                // [AgentLog.Assistant] flips it off.
                val buf = streamBuffers.getOrPut(assistantId) { StringBuilder() }
                buf.append(entry.text)
                val live = buf.toString()
                val prior = committedContent[assistantId].orEmpty()
                val merged = if (prior.isBlank()) live else prior + "\n\n" + live
                updateAssistant(assistantId) { msg -> msg.copy(content = merged, pending = true) }
            }
            is AgentLog.ReasoningDelta -> {
                // Stream chain-of-thought into the reasoning panel of the currently-streaming
                // assistant bubble. Mirrors [AssistantDelta] handling but writes to a separate
                // field so the UI can show reasoning above the answer with its own «Ответить
                // сразу» button.
                val buf = reasoningBuffers.getOrPut(assistantId) { StringBuilder() }
                buf.append(entry.text)
                val live = buf.toString()
                updateAssistant(assistantId) { msg ->
                    msg.copy(reasoning = live, reasoningPending = true)
                }
            }
            is AgentLog.Reasoning -> {
                // Terminal reasoning event — promote the buffer to the final field and clear
                // the pending flag so the panel stops showing the typing indicator.
                val streamed = reasoningBuffers[assistantId]?.toString().orEmpty()
                val finalReasoning = entry.text.ifEmpty { streamed }
                reasoningBuffers.remove(assistantId)
                updateAssistant(assistantId) { msg ->
                    msg.copy(reasoning = finalReasoning, reasoningPending = false)
                }
                persistMessages()
            }
            is AgentLog.Assistant -> {
                // Terminal event after all deltas (or the only event for non-streaming
                // backends). If the streamed buffer already contains the full text, just flip
                // off the pending flag and promote the buffer to `committed`; otherwise append
                // as before so non-streaming providers (default chatStream) still render.
                val streamed = streamBuffers[assistantId]?.toString().orEmpty()
                val prior = committedContent[assistantId].orEmpty()
                val final = when {
                    streamed.isNotBlank() && streamed == entry.text -> {
                        if (prior.isBlank()) entry.text else prior + "\n\n" + entry.text
                    }
                    prior.isBlank() -> entry.text
                    else -> prior + "\n\n" + entry.text
                }
                committedContent[assistantId] = final
                streamBuffers.remove(assistantId)
                updateAssistant(assistantId) { msg -> msg.copy(content = final, pending = false) }
                persistMessages()
            }
            is AgentLog.ToolCall -> {
                updateAssistant(assistantId) { msg ->
                    msg.copy(
                        toolEvents = msg.toolEvents + ToolEvent(
                            id = newId("t"),
                            name = entry.name,
                            arguments = entry.arguments,
                            summary = entry.summary,
                        ),
                    )
                }
            }
            is AgentLog.AskUser -> {
                updateAssistant(assistantId) { msg ->
                    msg.copy(pendingQuestion = entry.question, pending = false)
                }
                _state.update { it.copy(pendingQuestionMessageId = assistantId) }
            }
            is AgentLog.Done -> {
                streamBuffers.remove(assistantId)
                committedContent.remove(assistantId)
                reasoningBuffers.remove(assistantId)
                updateAssistant(assistantId) { msg ->
                    msg.copy(
                        doneSummary = entry.summary,
                        pending = false,
                        reasoningPending = false,
                    )
                }
                _state.update {
                    it.copy(usage = it.usage.copy(messages = it.usage.messages + 1))
                }
                persistMessages()
                maybeOpenAppAfterAnswer()
            }
            is AgentLog.Error -> {
                reasoningBuffers.remove(assistantId)
                updateAssistant(assistantId) { msg ->
                    msg.copy(error = entry.message, pending = false, reasoningPending = false)
                }
                persistMessages()
            }
            is AgentLog.System -> {
                updateAssistant(assistantId) { msg ->
                    msg.copy(
                        toolEvents = msg.toolEvents + ToolEvent(
                            id = newId("t"),
                            name = "system",
                            arguments = "",
                            summary = entry.message,
                        ),
                    )
                }
            }
        }
    }

    private fun updateAssistant(id: String, transform: (ChatMessage) -> ChatMessage) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { if (it.id == id) transform(it) else it },
            )
        }
    }

    private fun persistMessages() {
        // Recompute approximate context size every time history changes. Counting chars in
        // llmHistory and dividing by 4 is a rough but useful estimate that lets the user
        // see the conversation context grow in the header pill — previously this number
        // was always 0 because nothing wrote into `usage.totalTokens` for Kiro replies.
        val approxTokens = llmHistory.sumOf { msg ->
            // 4 bytes/token rule-of-thumb + a 4-token overhead for role markers.
            (msg.contentText?.length ?: 0) / 4 + 4
        }
        if (_state.value.usage.approxContextTokens != approxTokens) {
            _state.update { it.copy(usage = it.usage.copy(approxContextTokens = approxTokens)) }
        }
        storage.saveMessages(_state.value.messages)
        storage.saveUsage(_state.value.usage)
        // Persist the LLM-side conversation too so the model remembers context across
        // app restarts. Without this the visible chat bubbles come back but the model
        // sees an empty history — the user perceives that as «the AI answers once and
        // forgets everything I said before». Wrap in runCatching so a SharedPreferences
        // hiccup never aborts a live agent turn.
        runCatching { storage.saveLlmHistory(llmHistory) }
    }

    /**
     * Bring the app to the foreground via a fresh [com.aiagent.android.ui.MainActivity]
     * task launch when (a) the user opted into «Открывать приложение после ответа» AND
     * (b) the app is currently in the background. Called from the [AgentLog.Done]
     * handler so it fires the moment the agent finalises its answer.
     *
     * On Android 10+ background-launches generally require an open notification, a
     * companion-app pair, or POST_NOTIFICATIONS permission. Since this app already runs
     * a foreground service while the agent is active, the OS grants us the launch path.
     * If the device denies it (rare), the user still sees the completion notification.
     */
    private fun maybeOpenAppAfterAnswer() {
        if (!settings.openAppAfterAnswer) return
        if (AppForegroundTracker.isAppForeground) return
        val app = getApplication<Application>()
        val intent = Intent(app, com.aiagent.android.ui.MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        runCatching { app.startActivity(intent) }
    }

    private suspend fun waitForUserAnswer(assistantId: String, question: String): String {
        val ch = Channel<String>(capacity = 1)
        pendingAnswerChannel = ch
        updateAssistant(assistantId) { it.copy(pendingQuestion = question, pending = false) }
        _state.update { it.copy(pendingQuestionMessageId = assistantId) }
        return try {
            ch.receive()
        } catch (_: Throwable) {
            "(пользователь отменил)"
        } finally {
            pendingAnswerChannel = null
            updateAssistant(assistantId) { it.copy(pendingQuestion = null) }
            _state.update { it.copy(pendingQuestionMessageId = null) }
        }
    }

    private suspend fun startScreenRecording(): String {
        if (ScreenRecorderService.isRecording) return "уже идёт запись"
        val ch = Channel<ProjectionGrant>(capacity = 1)
        pendingProjectionChannel = ch
        _projectionRequests.update { it + 1 }
        val grant = try {
            ch.receive()
        } catch (_: Throwable) {
            return "запись отменена"
        } finally {
            pendingProjectionChannel = null
        }
        if (grant.resultCode == 0 || grant.data == null) return "пользователь отказал в записи"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenRecorderService.EXTRA_DATA, grant.data)
        }
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        delay(400)
        return ScreenRecorderService.lastError?.let { "ошибка записи: $it" } ?: "запись начата"
    }

    private suspend fun ensureScreenCaptureService(): Boolean {
        if (ScreenCaptureService.isRunning) return true
        val ch = Channel<ProjectionGrant>(capacity = 1)
        pendingProjectionChannel = ch
        _projectionRequests.update { it + 1 }
        val grant = try {
            ch.receive()
        } catch (_: Throwable) {
            return false
        } finally {
            pendingProjectionChannel = null
        }
        if (grant.resultCode == 0 || grant.data == null) return false
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, grant.resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, grant.data)
        }
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        delay(700)
        return ScreenCaptureService.isRunning
    }

    // ---------------------------------------------------------------------------------------
    // Voice dictation — the mic button on [MessageInput]. Uses the on-device Android
    // SpeechRecognizer by default; falls back to an OpenAI-compatible `/audio/transcriptions`
    // endpoint when [Settings.sttProvider] is set to a remote provider.
    // ---------------------------------------------------------------------------------------

    private val stt: SpeechToText by lazy { SpeechToText(getApplication(), settings) }
    private val _dictationListening = MutableStateFlow(false)
    val dictationListening: StateFlow<Boolean> = _dictationListening.asStateFlow()
    private val _dictationResults: Channel<String> = Channel(capacity = 16)
    val dictationResults: Flow<String> = _dictationResults.receiveAsFlow()
    private var dictationJob: Job? = null

    /**
     * Toggle voice dictation. The first call starts the recogniser; tapping again while it
     * is listening cancels it. Recognised text is emitted on [dictationResults] which the
     * UI collects and inserts into the text field — the user can then edit or send it.
     */
    fun toggleDictation() {
        val current = dictationJob
        if (current != null && current.isActive) {
            current.cancel()
            dictationJob = null
            _dictationListening.update { false }
            return
        }
        if (!hasMicPermission()) {
            _dictationResults.trySend("")
            updateLastUserVisibleSystemNote(
                "Нужно разрешить доступ к микрофону: Настройки → Микрофон.",
            )
            return
        }
        _dictationListening.update { true }
        dictationJob = viewModelScope.launch {
            val result = try {
                stt.listenLive()
            } catch (_: kotlinx.coroutines.CancellationException) {
                ""
            } catch (t: Throwable) {
                ""
            } finally {
                _dictationListening.update { false }
            }
            val clean = result.trim()
            // SpeechToText reports failures as `[ошибка …]` strings. Don't paste those into the
            // text field — surface them as a tool note instead so the user understands why
            // dictation didn't produce anything.
            if (clean.startsWith("[") && clean.endsWith("]")) {
                updateLastUserVisibleSystemNote("Голосовой ввод: $clean")
            } else if (clean.isNotEmpty()) {
                _dictationResults.trySend(clean)
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /** Best-effort: attach a system-level note to the most recent assistant bubble so the user
     *  sees why their action didn't produce a result. If there's no assistant bubble we just
     *  drop the note (rare — [updateAssistant] is a no-op for unknown ids). */
    private fun updateLastUserVisibleSystemNote(message: String) {
        val lastAssistantId = _state.value.messages
            .lastOrNull { it.role == ChatRole.ASSISTANT }?.id
            ?: return
        updateAssistant(lastAssistantId) { msg ->
            msg.copy(
                toolEvents = msg.toolEvents + ToolEvent(
                    id = newId("t"),
                    name = "voice",
                    arguments = "",
                    summary = message,
                ),
            )
        }
    }

    private fun stopScreenRecording(): String {
        if (!ScreenRecorderService.isRecording) return "запись не велась"
        val app = getApplication<Application>()
        val intent = Intent(app, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_STOP
        }
        app.startService(intent)
        val file = ScreenRecorderService.lastFile ?: ""
        return "запись остановлена, файл: $file"
    }

    companion object {
        private fun newId(prefix: String): String =
            "$prefix-${System.currentTimeMillis()}-${(Math.random() * 1_000_000).toInt()}"

        /** Russian plural ending for "изображени-" — singular `е`, 2..4 `я`, 5..20 `й`. */
        private fun pluralEndingRu(n: Int): String {
            val abs = kotlin.math.abs(n) % 100
            if (abs in 11..14) return "й"
            return when (abs % 10) {
                1 -> "е"
                2, 3, 4 -> "я"
                else -> "й"
            }
        }
    }
}

/** Helper holding the result of the system MediaProjection consent dialog. */
data class ProjectionGrant(val resultCode: Int, val data: Intent?)

/**
 * Unused import-guard: textMessage is referenced in case the future ChatViewModel needs to
 * seed [llmHistory] with a system prompt before [Agent.run] is called. Keeping the import
 * documents the intent and prevents accidental removal.
 */
@Suppress("unused")
private val SEED_PROMPT_SUPPORT = ::textMessage
