package com.aiagent.android.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.aiagent.android.data.Settings
import com.aiagent.android.stt.SpeechToText
import com.aiagent.android.agent.UserActionLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Floating overlay window used by the agent to:
 *   - ask the user a yes/no question (`SHOW_QUESTION`)
 *   - show transient status (`SHOW_STATUS`)
 *   - render a persistent **STOP button** that is the ONLY way the user can terminate the agent.
 *     The agent itself cannot exit; the model's `done` tool is logged but ignored. The button is
 *     a separate, draggable overlay window placed above all apps; the AI cannot tap it because:
 *       a) `dispatchGesture` would have to reach the overlay's window which it can but
 *       b) we publish the on-screen bounds via [stopButtonBounds] and `tap_at` / `swipe_at`
 *          refuse to dispatch when the target falls inside that rectangle.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var buttonsRow: LinearLayout? = null
    private var voiceButton: Button? = null
    private var dismissButton: Button? = null
    private var voiceJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stopRoot: View? = null
    private var settingsRoot: LinearLayout? = null
    private var settingsExpanded = false

    // "Thoughts island" — small persistent overlay that shows what the agent is doing.
    private var thoughtRoot: LinearLayout? = null
    private var thoughtTitleView: TextView? = null
    private var thoughtBodyView: TextView? = null

    // Demonstration prompt overlay (two-phase: confirm → record → done).
    private var demoRoot: LinearLayout? = null
    private var demoTitleView: TextView? = null
    private var demoBodyView: TextView? = null
    private var demoRow: LinearLayout? = null
    private var demoCounterView: TextView? = null
    private var demoCounterUpdater: Job? = null

    // Minimize state — flips between full overlay and a tiny pill the user can tap to restore.
    private var rootMinimized = false
    private var demoMinimized = false
    private var thoughtMinimized = false
    private var lastThoughtTitle: String = "AI Agent"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_QUESTION -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val opts = intent.getStringArrayExtra(EXTRA_OPTIONS)?.toList()
                showQuestion(text, opts)
            }
            ACTION_SHOW_STATUS -> showStatus(intent.getStringExtra(EXTRA_TEXT) ?: "")
            ACTION_HIDE -> hideAll()
            ACTION_SHOW_STOP -> showStopButton()
            ACTION_HIDE_STOP -> hideStopButton()
            ACTION_SHOW_SETTINGS -> showSettingsButton()
            ACTION_HIDE_SETTINGS -> hideSettingsButton()
            ACTION_SHOW_THOUGHT -> showThoughtIsland(
                intent.getStringExtra(EXTRA_THOUGHT_TITLE) ?: "ИИ думает",
                intent.getStringExtra(EXTRA_TEXT) ?: "",
            )
            ACTION_HIDE_THOUGHT -> hideThoughtIsland()
            ACTION_PULSE_TAP -> spawnTapPulse(
                x = intent.getIntExtra(EXTRA_TAP_X, -1),
                y = intent.getIntExtra(EXTRA_TAP_Y, -1),
                kind = intent.getStringExtra(EXTRA_TAP_KIND) ?: "tap",
            )
            ACTION_PULSE_SWIPE -> spawnSwipePulse(
                x1 = intent.getIntExtra(EXTRA_SWIPE_X1, -1),
                y1 = intent.getIntExtra(EXTRA_SWIPE_Y1, -1),
                x2 = intent.getIntExtra(EXTRA_SWIPE_X2, -1),
                y2 = intent.getIntExtra(EXTRA_SWIPE_Y2, -1),
            )
            ACTION_SHOW_DEMO_CONFIRM -> showDemonstrationConfirm(
                intent.getStringExtra(EXTRA_TEXT) ?: "",
            )
            ACTION_SHOW_DEMO_ACTIVE -> showDemonstrationActive()
            ACTION_HIDE_DEMO -> hideDemonstration()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureView() {
        if (rootView != null) return
        val ctx: Context = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1A237E")) // Indigo 900 — visible against most game UIs.
                setStroke(dp(2), Color.WHITE)
            }
        }

        titleView = TextView(ctx).apply {
            text = "AI Agent"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val minimizeBtn = TextView(ctx).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(minimizeBtn)
        }
        bodyView = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(8))
        }

        // Buttons row is now built dynamically per question — see populateOptions().
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttonsRow = row

        container.addView(headerRow)
        container.addView(bodyView)
        container.addView(row)

        minimizeBtn.setOnClickListener {
            rootMinimized = !rootMinimized
            applyRootMinimizedState()
        }
        // Tapping the title itself, when minimized, also restores.
        titleView?.setOnClickListener {
            if (rootMinimized) {
                rootMinimized = false
                applyRootMinimizedState()
            }
        }

        rootView = container
        attachDragHandler(container)
    }

    private fun applyRootMinimizedState() {
        val bv = bodyView
        val br = buttonsRow
        val tv = titleView
        if (rootMinimized) {
            bv?.visibility = View.GONE
            br?.visibility = View.GONE
            tv?.text = "💬 Агент (тапни)"
        } else {
            bv?.visibility = View.VISIBLE
            br?.visibility = View.VISIBLE
            tv?.text = "AI Agent"
        }
    }

    /**
     * Render the answer buttons for a question. If [options] is null/empty we fall back to
     * the classic Да / Нет / 🎤 / ✕ row. With options, each becomes a labeled button (truncated
     * to ~16 chars). The mic button always gets shown — the user can answer by voice without
     * leaving the game.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun populateOptions(options: List<String>?) {
        val ctx: Context = this
        val row = buttonsRow ?: return
        row.removeAllViews()
        val effective = options?.take(6)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (effective.isEmpty()) {
            // Default yes/no flow.
            row.addView(makeRowChild(makeButton(ctx, "Да", Color.parseColor("#1B5E20")) { deliver("yes") }))
            row.addView(makeRowChild(makeButton(ctx, "Нет", Color.parseColor("#B71C1C")) { deliver("no") }))
        } else {
            for ((idx, opt) in effective.withIndex()) {
                val short = if (opt.length > 22) opt.take(20) + "…" else opt
                val color = optionColor(idx)
                row.addView(makeRowChild(makeButton(ctx, short, color) { deliver(opt) }))
            }
        }
        voiceButton = makeButton(ctx, "🎤", Color.parseColor("#4527A0")) { startVoiceAnswer() }
        dismissButton = makeButton(ctx, "✕", Color.parseColor("#424242")) {
            cancelVoiceAnswer()
            deliver("dismiss")
            hideAll()
        }
        row.addView(makeRowChild(voiceButton!!))
        row.addView(makeRowChild(dismissButton!!))
    }

    private fun makeRowChild(btn: Button): Button {
        btn.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { marginEnd = dp(4) }
        return btn
    }

    private fun optionColor(index: Int): Int = when (index % 6) {
        0 -> Color.parseColor("#1B5E20") // green
        1 -> Color.parseColor("#0D47A1") // blue
        2 -> Color.parseColor("#4A148C") // purple
        3 -> Color.parseColor("#BF360C") // orange-red
        4 -> Color.parseColor("#1B5E20") // green again
        else -> Color.parseColor("#37474F") // blue grey
    }

    private fun startVoiceAnswer() {
        val current = voiceJob
        if (current?.isActive == true) {
            // Tap-to-cancel.
            cancelVoiceAnswer()
            voiceButton?.text = "🎤"
            return
        }
        voiceButton?.text = "● слушаю"
        bodyView?.append("\n\n[слушаю — говори…]")
        voiceJob = serviceScope.launch {
            val stt = SpeechToText(applicationContext, Settings(applicationContext))
            val transcript = try {
                stt.listenLive(language = null)
            } catch (e: Exception) {
                "[ошибка распознавания: ${e.message ?: e::class.java.simpleName}]"
            }
            voiceButton?.text = "🎤"
            if (transcript.isNotBlank() && !transcript.startsWith("[")) {
                deliver(transcript)
            } else {
                bodyView?.append("\n$transcript — попробуй ещё раз")
            }
        }
    }

    private fun cancelVoiceAnswer() {
        voiceJob?.cancel()
        voiceJob = null
    }

    private fun makeButton(ctx: Context, text: String, bg: Int, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isAllCaps = false
            setBackgroundColor(bg)
            setOnClickListener(onClick)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(container: View) {
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        container.setOnTouchListener { _, ev ->
            val params = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    rawX = ev.rawX
                    rawY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - rawX).toInt()
                    params.y = startY + (ev.rawY - rawY).toInt()
                    runCatching { windowManager?.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun showQuestion(text: String, options: List<String>?) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        rootMinimized = false
        applyRootMinimizedState()
        titleView?.text = "Агент спрашивает"
        bodyView?.text = text
        populateOptions(options)
        buttonsRow?.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        ensureView()
        val view = rootView ?: return
        if (view.parent == null) attach(view)
        rootMinimized = false
        applyRootMinimizedState()
        titleView?.text = "AI Agent"
        bodyView?.text = text
        // For status-only display we hide the buttons row.
        buttonsRow?.visibility = View.GONE
    }

    private fun attach(view: View) {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.7f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(60)
        }
        val alpha = Settings(this).overlayAlpha.coerceIn(0.1f, 1.0f)
        view.alpha = alpha
        runCatching { windowManager?.addView(view, params) }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showStopButton() {
        if (stopRoot != null) return
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val btn = Button(ctx).apply {
            text = "🛑 СТОП"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#C62828"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener {
                // Fire the registered ViewModel callback. The button stays visible until the
                // ViewModel asks us to hide it (after it has cancelled the agent job).
                runCatching { stopListener?.invoke() }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(80)
        }

        // Make the stop button draggable too — user requested "configurable".
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        var dragged = false
        btn.setOnTouchListener { _, ev ->
            val lp = btn.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = ev.rawX; rawY = ev.rawY; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {
                        dragged = true
                        // For Gravity.TOP|END, x grows toward the LEFT edge.
                        lp.x = (startX - dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(btn, lp) }
                        updateStopButtonBounds(btn)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) true else false // consume only if dragged, else let click fire
                }
                else -> false
            }
        }
        runCatching { windowManager?.addView(btn, params) }
        stopRoot = btn
        // Capture bounds once layout is done.
        btn.post { updateStopButtonBounds(btn) }
    }

    private fun updateStopButtonBounds(view: View) {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        stopButtonBounds = Rect(
            loc[0],
            loc[1],
            loc[0] + view.width,
            loc[1] + view.height,
        )
    }

    private fun hideStopButton() {
        val v = stopRoot
        if (v != null) {
            runCatching { windowManager?.removeView(v) }
        }
        stopRoot = null
        stopButtonBounds = null
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showSettingsButton() {
        // Re-show is a no-op if the view is still attached to a window.
        val existing = settingsRoot
        if (existing != null && existing.windowToken != null) return
        // If we still hold a stale reference (e.g. addView failed last time, or
        // panic-shutdown removed the view but our static field never got cleared)
        // wipe it so we build a fresh one this turn.
        if (existing != null) {
            runCatching { windowManager?.removeView(existing) }
            settingsRoot = null
            settingsExpanded = false
        }
        // Without SYSTEM_ALERT_WINDOW we can't draw over other apps. Surface that with
        // a Toast so the user doesn't think the toggle is broken.
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Нет разрешения «Поверх других приложений». Открой Настройки → AI Agent → Разрешения.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EE263238"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val toggleBtn = Button(ctx).apply {
            text = "⚙️"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#5C6BC0"))
                setStroke(dp(2), Color.WHITE)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        rebuildSettingsPanel(panel)

        toggleBtn.setOnClickListener {
            settingsExpanded = !settingsExpanded
            panel.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
            if (settingsExpanded) rebuildSettingsPanel(panel)
        }
        container.addView(toggleBtn)
        container.addView(panel)

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(80)
        }

        // Drag the whole settings widget by long-press on the ⚙️ button.
        var startX = 0
        var startY = 0
        var rawX = 0f
        var rawY = 0f
        var dragged = false
        toggleBtn.setOnTouchListener { _, ev ->
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; rawX = ev.rawX; rawY = ev.rawY; dragged = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rawX).toInt()
                    val dy = (ev.rawY - rawY).toInt()
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) {
                        dragged = true
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(container, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> if (dragged) true else false
                else -> false
            }
        }

        val added = runCatching { windowManager?.addView(container, params) }
        if (added.isFailure) {
            val msg = added.exceptionOrNull()?.message ?: "addView failed"
            android.widget.Toast.makeText(
                this,
                "Не удалось показать ⚙-окно: $msg",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        settingsRoot = container
    }

    private fun rebuildSettingsPanel(panel: LinearLayout) {
        panel.removeAllViews()
        val s = Settings(this)
        addDiagnosticsLine(panel)
        // Voice → start agent button. The most-used action when in-game.
        addStartAgentButton(panel)
        addSettingsToggle(panel, "🕹️ Джойстик", s.joystickEnabled) { value ->
            s.joystickEnabled = value
            if (value) JoystickOverlayService.show(applicationContext)
            else JoystickOverlayService.hide(applicationContext)
        }
        addSettingsToggle(panel, "👁️ Двухмодельный режим", s.useVisionDescriber) { value ->
            s.useVisionDescriber = value
        }
        addSettingsToggle(panel, "📸 Авто-скриншот", s.autoScreenshotEachTurn) { value ->
            s.autoScreenshotEachTurn = value
        }
        addSettingsToggle(panel, "⏸️ Пауза по «done»", s.autoPauseOnIdle) { value ->
            s.autoPauseOnIdle = value
        }
        addSettingsToggle(panel, "🎮 Передавать жест в игру", s.joystickDispatch) { value ->
            s.joystickDispatch = value
        }
        addDeadInsideButton(panel)
    }

    @SuppressLint("SetTextI18n")
    private fun addDeadInsideButton(parent: LinearLayout) {
        val ctx: Context = this
        var armed = false
        val btn = Button(ctx).apply {
            text = "💀 Дед инсайд — всё выключить"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#212121"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        btn.setOnClickListener {
            if (!armed) {
                armed = true
                btn.text = "Точно? Тапни ещё раз"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#880E4F"))
                // Auto-disarm in 4 seconds if the user changes their mind.
                serviceScope.launch {
                    delay(4000)
                    if (armed) {
                        armed = false
                        btn.text = "💀 Дед инсайд — всё выключить"
                        (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#212121"))
                    }
                }
            } else {
                armed = false
                runCatching { panicListener?.invoke() }
            }
        }
        parent.addView(btn)
    }

    private var diagnosticsUpdater: Job? = null

    @SuppressLint("SetTextI18n")
    private fun addDiagnosticsLine(parent: LinearLayout) {
        val ctx: Context = this
        val tv = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#33000000"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        parent.addView(tv)
        diagnosticsUpdater?.cancel()
        diagnosticsUpdater = serviceScope.launch {
            while (isActive) {
                val a11yRunning = com.aiagent.android.service.AgentAccessibilityService.isRunning()
                val events = UserActionLog.receivedEventCount
                val buffered = UserActionLog.peekCount(includeAll = false)
                val recordToggle = Settings(applicationContext).recordUserActions
                val a11yIcon = if (a11yRunning) "🟢" else "🔴"
                val recIcon = if (recordToggle) "🟢" else "🔴"
                tv.text =
                    "$a11yIcon Спецвозм.: ${if (a11yRunning) "подключена" else "ВЫКЛ"}  $recIcon Запись: ${if (recordToggle) "ВКЛ" else "ВЫКЛ"}\n" +
                        "📡 Событий получено: $events    📝 В памяти: $buffered"
                delay(500)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addStartAgentButton(parent: LinearLayout) {
        val ctx: Context = this
        val btn = Button(ctx).apply {
            text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#4527A0"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }
        var listening = false
        var listenScope: CoroutineScope? = null
        btn.setOnClickListener {
            if (listening) {
                listenScope?.coroutineContext?.get(Job)?.cancel()
                listening = false
                btn.text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#4527A0"))
                return@setOnClickListener
            }
            listening = true
            btn.text = "● слушаю…  (тапни чтоб отменить)"
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#C62828"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            listenScope = scope
            scope.launch {
                val transcript = try {
                    SpeechToText(applicationContext, Settings(applicationContext)).listenLive(language = null)
                } catch (e: Exception) {
                    "[ошибка распознавания: ${e.message ?: e::class.java.simpleName}]"
                }
                listening = false
                btn.text = if (agentRunning) "🎤 Сказать (продолжить)" else "🎤 Сказать задачу"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#4527A0"))
                if (transcript.isNotBlank() && !transcript.startsWith("[")) {
                    runCatching { startListener?.invoke(transcript) }
                }
            }
        }
        parent.addView(btn)
    }

    private fun addSettingsToggle(
        parent: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val ctx: Context = this
        var current = initial
        val btn = Button(ctx).apply {
            text = renderToggle(label, current)
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (current) Color.parseColor("#388E3C") else Color.parseColor("#616161"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(4)
            layoutParams = lp
        }
        btn.setOnClickListener {
            current = !current
            onChange(current)
            btn.text = renderToggle(label, current)
            (btn.background as? GradientDrawable)?.setColor(
                if (current) Color.parseColor("#388E3C") else Color.parseColor("#616161"),
            )
        }
        parent.addView(btn)
    }

    private fun renderToggle(label: String, value: Boolean): String =
        if (value) "$label  ✓" else "$label  —"

    private fun hideSettingsButton() {
        diagnosticsUpdater?.cancel()
        diagnosticsUpdater = null
        val v = settingsRoot
        if (v != null) runCatching { windowManager?.removeView(v) }
        settingsRoot = null
        settingsExpanded = false
    }

    private fun hideAll() {
        cancelVoiceAnswer()
        val view = rootView
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        titleView = null
        bodyView = null
        buttonsRow = null
        voiceButton = null
        dismissButton = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideAll()
        hideStopButton()
        hideSettingsButton()
        hideThoughtIsland()
        hideDemonstration()
    }

    // ============================================================================================
    // Thoughts island — small persistent overlay that shows what the agent is currently doing.
    // ============================================================================================

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showThoughtIsland(title: String, body: String) {
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var root = thoughtRoot
        if (root == null) {
            root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#CC1B1B1B"))
                    setStroke(dp(2), Color.parseColor("#7C4DFF"))
                }
            }
            thoughtTitleView = TextView(ctx).apply {
                setTextColor(Color.parseColor("#B388FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val thoughtMinimizeBtn = TextView(ctx).apply {
                text = "—"
                setTextColor(Color.parseColor("#B388FF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(10), 0, dp(10), 0)
            }
            val thoughtHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(thoughtTitleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(thoughtMinimizeBtn)
            }
            thoughtBodyView = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(2), 0, 0)
                maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
            }
            root.addView(thoughtHeader)
            root.addView(thoughtBodyView)
            thoughtMinimizeBtn.setOnClickListener {
                thoughtMinimized = !thoughtMinimized
                applyThoughtMinimizedState()
            }
            thoughtTitleView?.setOnClickListener {
                if (thoughtMinimized) {
                    thoughtMinimized = false
                    applyThoughtMinimizedState()
                }
            }
            attachDragHandler(root)

            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            val params = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(140)
            }
            runCatching { windowManager?.addView(root, params) }
            thoughtRoot = root
        }
        lastThoughtTitle = title
        if (thoughtMinimized) {
            thoughtTitleView?.text = "🧠"
        } else {
            thoughtTitleView?.text = title
        }
        thoughtBodyView?.text = body.ifBlank { "…" }
    }

    private fun applyThoughtMinimizedState() {
        val bv = thoughtBodyView
        val tv = thoughtTitleView
        if (thoughtMinimized) {
            bv?.visibility = View.GONE
            tv?.text = "🧠"
        } else {
            bv?.visibility = View.VISIBLE
            tv?.text = lastThoughtTitle.ifBlank { "AI Agent" }
        }
    }

    private fun hideThoughtIsland() {
        val v = thoughtRoot
        if (v != null) runCatching { windowManager?.removeView(v) }
        thoughtRoot = null
        thoughtTitleView = null
        thoughtBodyView = null
    }

    // ============================================================================================
    // Tap / swipe visualisation — transient pulse at the screen coords the agent is touching.
    // ============================================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun spawnTapPulse(x: Int, y: Int, kind: String) {
        if (x < 0 || y < 0) return
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wm = windowManager ?: return
        val color = when (kind) {
            "long" -> Color.parseColor("#FFD600") // amber for long-press
            "swipe" -> Color.parseColor("#00E5FF") // cyan for swipe waypoint
            else -> Color.parseColor("#FF1744") // red for tap
        }
        val sizeDp = 48
        val sizePx = dp(sizeDp)
        val view = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), color)
                setColor(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)))
            }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - sizePx / 2
            this.y = y - sizePx / 2
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) return
        val duration = if (kind == "long") 700L else 450L
        val scaleUp = ObjectAnimator.ofFloat(view, "scaleX", 0.3f, 1.6f)
        val scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 0.3f, 1.6f)
        val fade = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
        AnimatorSet().apply {
            playTogether(scaleUp, scaleUpY, fade)
            this.duration = duration
            start()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { wm.removeView(view) }
        }, duration + 50L)
    }

    private fun spawnSwipePulse(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (x1 < 0 || x2 < 0) return
        spawnTapPulse(x1, y1, "swipe")
        Handler(Looper.getMainLooper()).postDelayed({
            spawnTapPulse(x2, y2, "swipe")
        }, 180L)
    }

    // ============================================================================================
    // Demonstration prompt — two-phase user demonstration capture for the agent's tool.
    // ============================================================================================

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showDemonstrationConfirm(prompt: String) {
        ensureDemoView()
        demoMinimized = false
        applyDemoMinimizedState()
        demoTitleView?.text = "ИИ просит показать"
        demoBodyView?.text = if (prompt.isBlank()) "Покажи, я запишу твои действия." else prompt
        demoCounterView?.visibility = View.GONE
        cancelDemoCounter()
        renderDemoButtons(activePhase = false)
    }

    @SuppressLint("SetTextI18n")
    private fun showDemonstrationActive() {
        ensureDemoView()
        demoMinimized = false
        applyDemoMinimizedState()
        demoTitleView?.text = "🔴 Записываю твои действия"
        demoBodyView?.text = "Покажи как, потом нажми «Готово». Всё что ты жмёшь идёт в память ИИ."
        demoCounterView?.visibility = View.VISIBLE
        renderDemoButtons(activePhase = true)
        startDemoCounter()
    }

    private fun applyDemoMinimizedState() {
        val bv = demoBodyView
        val cv = demoCounterView
        val rw = demoRow
        val tv = demoTitleView
        if (demoMinimized) {
            bv?.visibility = View.GONE
            cv?.visibility = View.GONE
            rw?.visibility = View.GONE
            tv?.text = "🎬 Демо (тапни)"
        } else {
            bv?.visibility = View.VISIBLE
            rw?.visibility = View.VISIBLE
            // counter visibility is managed by the active phase, not by minimize state.
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureDemoView() {
        if (demoRoot != null) {
            if (demoRoot?.parent == null) attachDemoView(demoRoot!!)
            return
        }
        val ctx: Context = this
        windowManager = windowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E64A19"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        demoTitleView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val demoMinimizeBtn = TextView(ctx).apply {
            text = "—"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
        val demoHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(demoTitleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(demoMinimizeBtn)
        }
        demoBodyView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(8))
        }
        demoCounterView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFCC80"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
        }
        demoRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        container.addView(demoHeader)
        container.addView(demoBodyView)
        container.addView(demoCounterView)
        container.addView(demoRow)
        demoMinimizeBtn.setOnClickListener {
            demoMinimized = !demoMinimized
            applyDemoMinimizedState()
        }
        demoTitleView?.setOnClickListener {
            if (demoMinimized) {
                demoMinimized = false
                applyDemoMinimizedState()
            }
        }
        demoRoot = container
        attachDragHandler(container)
        attachDemoView(container)
    }

    private fun attachDemoView(view: View) {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.78f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(60)
        }
        view.alpha = Settings(this).overlayAlpha.coerceIn(0.5f, 1.0f)
        runCatching { windowManager?.addView(view, params) }
    }

    private fun renderDemoButtons(activePhase: Boolean) {
        val ctx: Context = this
        val row = demoRow ?: return
        row.removeAllViews()
        if (!activePhase) {
            row.addView(
                makeRowChild(
                    makeButton(ctx, "Готов показывать", Color.parseColor("#2E7D32")) {
                        DemoPending.deferred?.takeIf { !it.isCompleted }?.complete("ready")
                    },
                ),
            )
            row.addView(
                makeRowChild(
                    makeButton(ctx, "Отменить", Color.parseColor("#424242")) {
                        DemoPending.deferred?.takeIf { !it.isCompleted }?.complete("cancel")
                    },
                ),
            )
        } else {
            row.addView(
                makeRowChild(
                    makeButton(ctx, "Готово", Color.parseColor("#1565C0")) {
                        DemoPending.deferred?.takeIf { !it.isCompleted }?.complete("done")
                    },
                ),
            )
            row.addView(
                makeRowChild(
                    makeButton(ctx, "Отменить", Color.parseColor("#424242")) {
                        DemoPending.deferred?.takeIf { !it.isCompleted }?.complete("cancel")
                    },
                ),
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startDemoCounter() {
        cancelDemoCounter()
        demoCounterUpdater = serviceScope.launch {
            while (isActive) {
                val count = UserActionLog.peekCount(includeAll = true)
                demoCounterView?.text = "Действий записано: $count"
                delay(300L)
            }
        }
    }

    private fun cancelDemoCounter() {
        demoCounterUpdater?.cancel()
        demoCounterUpdater = null
    }

    private fun hideDemonstration() {
        cancelDemoCounter()
        val v = demoRoot
        if (v != null) runCatching { windowManager?.removeView(v) }
        demoRoot = null
        demoTitleView = null
        demoBodyView = null
        demoRow = null
        demoCounterView = null
    }

    private fun deliver(value: String) {
        val def = Pending.deferred
        if (def != null && !def.isCompleted) {
            def.complete(value)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Singleton bridge between the agent (caller) and the OverlayService (UI).
     *
     * Set [deferred] before starting the service; the user's button choice will be delivered
     * into it. Cleared when the agent reads the value.
     */
    object Pending {
        @Volatile
        var deferred: CompletableDeferred<String>? = null
    }

    /** Channel for the demonstration overlay. Separate from [Pending] so it doesn't fight the
     *  question overlay if both happen to be live. */
    object DemoPending {
        @Volatile
        var deferred: CompletableDeferred<String>? = null
    }

    companion object {
        const val ACTION_SHOW_QUESTION = "com.aiagent.android.OVERLAY_QUESTION"
        const val ACTION_SHOW_STATUS = "com.aiagent.android.OVERLAY_STATUS"
        const val ACTION_HIDE = "com.aiagent.android.OVERLAY_HIDE"
        const val ACTION_SHOW_STOP = "com.aiagent.android.OVERLAY_SHOW_STOP"
        const val ACTION_HIDE_STOP = "com.aiagent.android.OVERLAY_HIDE_STOP"
        const val ACTION_SHOW_SETTINGS = "com.aiagent.android.OVERLAY_SHOW_SETTINGS"
        const val ACTION_HIDE_SETTINGS = "com.aiagent.android.OVERLAY_HIDE_SETTINGS"
        const val ACTION_SHOW_THOUGHT = "com.aiagent.android.OVERLAY_SHOW_THOUGHT"
        const val ACTION_HIDE_THOUGHT = "com.aiagent.android.OVERLAY_HIDE_THOUGHT"
        const val ACTION_PULSE_TAP = "com.aiagent.android.OVERLAY_PULSE_TAP"
        const val ACTION_PULSE_SWIPE = "com.aiagent.android.OVERLAY_PULSE_SWIPE"
        const val ACTION_SHOW_DEMO_CONFIRM = "com.aiagent.android.OVERLAY_DEMO_CONFIRM"
        const val ACTION_SHOW_DEMO_ACTIVE = "com.aiagent.android.OVERLAY_DEMO_ACTIVE"
        const val ACTION_HIDE_DEMO = "com.aiagent.android.OVERLAY_DEMO_HIDE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OPTIONS = "options"
        const val EXTRA_THOUGHT_TITLE = "thought_title"
        const val EXTRA_TAP_X = "tap_x"
        const val EXTRA_TAP_Y = "tap_y"
        const val EXTRA_TAP_KIND = "tap_kind"
        const val EXTRA_SWIPE_X1 = "swipe_x1"
        const val EXTRA_SWIPE_Y1 = "swipe_y1"
        const val EXTRA_SWIPE_X2 = "swipe_x2"
        const val EXTRA_SWIPE_Y2 = "swipe_y2"

        /** Screen-space bounds of the persistent STOP button while it's visible. Used by
         *  AgentAccessibilityService to refuse `tap_at` / `swipe_at` calls that would land on it. */
        @Volatile
        var stopButtonBounds: Rect? = null

        /** Invoked when the user taps the persistent STOP overlay button. Set by MainViewModel. */
        @Volatile
        var stopListener: (() -> Unit)? = null

        /** Invoked when the user gives a fresh voice instruction inside the floating ⚙️ panel.
         *  The string is the (already-transcribed) instruction. Set by MainViewModel. */
        @Volatile
        var startListener: ((String) -> Unit)? = null

        /** True while the agent is running, set by MainViewModel. The settings overlay reads
         *  this to decide whether the action button should be "🎤 Запустить" or "▶️ Продолжить". */
        @Volatile
        var agentRunning: Boolean = false

        /** Invoked when the user taps "💀 Дед инсайд" in the floating ⚙️ settings panel.
         *  Set by MainViewModel to call panicShutdown(). */
        @Volatile
        var panicListener: (() -> Unit)? = null

        fun showQuestion(context: Context, text: String, options: List<String>? = null) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_QUESTION
                putExtra(EXTRA_TEXT, text)
                if (!options.isNullOrEmpty()) {
                    putExtra(EXTRA_OPTIONS, options.toTypedArray())
                }
            }
            context.startService(intent)
        }

        fun showStatus(context: Context, text: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_STATUS
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE }
            context.startService(intent)
        }

        fun showStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_STOP }
            context.startService(intent)
        }

        fun hideStop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_STOP }
            context.startService(intent)
        }

        /** Show the persistent floating ⚙️ Settings button + expandable panel on top of every app. */
        fun showSettings(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_SETTINGS }
            context.startService(intent)
        }

        fun hideSettings(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_SETTINGS }
            context.startService(intent)
        }

        /** Show / update the floating "thoughts island" overlay with the agent's current step. */
        fun showThought(context: Context, title: String, body: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_THOUGHT
                putExtra(EXTRA_THOUGHT_TITLE, title)
                putExtra(EXTRA_TEXT, body)
            }
            context.startService(intent)
        }

        fun hideThought(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_THOUGHT }
            context.startService(intent)
        }

        /** Spawn a transient pulse animation at the given screen coordinates so the user can
         *  see exactly where the agent is tapping. [kind] is "tap", "long", or "swipe". */
        fun pulseTap(context: Context, x: Int, y: Int, kind: String = "tap") {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_PULSE_TAP
                putExtra(EXTRA_TAP_X, x)
                putExtra(EXTRA_TAP_Y, y)
                putExtra(EXTRA_TAP_KIND, kind)
            }
            context.startService(intent)
        }

        fun pulseSwipe(context: Context, x1: Int, y1: Int, x2: Int, y2: Int) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_PULSE_SWIPE
                putExtra(EXTRA_SWIPE_X1, x1)
                putExtra(EXTRA_SWIPE_Y1, y1)
                putExtra(EXTRA_SWIPE_X2, x2)
                putExtra(EXTRA_SWIPE_Y2, y2)
            }
            context.startService(intent)
        }

        fun showDemonstrationConfirm(context: Context, prompt: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW_DEMO_CONFIRM
                putExtra(EXTRA_TEXT, prompt)
            }
            context.startService(intent)
        }

        fun showDemonstrationActive(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_SHOW_DEMO_ACTIVE }
            context.startService(intent)
        }

        fun hideDemonstration(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE_DEMO }
            context.startService(intent)
        }

        /**
         * Apply window-level brightness to the overlay view (if currently shown). `value` should be
         * in [0..1] for an explicit level or -1 to follow system. Has no effect when the overlay
         * is not currently visible — Android does not provide a way to change brightness of other
         * apps without WRITE_SETTINGS, which is intentionally not declared.
         */
        fun applyBrightness(@Suppress("UNUSED_PARAMETER") context: Context, value: Float) {
            pendingBrightness = value
        }

        @Volatile
        var pendingBrightness: Float = -1f
    }
}
