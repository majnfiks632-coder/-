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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroAccentBrush
import com.aiagent.android.ui.theme.KiroColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaPanelSheet(
    usage: UsageStats,
    quotaCap: Int,
    onSaveCap: (Int) -> Unit,
    onClearChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var capInput by remember { mutableStateOf(if (quotaCap > 0) quotaCap.toString() else "") }
    LaunchedEffect(quotaCap) {
        capInput = if (quotaCap > 0) quotaCap.toString() else ""
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Использование токенов",
                color = KiroColors.Foreground,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            StatRow(label = "Всего", value = usage.totalTokens, accent = true)
            StatRow(label = "Промпт", value = usage.promptTokens)
            StatRow(label = "Ответ", value = usage.completionTokens)
            StatRow(label = "Ходов диалога", value = usage.messages)

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Лимит (для отображения %)",
                color = KiroColors.Foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
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
                    if (capInput.isEmpty()) {
                        Text(
                            text = "напр. 1000000 (0 = без лимита)",
                            color = KiroColors.Muted,
                            fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = capInput,
                        onValueChange = { v -> capInput = v.filter { it.isDigit() }.take(10) },
                        textStyle = TextStyle(color = KiroColors.Foreground, fontSize = 13.sp),
                        cursorBrush = SolidColor(KiroColors.Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(KiroAccentBrush)
                        .clickable {
                            onSaveCap(capInput.toIntOrNull() ?: 0)
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Сохранить", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (quotaCap > 0 && usage.totalTokens > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgress(usage = usage.totalTokens, cap = quotaCap)
            }

            if (usage.byModel.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "По моделям",
                    color = KiroColors.Foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                usage.byModel.entries.sortedByDescending { it.value }.forEach { (model, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = kiroModelLabel(model),
                            color = KiroColors.Foreground,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = formatTokens(count),
                            color = KiroColors.Muted,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
                    .clickable(onClick = onClearChat)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = KiroColors.Danger,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Очистить чат и статистику",
                    color = KiroColors.Foreground,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int, accent: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = KiroColors.Muted, fontSize = 13.sp)
        Text(
            text = formatTokens(value),
            color = if (accent) KiroColors.Accent2 else KiroColors.Foreground,
            fontSize = if (accent) 16.sp else 13.sp,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LinearProgress(usage: Int, cap: Int) {
    val ratio = (usage.toFloat() / cap).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(KiroColors.Surface2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(KiroAccentBrush),
        )
    }
}
