package com.aiagent.android.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    reasoningModeEnabled: Boolean = true,
    onAnswerImmediately: () -> Unit = {},
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
            // Reasoning panel rendered above the bubble — only for assistant messages and
            // only when the user has the master toggle on. Hidden entirely when there's no
            // reasoning to show (neither streaming nor finalised).
            if (!isUser && reasoningModeEnabled &&
                (message.reasoning.isNotEmpty() || message.reasoningPending)
            ) {
                ReasoningPanel(
                    text = message.reasoning,
                    pending = message.reasoningPending,
                    onAnswerImmediately = onAnswerImmediately,
                )
                Spacer(Modifier.height(6.dp))
            }
            BubbleBody(message)
            FooterRow(message)
        }

        if (isUser) Spacer(Modifier.width(8.dp))
        if (isUser) Avatar(isUser = true)
    }
}

/**
 * Collapsible «Рассуждения» panel rendered above the assistant bubble. Streams its
 * text via [text] as the model thinks; while [pending] is true it shows a typing
 * indicator next to the brain icon and exposes an «Ответить сразу» shortcut that tells
 * the [ChatViewModel] to suppress further reasoning chunks for this turn.
 *
 * The panel auto-expands while [pending] is true so the user can watch the model
 * think, and collapses to a one-line summary once reasoning is finalised — click
 * the chevron to re-expand. Expansion state is remembered per-bubble across
 * recomposition so collapsing one assistant turn doesn't collapse others.
 */
@Composable
private fun ReasoningPanel(
    text: String,
    pending: Boolean,
    onAnswerImmediately: () -> Unit,
) {
    // While the model is streaming reasoning we want the panel open. Once streaming
    // ends, default to collapsed; user can re-expand. We use `pending` as the seed
    // for the remembered state so the first compose after streaming-end keeps it
    // open until the user explicitly collapses.
    var expanded by remember(pending) { mutableStateOf(true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2)
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                tint = KiroColors.Accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (pending) "Рассуждаю…" else "Рассуждения",
                color = KiroColors.Foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (pending) {
                Spacer(Modifier.width(8.dp))
                TypingDots()
            }
            Spacer(Modifier.width(8.dp))
            // «Ответить сразу» button — only shown while reasoning is still streaming
            // (otherwise the model is done thinking and the button has no effect).
            if (pending) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(KiroColors.Accent)
                        .clickable { onAnswerImmediately() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Ответить сразу",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Right-aligned expand/collapse chevron — wrap in fillMaxWidth + alignBy
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
                    tint = KiroColors.Muted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }
        if (expanded && text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            // Cap reasoning panel height so an obnoxiously long chain-of-thought doesn't
            // shove the user's input box off-screen. Long reasoning becomes scrollable.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    color = KiroColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
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
    val context = LocalContext.current
    // Image attachments — either an inline `data:image/...;base64,...` dataUri OR a
    // `content://` URI. We try the base64 path first (covers every picker-generated
    // image since MessageInput encodes them inline) and fall back to ContentResolver
    // for stragglers (legacy persisted messages, files re-attached via the share sheet).
    val imageBitmap = remember(att.id, att.dataUri) {
        if (att.kind != AttachmentKind.IMAGE) return@remember null
        runCatching {
            val uri = att.dataUri
            val bmp = when {
                uri.startsWith("data:") -> {
                    val b64 = uri.substringAfter("base64,", missingDelimiterValue = "")
                    if (b64.isEmpty()) null else {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                uri.startsWith("content:") || uri.startsWith("file:") -> {
                    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                else -> null
            }
            bmp?.asImageBitmap()
        }.onFailure {
            Log.w("MessageBubble", "Failed to decode image attachment ${att.name}", it)
        }.getOrNull()
    }

    if (att.kind == AttachmentKind.IMAGE && imageBitmap != null) {
        // Render the actual picture. Cap dimensions so a 12-megapixel selfie doesn't
        // blow up the whole bubble; ContentScale.Fit preserves aspect ratio.
        Column {
            Box(
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp)),
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = att.name,
                    contentScale = ContentScale.Fit,
                )
            }
            Text(
                text = "${att.name} • ${(att.sizeBytes / 1024.0).toInt()} KB",
                color = KiroColors.Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }
        return
    }

    // Non-image attachment OR image we failed to decode — fall back to a name/size card.
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
