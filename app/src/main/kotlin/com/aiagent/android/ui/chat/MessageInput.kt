package com.aiagent.android.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroColors

/**
 * Bottom input bar that mirrors Kiro Mobile Chat's `MessageInput.tsx`:
 * attachment chips, multiline textarea, attach picker (image / file), and a
 * single round Send / Stop button. The optional mic button forwards to the
 * caller — wire it to your STT pipeline if you want voice instructions.
 */
@Composable
fun MessageInput(
    isStreaming: Boolean,
    onSend: (text: String, attachments: List<UiAttachment>) -> Unit,
    onStop: () -> Unit,
    onMicClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val attachments: SnapshotStateList<UiAttachment> = remember { mutableStateListOf() }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            attachments.add(buildAttachment(context, uri, kind = AttachmentKind.IMAGE))
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            attachments.add(buildAttachment(context, uri, kind = AttachmentKind.FILE))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KiroColors.Surface.copy(alpha = 0.95f))
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(attachments) { att ->
                    AttachmentChip(att = att, onRemove = { attachments.remove(att) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Image pick
            IconButton(onClick = { pickImage.launch("image/*") }) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "Прикрепить изображение",
                    tint = KiroColors.Muted,
                )
            }
            // File pick
            IconButton(onClick = { pickFile.launch("*/*") }) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Прикрепить файл",
                    tint = KiroColors.Muted,
                )
            }
            // Mic
            if (onMicClick != null) {
                IconButton(onClick = onMicClick) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = "Диктовать",
                        tint = KiroColors.Muted,
                    )
                }
            }

            // Textarea
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(KiroColors.Surface2)
                    .border(1.dp, KiroColors.Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "Сообщение для Kiro Agent…",
                        color = KiroColors.Muted,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = KiroColors.Foreground, fontSize = 14.sp),
                    cursorBrush = SolidColor(KiroColors.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Send / Stop button
            SendOrStopButton(
                isStreaming = isStreaming,
                hasContent = text.trim().isNotEmpty() || attachments.isNotEmpty(),
                onSend = {
                    val payload = text.trim()
                    val attachList = attachments.toList()
                    text = ""
                    attachments.clear()
                    onSend(payload, attachList)
                },
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun SendOrStopButton(
    isStreaming: Boolean,
    hasContent: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    if (isStreaming) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(KiroColors.Danger)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Stop,
                contentDescription = "Стоп",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        val bg = if (hasContent) KiroColors.Accent else KiroColors.Surface2
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(enabled = hasContent, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = "Отправить",
                tint = if (hasContent) Color.White else KiroColors.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AttachmentChip(att: UiAttachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(KiroColors.Surface2)
            .border(1.dp, KiroColors.Border, RoundedCornerShape(10.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (att.kind == AttachmentKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = KiroColors.Muted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = att.name,
                color = KiroColors.Foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${(att.sizeBytes / 1024.0).toInt()} KB",
                color = KiroColors.Muted,
                fontSize = 10.sp,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Удалить",
                tint = KiroColors.Muted,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun buildAttachment(ctx: Context, uri: Uri, kind: AttachmentKind): UiAttachment {
    var displayName = uri.lastPathSegment ?: "attachment"
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
    }
    val mime = ctx.contentResolver.getType(uri)
        ?: if (kind == AttachmentKind.IMAGE) "image/*" else "application/octet-stream"
    return UiAttachment(
        id = "att-${System.currentTimeMillis()}-${(0..1_000_000).random()}",
        name = displayName,
        mimeType = mime,
        sizeBytes = size,
        kind = kind,
        dataUri = uri.toString(),
    )
}
