package com.aiagent.android.ui.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroColors
import kotlinx.coroutines.launch

/**
 * Bottom sheet that asks the configured OpenAI-compatible endpoint for its model list
 * (`GET {baseUrl}/models`) instead of hard-coding one. Falls back to a textual prompt
 * if Base URL is empty / the call fails. There is always a "custom model id" escape
 * hatch at the bottom in case the user wants to type one in manually (e.g. tiny
 * llama.cpp servers that don't implement `/models`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    selected: String,
    baseUrlConfigured: Boolean,
    loadModels: suspend () -> Result<List<String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customInput by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = null
        val result = loadModels()
        result.onSuccess {
            models = it
            error = if (it.isEmpty()) "Провайдер вернул пустой список моделей." else null
        }.onFailure {
            models = emptyList()
            error = it.message ?: it::class.java.simpleName
        }
        loading = false
    }

    LaunchedEffect(baseUrlConfigured) {
        if (baseUrlConfigured) reload()
    }

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
                .heightIn(min = 200.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Выбор модели",
                    color = KiroColors.Foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (baseUrlConfigured) {
                    IconButton(
                        onClick = { scope.launch { reload() } },
                        enabled = !loading,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Обновить",
                            tint = if (loading) KiroColors.Muted else KiroColors.Accent2,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (baseUrlConfigured) {
                    "Список грузится с GET {baseUrl}/models — OpenAI-совместимый эндпоинт."
                } else {
                    "Сначала вставь Base URL и API-ключ в Настройках — потом тут появится список моделей провайдера."
                },
                color = KiroColors.Muted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))

            when {
                !baseUrlConfigured -> {
                    EmptyHint(
                        text = "Открой ⚙ Настройки → API провайдера и заполни Base URL и ключ.",
                    )
                }
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = KiroColors.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Гружу список моделей…",
                            color = KiroColors.Muted,
                            fontSize = 13.sp,
                        )
                    }
                }
                error != null -> {
                    ErrorHint(text = error.orEmpty())
                }
                else -> {
                    models.forEach { id ->
                        ModelRow(
                            id = id,
                            selected = id == selected,
                            onClick = { onSelect(id) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Свой ID модели",
                color = KiroColors.Foreground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Если провайдер не отдаёт /models или нужна конкретная сборка — впиши вручную.",
                color = KiroColors.Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(KiroColors.Surface2)
                        .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (customInput.isEmpty()) {
                        Text(
                            text = "напр. gpt-4o-mini",
                            color = KiroColors.Muted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    BasicTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        textStyle = TextStyle(
                            color = KiroColors.Foreground,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(KiroColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(KiroColors.Accent)
                        .clickable(enabled = customInput.isNotBlank()) { onSelect(customInput.trim()) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Выбрать", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelRow(id: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) KiroColors.Surface2 else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) KiroColors.Accent else KiroColors.Border,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Star,
            contentDescription = null,
            tint = if (selected) KiroColors.Accent else KiroColors.Muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = id,
            color = KiroColors.Foreground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Выбрано",
                tint = KiroColors.Accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2.copy(alpha = 0.5f))
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(text = text, color = KiroColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ErrorHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22E36464))
            .border(1.dp, KiroColors.Danger, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = KiroColors.Danger,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Не получилось загрузить модели",
                color = KiroColors.Danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = text,
            color = KiroColors.Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
