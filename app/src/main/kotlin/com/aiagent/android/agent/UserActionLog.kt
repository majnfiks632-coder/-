package com.aiagent.android.agent

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Process-wide ring buffer of user-initiated actions captured by [com.aiagent.android.service.AgentAccessibilityService]
 * (taps, scrolls, text input, app switches) while the agent is NOT driving the UI itself.
 *
 * The agent loop reads from here at the top of each turn so the LLM can see "what the user
 * did between my replies" — the foundation of the user-action memory feature.
 *
 * Thread-safe: the AccessibilityService thread writes via [record], the agent coroutine reads
 * via [drain] / [snapshot]. All operations are guarded by the internal monitor.
 */
object UserActionLog {

    /** Hard cap. Older entries are dropped on overflow. */
    private const val MAX_ENTRIES = 200

    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Lifetime counter of accessibility events the service has actually delivered to
     * [record] — including the ones that were dropped because of suppression. Used by
     * the in-app diagnostics overlay to prove that events are reaching us at all
     * (separates "accessibility binding broken" from "everything is filtered out").
     * Reset only on app process death.
     */
    @Volatile
    var receivedEventCount: Int = 0
        private set

    /**
     * The agent flips this to `true` while it is dispatching its own gestures / setText etc.
     * AccessibilityService consults this so it doesn't log the agent's own actions as if they
     * were the user's. A short grace window (200ms) is added on the way out to absorb the
     * trailing TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_FOCUSED events that fire after the agent
     * action completes.
     */
    @Volatile
    var agentDriving: Boolean = false
        private set

    @Volatile
    private var agentDrivingUntil: Long = 0L

    /**
     * When demonstration mode is active, the user is explicitly demonstrating something to the
     * agent. Recorded actions go into a separate buffer (so they don't get drained into the
     * regular per-turn memory) and are returned wholesale at the end of the demonstration.
     */
    @Volatile
    var demonstrationActive: Boolean = false
        private set

    private val demoBuffer = ArrayDeque<Entry>()

    fun markAgentStart() {
        agentDriving = true
    }

    /** Re-enables user-action recording 200ms after the agent's last gesture. */
    fun markAgentEnd() {
        agentDrivingUntil = System.currentTimeMillis() + 200L
        agentDriving = false
    }

    /** True while the agent is dispatching, OR we are still inside the trailing grace window. */
    fun isSuppressed(): Boolean {
        if (agentDriving) return true
        return System.currentTimeMillis() < agentDrivingUntil
    }

    /** Called by the accessibility service for every event it observes, before any
     *  filtering. Used purely for the diagnostics counter ([receivedEventCount]). */
    fun bumpReceivedEvent() {
        receivedEventCount = (receivedEventCount + 1).coerceAtMost(Int.MAX_VALUE - 1)
    }

    /** Record a user-initiated action. No-op if the agent is currently driving. */
    fun record(kind: Kind, description: String, packageName: String? = null) {
        if (isSuppressed()) return
        if (description.isBlank()) return
        val entry = Entry(
            timeMs = System.currentTimeMillis(),
            kind = kind,
            description = description,
            packageName = packageName,
        )
        synchronized(lock) {
            if (demonstrationActive) {
                if (demoBuffer.size >= MAX_ENTRIES) demoBuffer.removeFirst()
                demoBuffer.addLast(entry)
            } else {
                if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
                buffer.addLast(entry)
            }
        }
    }

    /** Begin recording a user demonstration. Clears any leftover demo buffer entries. */
    fun startDemonstration() {
        synchronized(lock) {
            demoBuffer.clear()
            demonstrationActive = true
        }
    }

    /** Stop the demonstration and return all collected entries (oldest first). */
    fun stopDemonstrationAndDrain(): List<Entry> {
        synchronized(lock) {
            demonstrationActive = false
            if (demoBuffer.isEmpty()) return emptyList()
            val copy = demoBuffer.toList()
            demoBuffer.clear()
            return copy
        }
    }

    /** Number of buffered entries. Used by the demonstration overlay's live counter. */
    fun peekCount(includeAll: Boolean = false): Int = synchronized(lock) {
        if (includeAll && demonstrationActive) demoBuffer.size else buffer.size
    }

    /** Pull all entries and clear the buffer. Returns oldest-first. */
    fun drain(): List<Entry> {
        synchronized(lock) {
            if (buffer.isEmpty()) return emptyList()
            val copy = buffer.toList()
            buffer.clear()
            return copy
        }
    }

    /** Read the last [limit] entries WITHOUT clearing. */
    fun snapshot(limit: Int = MAX_ENTRIES): List<Entry> {
        synchronized(lock) {
            if (buffer.isEmpty()) return emptyList()
            val list = buffer.toList()
            return if (list.size <= limit) list else list.subList(list.size - limit, list.size)
        }
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    /** Render entries as a numbered Russian-friendly list for injection into the LLM context. */
    fun renderForPrompt(entries: List<Entry>): String {
        if (entries.isEmpty()) return ""
        return entries.mapIndexed { i, e ->
            val t = timeFmt.format(Date(e.timeMs))
            val pkg = e.packageName?.let { " [$it]" } ?: ""
            "${i + 1}. [$t]$pkg ${e.description}"
        }.joinToString("\n")
    }

    enum class Kind {
        TAP,
        LONG_TAP,
        SCROLL,
        TEXT,
        WINDOW,
        OTHER,
    }

    data class Entry(
        val timeMs: Long,
        val kind: Kind,
        val description: String,
        val packageName: String?,
    )
}
