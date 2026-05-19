package com.aiagent.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroColors

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onMicClick: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestMic: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Track whether the user is currently anchored at the bottom of the conversation. We
    // only auto-scroll while streaming if this is true — otherwise pulling up to read a
    // previous message would yank you back to the live stream every chunk, which is what
    // the user reported as "меня переносит в начало, пролистать не могу".
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            // 120px tolerance so swiping a little past the end still counts as «at bottom».
            lastVisible.index >= total - 1 &&
                lastVisible.offset + lastVisible.size <= info.viewportEndOffset + 120
        }
    }

    // When new content streams in, auto-scroll to the END of the last message (not its
    // beginning — which is what [animateScrollToItem] alone would do and is why the user
    // got stuck on the top of long replies). Skipped entirely when the user has scrolled
    // up; they regain control until they manually scroll back down or send a new message.
    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.content?.length,
        state.messages.lastOrNull()?.reasoning?.length,
    ) {
        if (!isAtBottom || state.messages.isEmpty()) return@LaunchedEffect
        val lastIdx = state.messages.size - 1
        listState.scrollToItem(lastIdx)
        // Float.MAX_VALUE is clamped by Compose to «end of scrollable content» — this puts
        // the bottom of the streaming bubble flush with the viewport bottom.
        listState.scrollBy(Float.MAX_VALUE)
    }

    Scaffold(
        containerColor = KiroColors.Background,
        // We handle every system inset ourselves (status bar on topBar, navigation bar
        // + IME on bottomBar). Telling Scaffold there are no "content insets" stops it
        // from double-applying the navigation bar padding to the bottomBar slot, which
        // is what made the input field jump to TWICE the keyboard height on focus.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                ChatHeader(
                    model = state.model,
                    usage = state.usage,
                    quotaCap = state.quotaCap,
                    onModelClick = { viewModel.setModelPickerOpen(true) },
                    onQuotaClick = { viewModel.setQuotaPanelOpen(true) },
                    onSettingsClick = { viewModel.setSettingsOpen(true) },
                )
                HorizontalDivider(color = KiroColors.Border, thickness = 1.dp)
            }
        },
        bottomBar = {
            // Pad bottomBar contents above the navigation bar AND the IME. When the
            // keyboard is open `imePadding` already covers the navigation bar height,
            // so stacking these is safe and produces the right result on every API.
            // `windowSoftInputMode="adjustResize"` is set on the activity so the IME
            // inset is reported (not panned) and `imePadding` doesn't double-apply.
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                HorizontalDivider(color = KiroColors.Border, thickness = 1.dp)
                val isListening by viewModel.dictationListening.collectAsState()
                MessageInput(
                    isStreaming = state.isStreaming,
                    onSend = { text, attachments ->
                        if (state.pendingQuestionMessageId != null) {
                            viewModel.submitAnswer(text)
                        } else {
                            viewModel.send(text, attachments)
                        }
                    },
                    onStop = { viewModel.cancelAgent() },
                    onMicClick = onMicClick,
                    isListening = isListening,
                    dictationResults = viewModel.dictationResults,
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (state.messages.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            reasoningModeEnabled = state.reasoningModeEnabled,
                            onAnswerImmediately = { viewModel.forceAnswerNow() },
                        )
                    }
                }
                // «Скроллнуть вниз» — появляется только когда пользователь пролистал
                // вверх и пропустил часть стрима. Возвращает в самый низ одним тапом
                // вместо ручной прокрутки через длинный ответ.
                if (!isAtBottom && state.messages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(KiroColors.Surface)
                            .border(1.dp, KiroColors.Border, CircleShape)
                            .clickable {
                                coroutineScope.launch {
                                    val lastIdx = state.messages.size - 1
                                    if (lastIdx >= 0) {
                                        listState.scrollToItem(lastIdx)
                                        listState.scrollBy(Float.MAX_VALUE)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "К новым сообщениям",
                            tint = KiroColors.Foreground,
                        )
                    }
                }
            }
        }
    }

    // -------- Modal sheets --------

    if (state.modelPickerOpen) {
        ModelPickerSheet(
            selectedModel = state.model,
            selectedSlot = state.activeProvider,
            loadModels = { viewModel.loadModels() },
            onSelect = { slot, id -> viewModel.selectModel(slot, id) },
            onDismiss = { viewModel.setModelPickerOpen(false) },
        )
    }
    if (state.quotaPanelOpen) {
        QuotaPanelSheet(
            usage = state.usage,
            quotaCap = state.quotaCap,
            onSaveCap = { viewModel.setQuotaCap(it); viewModel.setQuotaPanelOpen(false) },
            onClearChat = { viewModel.clearChat(); viewModel.setQuotaPanelOpen(false) },
            onDismiss = { viewModel.setQuotaPanelOpen(false) },
        )
    }
    if (state.settingsOpen) {
        SettingsSheet(
            state = state,
            onDismiss = { viewModel.setSettingsOpen(false) },
            onProvider1Name = viewModel::updateProvider1Name,
            onProvider1BaseUrl = viewModel::updateProvider1BaseUrl,
            onProvider1ApiKey = viewModel::updateProvider1ApiKey,
            onProvider1ExtraApiKeys = viewModel::updateProvider1ExtraApiKeys,
            onProvider1Transport = viewModel::updateProvider1Transport,
            onProvider2Name = viewModel::updateProvider2Name,
            onProvider2BaseUrl = viewModel::updateProvider2BaseUrl,
            onProvider2ApiKey = viewModel::updateProvider2ApiKey,
            onProvider2ExtraApiKeys = viewModel::updateProvider2ExtraApiKeys,
            onProvider2Transport = viewModel::updateProvider2Transport,
            onActiveProvider = viewModel::setActiveProvider,
            onTemperature = viewModel::updateTemperature,
            onMaxTokens = viewModel::updateMaxTokens,
            onSystemPrompt = viewModel::updateSystemPrompt,
            onSendScreenshots = viewModel::updateSendScreenshots,
            onAutoScreenshot = viewModel::updateAutoScreenshot,
            onRecordUserActions = viewModel::updateRecordUserActions,
            onJoystick = viewModel::updateJoystickEnabled,
            onSettingsOverlay = viewModel::updateSettingsOverlayEnabled,
            onDeviceControl = viewModel::updateDeviceControlEnabled,
            onRunInBackground = viewModel::updateRunInBackground,
            onWaitForMessages = viewModel::updateWaitForMessages,
            onReasoningMode = viewModel::updateReasoningMode,
            onOpenAppAfterAnswer = viewModel::updateOpenAppAfterAnswer,
            onRequestAccessibility = onRequestAccessibility,
            onRequestOverlay = onRequestOverlay,
            onRequestStorage = onRequestStorage,
            onRequestMic = onRequestMic,
            onPickFolder = onPickFolder,
            onPanic = { viewModel.panicShutdown(); viewModel.setSettingsOpen(false) },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(KiroColors.Surface)
                .border(1.dp, KiroColors.Border, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = KiroColors.Accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.size(16.dp))
        Text(
            text = "Kiro Agent",
            color = KiroColors.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Чат-интерфейс Kiro + автономный агент. Спроси что-нибудь или попроси выполнить задачу — агент сам решит, нужны ли инструменты (тапы, скриншоты, файлы, голос).",
            color = KiroColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(16.dp))
        SuggestionCard(text = "Открой YouTube, найди \"jazz lo-fi\" и поставь первое видео")
        Spacer(Modifier.size(8.dp))
        SuggestionCard(text = "Сделай скриншот, прочитай текст и переведи на английский")
        Spacer(Modifier.size(8.dp))
        SuggestionCard(text = "Напиши систему uvm-конфигов и сохрани в .md файл")
    }
}

@Composable
private fun SuggestionCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface)
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            color = KiroColors.Foreground,
            fontSize = 13.sp,
        )
    }
}

