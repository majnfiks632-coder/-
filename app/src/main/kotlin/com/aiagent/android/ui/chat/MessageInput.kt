package com.aiagent.android.ui.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.material.icons.outlined.CameraAlt
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
    /** When true the mic button glows red and shows a 'recording' label. */
    isListening: Boolean = false,
    /** Stream of recognised text from on-device or remote STT. Each emission is appended
     *  to the current text in the input field so the user can edit before sending. */
    dictationResults: kotlinx.coroutines.flow.Flow<String>? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val attachments: SnapshotStateList<UiAttachment> = remember { mutableStateListOf() }

    // Collect recognised speech and append it to the text field. We don't auto-send so the
    // user can correct typos before tapping the send button — matches how every other chat
    // app treats voice input.
    if (dictationResults != null) {
        LaunchedEffect(dictationResults) {
            dictationResults.collect { recognised ->
                if (recognised.isBlank()) return@collect
                text = if (text.isBlank()) recognised
                else text.trimEnd() + " " + recognised
            }
        }
    }

    // Multi-image picker — user wanted «несколько фото прикрепить за раз». Returns a list of
    // URIs (empty if the user dismissed without picking) so we just iterate.
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        for (u in uris) {
            attachments.add(buildAttachment(context, u, kind = AttachmentKind.IMAGE))
        }
    }
    // Pending MediaStore URI for the next camera capture. We pre-insert a row in
    // MediaStore.Images BEFORE launching the camera so the photo (a) lands somewhere the
    // camera app can write to, and (b) shows up in the Gallery automatically because it's
    // already indexed there. Reset to null after each capture so we don't reuse a URI.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success: Boolean ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
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
            // Image pick (multi-select)
            IconButton(onClick = { pickImages.launch("image/*") }) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = "Прикрепить изображения",
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
            // Camera — launches the system camera app via ACTION_IMAGE_CAPTURE. The capture
            // is saved to MediaStore.Images so it appears in the gallery, and the
            // resulting `content://` URI is added as an attachment (re-encoded as a
            // base64 data URI by [buildAttachment]).
            IconButton(onClick = {
                val uri = createCameraOutputUri(context)
                if (uri != null) {
                    pendingCameraUri = uri
                    takePicture.launch(uri)
                } else {
                    Log.w("MessageInput", "Failed to create MediaStore URI for camera")
                }
            }) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = "Сфотографировать",
                    tint = KiroColors.Muted,
                )
            }
            // Mic. Highlight in red while STT is listening so the user has feedback that
            // tapping it again will cancel.
            if (onMicClick != null) {
                IconButton(onClick = onMicClick) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = if (isListening) "Слушаю… нажми, чтобы отменить"
                        else "Диктовать",
                        tint = if (isListening) KiroColors.Danger else KiroColors.Muted,
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

/**
 * Insert an empty image row into MediaStore.Images and return its `content://` URI. The
 * row is created BEFORE the camera launches so (a) the camera app has somewhere to write
 * and (b) the photo shows up in the gallery automatically. Returns null if MediaStore
 * insert fails (no external storage, no permission on legacy API, etc.).
 */
private fun createCameraOutputUri(ctx: Context): Uri? = runCatching {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "KiroAgent_$stamp.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KiroAgent")
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    ctx.contentResolver.insert(collection, values)
}.onFailure {
    Log.w("MessageInput", "createCameraOutputUri failed", it)
}.getOrNull()

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
    // For image attachments we want the LLM to actually "see" the bytes, not just a
    // synthetic note saying «picture attached». Decode the image, downscale it to a
    // reasonable max edge, JPEG-encode it, and embed the result as a base64 data URI.
    // For non-image attachments we keep the raw `content://` URI as a reference; the
    // agent gets a one-line note via [ChatViewModel.buildInstructionText].
    val dataUri = if (kind == AttachmentKind.IMAGE) {
        encodeImageAsDataUri(ctx, uri) ?: uri.toString()
    } else {
        uri.toString()
    }
    return UiAttachment(
        id = "att-${System.currentTimeMillis()}-${(0..1_000_000).random()}",
        name = displayName,
        mimeType = mime,
        sizeBytes = size,
        kind = kind,
        dataUri = dataUri,
    )
}

/**
 * Convert a `content://` (or `file://`) image picked by the user into an inline
 * `data:image/...;base64,…` URI so the LLM can actually see the pixels.
 *
 * The happy path decodes the file as a Bitmap, downscales the longest side to
 * [maxEdge], honours EXIF rotation, JPEG-encodes at [quality] and base64-encodes
 * the result. If anything in that path fails (PNG transparency edge cases, weird
 * URIs, decoder OOM, exotic formats, …) we fall back to reading the raw bytes
 * verbatim and serving them under the file's original MIME type. That keeps the
 * model fed with pixels even when our resize pipeline can't make sense of the
 * source — far better than silently dropping the attachment.
 *
 * Returns null only when even the raw-byte fallback fails (URI revoked,
 * SecurityException, ...). In that case the caller logs a chat system note so
 * the user sees what went wrong instead of the model claiming there's no image.
 */
private fun encodeImageAsDataUri(
    ctx: Context,
    uri: Uri,
    maxEdge: Int = 1024,
    quality: Int = 80,
): String? {
    // ---- Fast path: decode, rotate, downscale, JPEG-encode ----
    val resized = runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val w = boundsOpts.outWidth
        val h = boundsOpts.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap: Bitmap = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return@runCatching null

        val orientation = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        Log.i("MessageInput", "encoded image via resize path: ${w}x${h} -> ${scaled.width}x${scaled.height}, ${baos.size()/1024}KB JPEG")
        "data:image/jpeg;base64,$b64"
    }.onFailure { Log.w("MessageInput", "image resize path failed for $uri, falling back to raw bytes", it) }
        .getOrNull()
    if (resized != null) return resized

    // ---- Fallback: read raw bytes, base64-encode with original MIME type ----
    return runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        if (bytes.isEmpty()) return@runCatching null
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.i("MessageInput", "encoded image via raw-bytes fallback: $mime, ${bytes.size/1024}KB")
        "data:$mime;base64,$b64"
    }.onFailure { Log.e("MessageInput", "raw-bytes fallback also failed for $uri", it) }
        .getOrNull()
}
