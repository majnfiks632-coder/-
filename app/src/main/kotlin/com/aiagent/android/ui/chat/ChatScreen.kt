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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        containerColor = KiroColors.Background,
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
            Column(modifier = Modifier.imePadding().windowInsetsPadding(WindowInsets.navigationBars)) {
                HorizontalDivider(color = KiroColors.Border, thickness = 1.dp)
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
                        MessageBubble(message = msg)
                    }
                }
            }
        }
    }

    // -------- Modal sheets --------

    if (state.modelPickerOpen) {
        ModelPickerSheet(
            selected = state.model,
            baseUrlConfigured = state.baseUrl.isNotBlank(),
            loadModels = { viewModel.loadModels() },
            onSelect = { viewModel.setModel(it) },
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
            onApiKey = viewModel::updateApiKey,
            onBaseUrl = viewModel::updateBaseUrl,
            onTemperature = viewModel::updateTemperature,
            onMaxTokens = viewModel::updateMaxTokens,
            onSystemPrompt = viewModel::updateSystemPrompt,
            onSendScreenshots = viewModel::updateSendScreenshots,
            onAutoScreenshot = viewModel::updateAutoScreenshot,
            onRecordUserActions = viewModel::updateRecordUserActions,
            onJoystick = viewModel::updateJoystickEnabled,
            onSettingsOverlay = viewModel::updateSettingsOverlayEnabled,
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

