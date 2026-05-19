package com.aiagent.android.ui.chat

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroAccentBrush
import com.aiagent.android.ui.theme.KiroColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onProvider1Name: (String) -> Unit,
    onProvider1BaseUrl: (String) -> Unit,
    onProvider1ApiKey: (String) -> Unit,
    onProvider1ExtraApiKeys: (String) -> Unit,
    onProvider1Transport: (String) -> Unit,
    onProvider2Name: (String) -> Unit,
    onProvider2BaseUrl: (String) -> Unit,
    onProvider2ApiKey: (String) -> Unit,
    onProvider2ExtraApiKeys: (String) -> Unit,
    onProvider2Transport: (String) -> Unit,
    onActiveProvider: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onSendScreenshots: (Boolean) -> Unit,
    onAutoScreenshot: (Boolean) -> Unit,
    onRecordUserActions: (Boolean) -> Unit,
    onJoystick: (Boolean) -> Unit,
    onSettingsOverlay: (Boolean) -> Unit,
    onDeviceControl: (Boolean) -> Unit,
    onRunInBackground: (Boolean) -> Unit,
    onWaitForMessages: (Boolean) -> Unit,
    onReasoningMode: (Boolean) -> Unit,
    onOpenAppAfterAnswer: (Boolean) -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestMic: () -> Unit,
    onPickFolder: () -> Unit,
    onPanic: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = KiroColors.Surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Настройки",
                color = KiroColors.Foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // -------- API providers (Kiro AI primary by default) --------
            SectionTitle("Активный провайдер")
            ActiveProviderRow(
                activeProvider = state.activeProvider,
                provider1Name = state.provider1Name,
                provider2Name = state.provider2Name,
                onActiveProvider = onActiveProvider,
            )

            ProviderCard(
                title = "Провайдер 1",
                name = state.provider1Name,
                baseUrl = state.provider1BaseUrl,
                apiKey = state.provider1ApiKey,
                extraApiKeys = state.provider1ExtraApiKeys,
                transport = state.provider1Transport,
                onName = onProvider1Name,
                onBaseUrl = onProvider1BaseUrl,
                onApiKey = onProvider1ApiKey,
                onExtraApiKeys = onProvider1ExtraApiKeys,
                onTransport = onProvider1Transport,
            )
            ProviderCard(
                title = "Провайдер 2",
                name = state.provider2Name,
                baseUrl = state.provider2BaseUrl,
                apiKey = state.provider2ApiKey,
                extraApiKeys = state.provider2ExtraApiKeys,
                transport = state.provider2Transport,
                onName = onProvider2Name,
                onBaseUrl = onProvider2BaseUrl,
                onApiKey = onProvider2ApiKey,
                onExtraApiKeys = onProvider2ExtraApiKeys,
                onTransport = onProvider2Transport,
            )

            // -------- Generation --------
            SectionTitle("Параметры генерации")
            NumberRow(
                label = "Макс. токенов ответа",
                value = state.maxTokens,
                placeholder = "2048",
                onChange = onMaxTokens,
            )
            FloatRow(
                label = "Temperature (0…2)",
                value = state.temperature,
                onChange = onTemperature,
            )
            TextRow(
                label = "System prompt (опц.)",
                value = state.systemPrompt,
                placeholder = "Доп. инструкции…",
                onChange = onSystemPrompt,
                multiline = true,
            )

            // -------- Toggles --------
            SectionTitle("Поведение")
            SwitchRow(
                title = "Скриншоты в каждом ходе",
                subtitle = "Авто-добавлять `take_screenshot` перед запросом",
                checked = state.autoScreenshotEachTurn,
                onChange = onAutoScreenshot,
            )
            SwitchRow(
                title = "Отправлять скриншоты в LLM",
                subtitle = "Только для vision-моделей. По умолчанию выключено.",
                checked = state.sendScreenshots,
                onChange = onSendScreenshots,
            )
            SwitchRow(
                title = "Запись действий пользователя",
                subtitle = "Агент знает что вы тапали / печатали между ходами.",
                checked = state.recordUserActions,
                onChange = onRecordUserActions,
            )
            SwitchRow(
                title = "Плавающий джойстик",
                subtitle = "Удобный оверлей для ручной игры.",
                checked = state.joystickEnabled,
                onChange = onJoystick,
            )
            SwitchRow(
                title = "Плавающая ⚙ панель",
                subtitle = "Быстрый доступ к голосу / стопу с экрана.",
                checked = state.settingsOverlayEnabled,
                onChange = onSettingsOverlay,
            )

            // -------- Device control + background --------
            SectionTitle("Управление и фон")
            SwitchRow(
                title = "Управлять телефоном",
                subtitle = when {
                    !state.deviceControlEnabled ->
                        "Спецвозможности и плавающие кнопки выключены. UI-инструменты вернут soft-ошибку."
                    state.appForeground ->
                        "Включено. Кнопки прячутся пока чат открыт — вернутся, как только свернёшь приложение."
                    else ->
                        "Агент может тапать, свайпить, читать экран. Выключи, когда это не нужно."
                },
                checked = state.deviceControlEnabled,
                onChange = onDeviceControl,
            )
            SwitchRow(
                title = "Работать в фоне",
                subtitle = "Пока агент думает — висит уведомление; когда закончит — придёт второе.",
                checked = state.runInBackground,
                onChange = onRunInBackground,
            )
            SwitchRow(
                title = "Не останавливаться после ответа",
                subtitle = if (state.waitForMessages)
                    "Включено: агент не закрывается после `done`, продолжает слушать тебя (live-coach)."
                else
                    "Выключено (по умолчанию): агент сам завершается сразу после ответа (`done` → стоп).",
                checked = state.waitForMessages,
                onChange = onWaitForMessages,
            )
            SwitchRow(
                title = "Открывать приложение после ответа",
                subtitle = if (state.openAppAfterAnswer)
                    "Включено: когда агент закончит ответ, приложение само вернётся на передний план."
                else
                    "Выключено (по умолчанию): после ответа агент тебя не отвлекает — увидишь его в уведомлении.",
                checked = state.openAppAfterAnswer,
                onChange = onOpenAppAfterAnswer,
            )

            // -------- Reasoning --------
            SectionTitle("Рассуждения модели")
            SwitchRow(
                title = "Показывать рассуждения",
                subtitle = if (state.reasoningModeEnabled)
                    "Включено: видно цепочку рассуждений модели до ответа. Рядом есть «Ответить сразу»."
                else
                    "Выключено: модель отвечает без видимых рассуждений, панель скрыта.",
                checked = state.reasoningModeEnabled,
                onChange = onReasoningMode,
            )

            // -------- Permissions --------
            SectionTitle("Разрешения")
            PermissionRow(
                title = "Accessibility (тапы / свайпы)",
                granted = state.accessibilityEnabled,
                onClick = onRequestAccessibility,
            )
            PermissionRow(
                title = "Поверх других окон",
                granted = state.overlayGranted,
                onClick = onRequestOverlay,
            )
            PermissionRow(
                title = "Доступ к файлам (MANAGE_EXTERNAL_STORAGE)",
                granted = state.manageStorageGranted,
                onClick = onRequestStorage,
            )
            PermissionRow(
                title = "Микрофон",
                granted = state.micGranted,
                onClick = onRequestMic,
            )
            ButtonRow(text = "Выбрать SAF-папку для файлов", onClick = onPickFolder)

            // -------- Panic --------
            SectionTitle("Срочно")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33E36464))
                    .border(1.dp, KiroColors.Danger, RoundedCornerShape(10.dp))
                    .clickable(onClick = onPanic)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = KiroColors.Danger,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Дед инсайд — выключить всё",
                            color = KiroColors.Danger,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Остановит агента, оверлеи, захват экрана, запись действий.",
                            color = KiroColors.Danger,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = KiroColors.Accent2,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ActiveProviderRow(
    activeProvider: Int,
    provider1Name: String,
    provider2Name: String,
    onActiveProvider: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProviderToggle(
            label = provider1Name.ifBlank { "Провайдер 1" },
            slotNumber = 1,
            active = activeProvider == 1,
            onClick = { onActiveProvider(1) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        ProviderToggle(
            label = provider2Name.ifBlank { "Провайдер 2" },
            slotNumber = 2,
            active = activeProvider == 2,
            onClick = { onActiveProvider(2) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProviderToggle(
    label: String,
    slotNumber: Int,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) KiroColors.Accent else KiroColors.Surface2)
            .border(
                1.dp,
                if (active) KiroColors.Accent else KiroColors.Border,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#$slotNumber",
            color = if (active) Color.White.copy(alpha = 0.85f) else KiroColors.Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (active) Color.White else KiroColors.Foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    name: String,
    baseUrl: String,
    apiKey: String,
    extraApiKeys: String,
    transport: String,
    onName: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onExtraApiKeys: (String) -> Unit,
    onTransport: (String) -> Unit,
) {
    val isKiro = transport == "kiro"
    val extraCount = extraApiKeys.lines().count { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KiroColors.Surface2.copy(alpha = 0.4f))
            .border(1.dp, KiroColors.Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = KiroColors.Foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        TransportRow(transport = transport, onChange = onTransport)
        TextRow(
            label = "Название",
            value = name,
            placeholder = "Kiro AI / OpenAI / OpenRouter",
            onChange = onName,
        )
        TextRow(
            label = "Base URL",
            value = baseUrl,
            placeholder = if (isKiro) "https://q.us-east-1.amazonaws.com" else "https://api.openai.com/v1",
            onChange = onBaseUrl,
            mono = true,
        )
        TextRow(
            label = "API ключ (основной)",
            value = apiKey,
            placeholder = if (isKiro) "ksk_…" else "sk-…",
            onChange = onApiKey,
            mono = true,
            secret = true,
        )
        // Multi-key pool. The user pastes one key per line; if the primary above fails
        // with auth (401/403/expired) or persistent quota (HTTP 429), the client rotates
        // through these in order until one succeeds, and promotes the first working one
        // to be the new primary so the rotation persists across runs.
        TextRow(
            label = if (extraCount > 0) {
                "Запасные ключи · $extraCount шт (по одному на строку)"
            } else {
                "Запасные ключи (по одному на строку)"
            },
            value = extraApiKeys,
            placeholder = if (isKiro) {
                "ksk_…\nksk_…\n(каждый с новой строки)"
            } else {
                "sk-…\nsk-…\n(каждый с новой строки)"
            },
            onChange = onExtraApiKeys,
            mono = true,
            multiline = true,
            secret = true,
        )
        Text(
            text = "Если основной ключ выдаёт 401/403/Expired или 429 — клиент сам " +
                "переключится на следующий рабочий из списка и сделает его основным.",
            color = KiroColors.Muted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TransportRow(transport: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportToggle(
            label = "Kiro нативный",
            active = transport == "kiro",
            onClick = { onChange("kiro") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TransportToggle(
            label = "OpenAI HTTP",
            active = transport != "kiro",
            onClick = { onChange("openai") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TransportToggle(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) KiroColors.Accent.copy(alpha = 0.18f) else KiroColors.Surface2)
            .border(
                1.dp,
                if (active) KiroColors.Accent else KiroColors.Border,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (active) KiroColors.Accent else KiroColors.Foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = KiroColors.Accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TextRow(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    mono: Boolean = false,
    multiline: Boolean = false,
    secret: Boolean = false,
) {
    Column {
        Text(
            text = label,
            color = KiroColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIfNotMultiline(multiline)
                .clip(RoundedCornerShape(10.dp))
                .background(KiroColors.Surface2)
                .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = KiroColors.Muted,
                    fontSize = 13.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(
                    color = KiroColors.Foreground,
                    fontSize = 13.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                ),
                cursorBrush = SolidColor(KiroColors.Accent),
                singleLine = !multiline,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Modifier.heightIfNotMultiline(multiline: Boolean): Modifier =
    if (multiline) this else this.then(Modifier.height(44.dp))

@Composable
private fun NumberRow(label: String, value: Int, placeholder: String, onChange: (Int) -> Unit) {
    var localText by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Text(
            text = label,
            color = KiroColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(KiroColors.Surface2)
                .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (localText.isEmpty()) {
                Text(text = placeholder, color = KiroColors.Muted, fontSize = 13.sp)
            }
            BasicTextField(
                value = localText,
                onValueChange = { v ->
                    val sanitized = v.filter { it.isDigit() }.take(10)
                    localText = sanitized
                    onChange(sanitized.toIntOrNull() ?: 0)
                },
                textStyle = TextStyle(color = KiroColors.Foreground, fontSize = 13.sp),
                cursorBrush = SolidColor(KiroColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FloatRow(label: String, value: Float, onChange: (Float) -> Unit) {
    var localText by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Text(
            text = label,
            color = KiroColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(KiroColors.Surface2)
                .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = localText,
                onValueChange = { v ->
                    val sanitized = v.filter { it.isDigit() || it == '.' }.take(6)
                    localText = sanitized
                    sanitized.toFloatOrNull()?.let(onChange)
                },
                textStyle = TextStyle(color = KiroColors.Foreground, fontSize = 13.sp),
                cursorBrush = SolidColor(KiroColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2.copy(alpha = 0.5f))
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = KiroColors.Foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(text = subtitle, color = KiroColors.Muted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = KiroColors.Accent,
                uncheckedThumbColor = KiroColors.Muted,
                uncheckedTrackColor = KiroColors.Surface2,
                uncheckedBorderColor = KiroColors.Border,
            ),
        )
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = if (granted) KiroColors.Success else KiroColors.Border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (granted) KiroColors.Success else KiroColors.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = KiroColors.Foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = KiroColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ButtonRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroAccentBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
