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
import com.aiagent.android.overlay.JoystickOverlayService
import com.aiagent.android.overlay.OverlayService
import com.aiagent.android.service.AgentAccessibilityService
import com.aiagent.android.service.ScreenCaptureService
import com.aiagent.android.service.ScreenRecorderService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _state: MutableStateFlow<ChatUiState>
    val state: StateFlow<ChatUiState>

    /** LLM-side conversation history — survives across `runAgent` invocations so the user can
     *  keep talking to the same agent thread the way `MainViewModel` did. Cleared on reset. */
    private val llmHistory: MutableList<LlmChatMessage> = mutableListOf()

    private var currentJob: Job? = null
    private var pendingAnswerChannel: Channel<String>? = null
    private var pendingProjectionChannel: Channel<ProjectionGrant>? = null

    /** True while the agent is suspended waiting for the system MediaProjection consent dialog.
     *  [MainActivity] observes this and pops the dialog automatically when it flips on. */
    private val _projectionRequests = MutableStateFlow(0L)
    val projectionRequests: StateFlow<Long> = _projectionRequests.asStateFlow()

    init {
        // Bootstrap state from persistent storage + Settings + permissions.
        val savedMessages = storage.loadMessages()
        val savedModel = storage.loadModel(default = settings.model.ifBlank { DEFAULT_KIRO_MODEL })
        val savedUsage = storage.loadUsage()
        _state = MutableStateFlow(
            ChatUiState(
                messages = savedMessages,
                model = savedModel,
                usage = savedUsage,
                quotaCap = storage.loadQuotaCap(),
                apiKey = settings.apiKey,
                baseUrl = settings.baseUrl,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                systemPrompt = settings.systemPrompt,
                sendScreenshots = settings.sendScreenshots,
                autoScreenshotEachTurn = settings.autoScreenshotEachTurn,
                recordUserActions = settings.recordUserActions,
                joystickEnabled = settings.joystickEnabled,
                settingsOverlayEnabled = settings.settingsOverlayEnabled,
            ),
        )
        state = _state.asStateFlow()

        // Persist model selection back to legacy Settings the first time so the Agent's
        // OpenAI-compatible request uses the curated model id.
        if (settings.model.isBlank()) settings.model = savedModel

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

        if (settings.joystickEnabled) JoystickOverlayService.show(app)
        if (settings.settingsOverlayEnabled) OverlayService.showSettings(app)
    }

    override fun onCleared() {
        super.onCleared()
        OverlayService.stopListener = null
        OverlayService.startListener = null
        OverlayService.panicListener = null
    }

    // -----------------------------------------------------------------------------------------
    // Public API: send / cancel / reset / quotas
    // -----------------------------------------------------------------------------------------

    fun send(text: String, attachments: List<UiAttachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (_state.value.isStreaming) return

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

        val agent = Agent(
            getApplication(),
            settings,
            askUser = { question -> waitForUserAnswer(assistantMessage.id, question) },
            startScreenRecording = { startScreenRecording() },
            stopScreenRecording = { stopScreenRecording() },
            ensureCaptureService = { ensureScreenCaptureService() },
        ) { entry -> handleAgentLog(assistantMessage.id, entry) }

        val instruction = buildInstructionText(trimmed, attachments)

        currentJob = viewModelScope.launch {
            try {
                agent.run(instruction, llmHistory)
            } catch (t: Throwable) {
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
     * Fetches the model list from the configured OpenAI-compatible endpoint.
     * Surfaces network/auth failures back to the picker as a `Result.failure`
     * so the UI can render a non-fatal error card and let the user retry.
     */
    suspend fun loadModels(): Result<List<String>> {
        val baseUrl = settings.baseUrl.trim()
        if (baseUrl.isBlank()) {
            return Result.failure(IllegalStateException("Base URL пустой — вставь его в настройках."))
        }
        val client = LlmClient(baseUrl = baseUrl, apiKey = settings.apiKey)
        return try {
            Result.success(client.listModels())
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            client.close()
        }
    }

    fun setModel(id: String) {
        settings.model = id
        storage.saveModel(id)
        _state.update { it.copy(model = id, modelPickerOpen = false) }
    }

    fun setModelPickerOpen(open: Boolean) { _state.update { it.copy(modelPickerOpen = open) } }
    fun setQuotaPanelOpen(open: Boolean) { _state.update { it.copy(quotaPanelOpen = open) } }
    fun setSettingsOpen(open: Boolean) { _state.update { it.copy(settingsOpen = open) } }

    fun updateApiKey(v: String) { settings.apiKey = v; _state.update { it.copy(apiKey = v) } }
    fun updateBaseUrl(v: String) { settings.baseUrl = v; _state.update { it.copy(baseUrl = v) } }
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
        // Multimodal images are injected by the Agent loop when settings.sendScreenshots is on;
        // non-image attachments become a one-line note so the agent sees they exist.
        val notes = attachments
            .filter { it.kind != AttachmentKind.IMAGE }
            .joinToString("\n") {
                "[Прикреплён файл: ${it.name} (${it.mimeType}, ${(it.sizeBytes / 1024.0).toInt()} KB)]"
            }
        val imageNotes = attachments
            .filter { it.kind == AttachmentKind.IMAGE }
            .joinToString("\n") { "[Прикреплено изображение: ${it.name}]" }
        return listOf(text, imageNotes, notes).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun handleAgentLog(assistantId: String, entry: AgentLog) {
        when (entry) {
            is AgentLog.Thinking -> {
                // Render as a typing/spinner state — we keep the assistant message in `pending`
                // mode and append a system tool event so the user can see step progression.
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
            is AgentLog.Assistant -> {
                updateAssistant(assistantId) { msg ->
                    val newContent =
                        if (msg.content.isBlank()) entry.text else msg.content + "\n\n" + entry.text
                    msg.copy(content = newContent, pending = false)
                }
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
                updateAssistant(assistantId) { msg ->
                    msg.copy(
                        doneSummary = entry.summary,
                        pending = false,
                    )
                }
                _state.update {
                    it.copy(usage = it.usage.copy(messages = it.usage.messages + 1))
                }
                persistMessages()
            }
            is AgentLog.Error -> {
                updateAssistant(assistantId) { msg ->
                    msg.copy(error = entry.message, pending = false)
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
        storage.saveMessages(_state.value.messages)
        storage.saveUsage(_state.value.usage)
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
