package com.aiagent.android.agent

import com.aiagent.android.data.Settings
import com.aiagent.android.llm.ChatBackends
import com.aiagent.android.llm.ChatRequest
import com.aiagent.android.llm.textMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fire-and-forget background "sub-agent" worker.
 *
 * Why: the user asked for the agent to be able to spawn other agents and NOT
 * wait for them ("ии создавал сабагнтов и неждал их создавал еще"). The main
 * controller calls [spawn] with a task description; this object kicks the work
 * off on its own coroutine, returns a short ID immediately, and the main loop
 * carries on. The controller can poll [list] / [get] later, or just forget
 * about it — completed sub-agents stay in memory until the process is killed
 * or [clear] is called.
 *
 * Implementation is intentionally small: each sub-agent is a single LLM chat
 * call against the active provider. No tool loop, no device access, no
 * overlays. That keeps them cheap and safe — they can't grab the
 * AccessibilityService or fight with the main agent for the screen. Think of
 * them as "go research this in parallel" workers, not full duplicates of the
 * main runtime.
 */
object SubAgentManager {

    enum class Status { RUNNING, DONE, ERROR, CANCELLED }

    data class Job(
        val id: String,
        val label: String,
        val task: String,
        @Volatile var status: Status,
        @Volatile var result: String? = null,
        @Volatile var error: String? = null,
        val startedAt: Long,
        @Volatile var finishedAt: Long? = null,
        val coroutineJob: kotlinx.coroutines.Job,
    )

    // Supervisor scope so one failing sub-agent doesn't take the rest down.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Launch a new sub-agent immediately. Returns its short ID — the caller can
     * carry on without awaiting completion.
     *
     * @param task     The natural-language task to give the sub-agent. Becomes the user
     *                 message of its conversation.
     * @param label    Optional short human-readable label so the parent can
     *                 keep track ("research weather in Paris").
     * @param settings Active settings — read for the active provider, model, and
     *                 system prompt.
     */
    fun spawn(
        settings: Settings,
        task: String,
        label: String? = null,
    ): String {
        val id = "sa_" + UUID.randomUUID().toString().take(8)
        val start = System.currentTimeMillis()
        val coJob = scope.launch {
            val client = ChatBackends.forActiveProvider(settings)
            try {
                val sysPrompt = buildString {
                    append(
                        "Ты — фоновый суб-агент, запущенный другим агентом для параллельной задачи. " +
                            "У тебя нет доступа к устройству, экрану или файлам — только к собственному размышлению " +
                            "и тому что было передано в задании. Ответь кратко, по делу, на русском (если задача " +
                            "не указывает другой язык). Не выходи за рамки задачи."
                    )
                }
                val request = ChatRequest(
                    model = settings.model,
                    messages = listOf(
                        textMessage(role = "system", text = sysPrompt),
                        textMessage(role = "user", text = task),
                    ),
                    tools = emptyList(),
                    toolChoice = null,
                    temperature = settings.temperature.toDouble(),
                    maxCompletionTokens = settings.maxTokens.takeIf { it > 0 },
                )
                val response = client.chat(request)
                val text = response.choices.firstOrNull()?.message?.contentText.orEmpty()
                val j = jobs[id] ?: return@launch
                j.result = text.ifBlank { "(пустой ответ от модели)" }
                j.status = Status.DONE
                j.finishedAt = System.currentTimeMillis()
            } catch (e: kotlinx.coroutines.CancellationException) {
                val j = jobs[id]
                if (j != null) {
                    j.status = Status.CANCELLED
                    j.finishedAt = System.currentTimeMillis()
                }
                throw e
            } catch (e: Throwable) {
                val j = jobs[id] ?: return@launch
                j.error = e.message ?: e::class.java.simpleName
                j.status = Status.ERROR
                j.finishedAt = System.currentTimeMillis()
            } finally {
                runCatching { client.close() }
            }
        }
        jobs[id] = Job(
            id = id,
            label = label?.takeIf { it.isNotBlank() } ?: task.take(40),
            task = task,
            status = Status.RUNNING,
            startedAt = start,
            coroutineJob = coJob,
        )
        return id
    }

    /** Snapshot of all jobs, newest first. */
    fun list(): List<Job> = jobs.values.sortedByDescending { it.startedAt }

    /** Look up a single job by ID, returns null if unknown. */
    fun get(id: String): Job? = jobs[id]

    /** Cancel a running sub-agent (no-op if already finished). */
    fun cancel(id: String): Boolean {
        val j = jobs[id] ?: return false
        if (j.status != Status.RUNNING) return false
        j.coroutineJob.cancel()
        return true
    }

    /** Forget about all finished jobs. Running jobs survive. */
    fun clearFinished() {
        val toRemove = jobs.values.filter { it.status != Status.RUNNING }.map { it.id }
        toRemove.forEach { jobs.remove(it) }
    }
}
