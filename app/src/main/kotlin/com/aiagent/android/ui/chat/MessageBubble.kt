package com.aiagent.android.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroAccentBrush
import com.aiagent.android.ui.theme.KiroColors

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.USER
    val isSystem = message.role == ChatRole.SYSTEM

    if (isSystem) {
        SystemNote(message.content)
        return
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) Avatar(isUser = false)
        if (!isUser) Spacer(Modifier.width(8.dp))

        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            BubbleBody(message)
            FooterRow(message)
        }

        if (isUser) Spacer(Modifier.width(8.dp))
        if (isUser) Avatar(isUser = true)
    }
}

@Composable
private fun Avatar(isUser: Boolean) {
    if (isUser) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(KiroColors.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(KiroColors.Surface2)
                .border(1.dp, KiroColors.Border, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = KiroColors.Foreground,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BubbleBody(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (isUser) KiroColors.UserBubble else KiroColors.AssistantBubble)
            .border(1.dp, KiroColors.Border, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Attachments preview.
        if (message.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.attachments.forEach { att -> AttachmentPreview(att) }
            }
        }

        // Tool events (collapsed cards) for assistant bubbles.
        if (!isUser && message.toolEvents.isNotEmpty()) {
            message.toolEvents.forEach { ToolEventCard(it) }
        }

        // Main text body.
        when {
            message.content.isNotBlank() -> {
                if (isUser) {
                    Text(
                        text = message.content,
                        color = KiroColors.Foreground,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                } else {
                    MarkdownText(text = message.content)
                }
            }
            message.pending -> TypingDots()
            else -> {}
        }

        // pendingQuestion appears as a yellow callout.
        message.pendingQuestion?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33F4C152), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFF4C152), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = "Агент спрашивает: $it",
                    color = Color(0xFFF4C152),
                    fontSize = 14.sp,
                )
            }
        }

        // Done summary.
        message.doneSummary?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = KiroColors.Success,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Готово: $it",
                    color = KiroColors.Success,
                    fontSize = 13.sp,
                )
            }
        }

        // Error inline.
        message.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Error,
                    contentDescription = null,
                    tint = KiroColors.Danger,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = it, color = KiroColors.Danger, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FooterRow(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    if (isUser || message.pending) return
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (message.content.isNotBlank()) {
            Row(
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(message.content))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = KiroColors.Muted,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(text = "Копировать", color = KiroColors.Muted, fontSize = 11.sp)
            }
        }
        message.model?.let {
            Text(text = it, color = KiroColors.Muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = KiroColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ToolEventCard(event: ToolEvent) {
    var expanded by remember { mutableStateOf(false) }
    val toneBrush: Brush = when (event.name) {
        "thinking" -> Brush.linearGradient(listOf(KiroColors.Surface2, KiroColors.Surface2))
        "system" -> Brush.linearGradient(listOf(KiroColors.Surface2, KiroColors.Surface2))
        else -> KiroAccentBrush
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2)
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(toneBrush),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.name,
                color = KiroColors.Foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = event.summary,
                color = KiroColors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.alpha(0.9f),
            )
        }
        if (expanded && event.arguments.isNotBlank()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = event.arguments,
                color = KiroColors.Muted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun AttachmentPreview(att: UiAttachment) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(KiroColors.Surface2)
            .border(1.dp, KiroColors.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = att.name,
                color = KiroColors.Foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${att.mimeType} • ${(att.sizeBytes / 1024.0).toInt()} KB",
                color = KiroColors.Muted,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { idx ->
            val alpha = 0.25f + 0.75f * kotlin.math.max(0f, 1f - kotlin.math.abs(phase - idx))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(KiroColors.Muted, RoundedCornerShape(3.dp)),
            )
            if (idx != 2) Spacer(Modifier.width(4.dp))
        }
    }
}
