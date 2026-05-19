package com.aiagent.android.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Bridges the agent's "share this file with the user" need to Android's share sheet so the
 * user can email/Telegram/save it from outside the app. The user explicitly asked:
 *   «добавь редактирование файлов и предоставление пользователю».
 *
 * Two delivery paths are supported:
 *   - `path` is an absolute filesystem path: wrap it with a [FileProvider] URI so other apps
 *     can read it (Android forbids raw `file://` URIs in cross-app intents on N+).
 *   - `path` is a `content://` URI: pass through as-is.
 *
 * The caller is `Agent.run`, which then writes a result line back to the chat saying the
 * share sheet was opened. The actual delivery decision (email vs Telegram vs Drive vs Save) is
 * left to the user — we never try to pick a target app on their behalf.
 */
object FileShareHelper {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Pop the system share sheet for [pathOrUri]. Returns a human-readable status string
     * suitable for sending back to the LLM as a tool result.
     *
     * Note: the share sheet is started with [Intent.FLAG_ACTIVITY_NEW_TASK] so it works even
     * from a non-activity context (e.g. when the agent is running in the background).
     */
    fun share(context: Context, pathOrUri: String, mimeTypeHint: String? = null): String {
        val ctx = context.applicationContext
        val uri = resolveUri(ctx, pathOrUri) ?: return "ошибка: файл не найден: $pathOrUri"
        val mime = mimeTypeHint?.takeIf { it.isNotBlank() } ?: guessMime(uri.toString())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Поделиться файлом").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(chooser)
            "ок: открыт диалог «Поделиться» для $pathOrUri (mime=$mime)"
        }.getOrElse { e ->
            "ошибка: не удалось открыть диалог «Поделиться»: ${e.message ?: e::class.java.simpleName}"
        }
    }

    /**
     * Like [share] but invokes the system "Open with…" picker (ACTION_VIEW) so the user can
     * preview the file. Useful when the agent wants to surface a freshly generated PDF / image
     * for inspection without going through the share sheet.
     */
    fun open(context: Context, pathOrUri: String, mimeTypeHint: String? = null): String {
        val ctx = context.applicationContext
        val uri = resolveUri(ctx, pathOrUri) ?: return "ошибка: файл не найден: $pathOrUri"
        val mime = mimeTypeHint?.takeIf { it.isNotBlank() } ?: guessMime(uri.toString())
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(Intent.createChooser(intent, "Открыть файл").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ок: открыт диалог «Открыть» для $pathOrUri (mime=$mime)"
        }.getOrElse { e ->
            "ошибка: не удалось открыть «Открыть»: ${e.message ?: e::class.java.simpleName}"
        }
    }

    private fun resolveUri(ctx: Context, pathOrUri: String): Uri? {
        if (pathOrUri.startsWith("content://")) return Uri.parse(pathOrUri)
        if (pathOrUri.startsWith("file://")) {
            // Re-wrap with FileProvider on N+ where exposing file:// URIs throws FileUriExposedException.
            if (Build.VERSION.SDK_INT >= 24) {
                val path = Uri.parse(pathOrUri).path ?: return null
                return wrapWithFileProvider(ctx, File(path))
            }
            return Uri.parse(pathOrUri)
        }
        // Assume bare filesystem path.
        val file = File(pathOrUri)
        if (!file.exists()) return null
        return wrapWithFileProvider(ctx, file)
    }

    private fun wrapWithFileProvider(ctx: Context, file: File): Uri? {
        val authority = ctx.packageName + AUTHORITY_SUFFIX
        return runCatching { FileProvider.getUriForFile(ctx, authority, file) }.getOrNull()
    }

    private fun guessMime(uriOrPath: String): String {
        val ext = uriOrPath.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
