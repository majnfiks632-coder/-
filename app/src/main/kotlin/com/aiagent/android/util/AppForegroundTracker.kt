package com.aiagent.android.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide tracker of whether any activity of the app is currently in the foreground.
 *
 * Registered as an [Application.ActivityLifecycleCallbacks] inside [com.aiagent.android.AgentApp].
 * Other components (overlay services, agent loop, …) check [isAppForeground] before deciding
 * whether to render floating overlays.
 *
 * The user explicitly asked the floating buttons to disappear when the app is on screen:
 *   «Плавающие кнопки убрать только когда ты в приложении или я не хочу чтобы он управлял
 *    телефоном.»
 *
 * Implementation is the canonical "started activity count" approach: start ⇒ increment,
 * stop ⇒ decrement. The app is foreground iff the counter > 0. No timers / no leaks.
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    /** Snapshot read by overlay services / the ChatViewModel. */
    val isAppForeground: Boolean
        get() = startedActivities.get() > 0

    /** Convenience helper for components that want to express "render this overlay only when
     *  the user is NOT looking at the chat screen". */
    val isAppBackground: Boolean
        get() = startedActivities.get() == 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
        notifyListeners()
    }

    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        startedActivities.decrementAndGet()
        notifyListeners()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    // Pull-style observers. We deliberately don't use Flow here to keep this file dependency-free
    // for unit tests; overlay services poll on a short timer (cheap) or react to lifecycle
    // changes via [addListener].
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    @Synchronized
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        // Fire current state immediately so callers don't race.
        listener(isAppForeground)
    }

    @Synchronized
    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun notifyListeners() {
        val foreground = isAppForeground
        for (l in listeners.toList()) {
            runCatching { l(foreground) }
        }
    }
}
