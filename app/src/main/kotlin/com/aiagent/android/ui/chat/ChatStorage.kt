package com.aiagent.android.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Local-first persistence for the Kiro chat: mirrors what `src/lib/storage.ts`
 * does in the original Next.js project, but backed by SharedPreferences +
 * kotlinx-serialization instead of localStorage.
 *
 *   - `kiro.messages`   →  List<SerializableMessage>
 *   - `kiro.model`      →  String
 *   - `kiro.usage`      →  SerializableUsage
 *   - `kiro.quotaCap`   →  Int
 */
class ChatStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SerializableMessage>>(raw).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val payload = json.encodeToString(messages.map(SerializableMessage::from))
        prefs.edit { putString(KEY_MESSAGES, payload) }
    }

    fun loadModel(default: String): String =
        prefs.getString(KEY_MODEL, default) ?: default

    fun saveModel(model: String) {
        prefs.edit { putString(KEY_MODEL, model) }
    }

    fun loadUsage(): UsageStats {
        val raw = prefs.getString(KEY_USAGE, null) ?: return UsageStats()
        return runCatching {
            json.decodeFromString<SerializableUsage>(raw).toDomain()
        }.getOrDefault(UsageStats())
    }

    fun saveUsage(usage: UsageStats) {
        prefs.edit { putString(KEY_USAGE, json.encodeToString(SerializableUsage.from(usage))) }
    }

    fun loadQuotaCap(): Int = prefs.getInt(KEY_QUOTA, 0)
    fun saveQuotaCap(value: Int) {
        prefs.edit { putInt(KEY_QUOTA, value.coerceAtLeast(0)) }
    }

    fun clearAll() {
        prefs.edit {
            remove(KEY_MESSAGES)
            remove(KEY_USAGE)
        }
    }

    private companion object {
        const val PREFS_NAME = "kiro.chat"
        const val KEY_MESSAGES = "kiro.messages"
        const val KEY_MODEL = "kiro.model"
        const val KEY_USAGE = "kiro.usage"
        const val KEY_QUOTA = "kiro.quotaCap"
    }
}

@Serializable
private data class SerializableMessage(
    val id: String,
    val role: String,
    val content: String = "",
    val attachments: List<SerializableAttachment> = emptyList(),
    val toolEvents: List<SerializableToolEvent> = emptyList(),
    val usage: SerializableTurnUsage? = null,
    val model: String? = null,
    val pending: Boolean = false,
    val error: String? = null,
    val doneSummary: String? = null,
    val createdAt: Long = 0L,
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = when (role) {
            "user" -> ChatRole.USER
            "system" -> ChatRole.SYSTEM
            else -> ChatRole.ASSISTANT
        },
        content = content,
        attachments = attachments.map { it.toDomain() },
        toolEvents = toolEvents.map { it.toDomain() },
        usage = usage?.toDomain(),
        model = model,
        pending = false, // persisted messages are never still-streaming after a relaunch
        error = error,
        doneSummary = doneSummary,
        createdAt = createdAt,
    )

    companion object {
        fun from(m: ChatMessage): SerializableMessage = SerializableMessage(
            id = m.id,
            role = when (m.role) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.SYSTEM -> "system"
            },
            content = m.content,
            attachments = m.attachments.map(SerializableAttachment::from),
            toolEvents = m.toolEvents.map(SerializableToolEvent::from),
            usage = m.usage?.let(SerializableTurnUsage::from),
            model = m.model,
            pending = false,
            error = m.error,
            doneSummary = m.doneSummary,
            createdAt = m.createdAt,
        )
    }
}

@Serializable
private data class SerializableAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: String,
    val dataUri: String,
) {
    fun toDomain(): UiAttachment = UiAttachment(
        id = id,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        kind = if (kind == "image") AttachmentKind.IMAGE else AttachmentKind.FILE,
        dataUri = dataUri,
    )

    companion object {
        fun from(a: UiAttachment): SerializableAttachment = SerializableAttachment(
            id = a.id,
            name = a.name,
            mimeType = a.mimeType,
            sizeBytes = a.sizeBytes,
            kind = if (a.kind == AttachmentKind.IMAGE) "image" else "file",
            dataUri = a.dataUri,
        )
    }
}

@Serializable
private data class SerializableToolEvent(
    val id: String,
    val name: String,
    val arguments: String,
    val summary: String,
    val createdAt: Long = 0L,
) {
    fun toDomain(): ToolEvent = ToolEvent(id, name, arguments, summary, createdAt)
    companion object {
        fun from(t: ToolEvent): SerializableToolEvent =
            SerializableToolEvent(t.id, t.name, t.arguments, t.summary, t.createdAt)
    }
}

@Serializable
private data class SerializableTurnUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    fun toDomain(): TurnUsage = TurnUsage(promptTokens, completionTokens, totalTokens)
    companion object {
        fun from(u: TurnUsage): SerializableTurnUsage =
            SerializableTurnUsage(u.promptTokens, u.completionTokens, u.totalTokens)
    }
}

@Serializable
private data class SerializableUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val messages: Int = 0,
    val byModel: Map<String, Int> = emptyMap(),
) {
    fun toDomain(): UsageStats =
        UsageStats(promptTokens, completionTokens, totalTokens, messages, byModel)
    companion object {
        fun from(u: UsageStats): SerializableUsage = SerializableUsage(
            u.promptTokens, u.completionTokens, u.totalTokens, u.messages, u.byModel,
        )
    }
}
