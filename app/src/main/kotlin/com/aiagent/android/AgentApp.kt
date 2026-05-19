package com.aiagent.android

import android.app.Application
import com.aiagent.android.util.AppForegroundTracker

/**
 * Application subclass — wires the [AppForegroundTracker] so every component in the app
 * (overlays, agent loop, chat VM) can ask "is the user looking at our UI right now".
 *
 * The user explicitly asked for floating overlays to be hidden whenever the app is on
 * screen OR device control is disabled; without a process-wide foreground signal we'd
 * have no way to do that reliably.
 */
class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppForegroundTracker)
    }
}
