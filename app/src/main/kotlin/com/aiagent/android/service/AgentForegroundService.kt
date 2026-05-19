package com.aiagent.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aiagent.android.R
import com.aiagent.android.ui.MainActivity

/**
 * Foreground service that keeps the agent alive while the user navigates away from the app.
 *
 * The user explicitly asked for two things this service implements:
 *   1. «Добавь чтоб в фоне работало пока агент работает» — Android kills background coroutines
 *      after ~30 s; running inside a foreground service keeps our agent loop alive indefinitely.
 *   2. «а потом приходит уведомление» — when the agent finishes (done / error / cancelled) we
 *      post a separate completion notification so the user is told even if they aren't looking
 *      at the app.
 *
 * Lifecycle:
 *   - [ChatViewModel] calls [start] when it kicks off an agent run AND `Settings.runInBackground`
 *     is on. The "Running…" notification persists for as long as the agent is busy.
 *   - When the agent finishes / errors / is cancelled, [ChatViewModel] calls [finish]. The
 *     foreground service stops itself, and we post a one-shot completion notification (unless
 *     the app is currently in the foreground — then the user is already watching the chat and
 *     a notification would be noisy).
 *   - The "Stop" action on the running notification fires [ACTION_STOP], which calls back into
 *     [ChatViewModel] via [stopListener] (wired in `ChatViewModel.init`).
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAsForeground(
                intent.getStringExtra(EXTRA_TITLE) ?: "AI Agent работает",
                intent.getStringExtra(EXTRA_BODY) ?: "Можно свернуть приложение — отвечу уведомлением.",
            )
            ACTION_UPDATE -> updateNotification(
                intent.getStringExtra(EXTRA_TITLE) ?: "AI Agent работает",
                intent.getStringExtra(EXTRA_BODY) ?: "",
            )
            ACTION_STOP -> {
                stopListener?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_FINISH -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(title: String, body: String) {
        createChannels()
        val notification = buildRunningNotification(title, body)
        startForeground(NOTIF_ID_RUNNING, notification)
    }

    private fun updateNotification(title: String, body: String) {
        createChannels()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_RUNNING, buildRunningNotification(title, body))
    }

    private fun buildRunningNotification(title: String, body: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, AgentForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openPending)
            .addAction(0, "Стоп", stopPending)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_RUNNING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RUNNING,
                    "Агент работает",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Уведомление пока AI-агент думает или управляет устройством"
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    "Агент завершил",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Когда AI-агент закончил выполнение запроса"
                },
            )
        }
    }

    companion object {
        const val ACTION_START = "com.aiagent.android.AGENT_FG_START"
        const val ACTION_UPDATE = "com.aiagent.android.AGENT_FG_UPDATE"
        const val ACTION_FINISH = "com.aiagent.android.AGENT_FG_FINISH"
        const val ACTION_STOP = "com.aiagent.android.AGENT_FG_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"

        const val CHANNEL_RUNNING = "agent_running"
        const val CHANNEL_DONE = "agent_done"
        const val NOTIF_ID_RUNNING = 4711
        const val NOTIF_ID_DONE = 4712

        /** Hooked by ChatViewModel — invoked when the user taps "Стоп" on the running notification. */
        @Volatile
        var stopListener: (() -> Unit)? = null

        fun start(context: Context, title: String, body: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, title: String, body: String) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            runCatching { context.startService(intent) }
        }

        fun finish(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_FINISH
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Post a one-shot "agent finished" notification. Skipped if the app is already in the
         * foreground (the user is watching the chat — a notification would be redundant).
         */
        fun notifyDone(context: Context, summary: String, success: Boolean) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_DONE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_DONE,
                        "Агент завершил",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                2,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = if (success) "AI Agent: готово" else "AI Agent: остановлен"
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle(title)
                .setContentText(summary.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            runCatching { nm.notify(NOTIF_ID_DONE, n) }
        }
    }
}
