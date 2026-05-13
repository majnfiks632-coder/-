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
    onApiKey: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onSystemPrompt: (String) -> Unit,
    onSendScreenshots: (Boolean) -> Unit,
    onAutoScreenshot: (Boolean) -> Unit,
    onRecordUserActions: (Boolean) -> Unit,
    onJoystick: (Boolean) -> Unit,
    onSettingsOverlay: (Boolean) -> Unit,
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

            // -------- API --------
            SectionTitle("API провайдера")
            TextRow(
                label = "Base URL",
                value = state.baseUrl,
                placeholder = "https://api.openai.com/v1",
                onChange = onBaseUrl,
                mono = true,
            )
            TextRow(
                label = "API ключ",
                value = state.apiKey,
                placeholder = "sk-…",
                onChange = onApiKey,
                mono = true,
                secret = true,
            )

            // -------- Generation --------
            SectionTitle("Параметры генерации")
            NumberRow(
                label = "Макс. шагов агента",
                value = state.maxSteps,
                placeholder = "10000",
                onChange = onMaxSteps,
            )
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
