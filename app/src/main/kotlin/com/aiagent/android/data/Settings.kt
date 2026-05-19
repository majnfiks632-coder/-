package com.aiagent.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent user-configurable settings for the agent. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-shot migration: builds prior to v3 shipped with maxSteps default = 20. The
        // user explicitly asked for unbounded steps — always force MAX_VALUE so old
        // installs that persisted 10_000 also start running unbounded.
        if (!prefs.getBoolean(KEY_MIGRATED_MAX_STEPS_V4, false)) {
            prefs.edit {
                putInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
                putBoolean(KEY_MIGRATED_MAX_STEPS_V4, true)
            }
        }
        // Migrate the legacy single-provider settings (`api_key`, `base_url`) into a free
        // provider slot so that users upgrading from an earlier build don't lose their
        // already-typed config. New installs default to Kiro AI in slot 1, so the legacy
        // values (which were typically Groq) go into slot 2 instead.
        if (!prefs.getBoolean(KEY_MIGRATED_PROVIDERS_V1, false)) {
            val legacyKey = prefs.getString(KEY_API, "") ?: ""
            val legacyUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
            prefs.edit {
                if (legacyKey.isNotBlank() && (prefs.getString(KEY_PROVIDER2_API, "") ?: "").isBlank()) {
                    putString(KEY_PROVIDER2_API, legacyKey)
                }
                if (legacyUrl.isNotBlank() && (prefs.getString(KEY_PROVIDER2_BASE_URL, "") ?: "").isBlank()) {
                    putString(KEY_PROVIDER2_BASE_URL, legacyUrl)
                }
                putBoolean(KEY_MIGRATED_PROVIDERS_V1, true)
            }
        }
        // Migrate users who had Groq as provider 1 from earlier builds: move Groq into
        // slot 2 (secondary) and leave slot 1 free for Kiro AI. We only do this if slot 1
        // currently points at api.groq.com AND slot 2 is empty.
        if (!prefs.getBoolean(KEY_MIGRATED_KIRO_FIRST, false)) {
            val p1Url = prefs.getString(KEY_PROVIDER1_BASE_URL, "") ?: ""
            val p2Url = prefs.getString(KEY_PROVIDER2_BASE_URL, "") ?: ""
            if (p1Url.contains("groq.com", ignoreCase = true) && p2Url.isBlank()) {
                val p1Key = prefs.getString(KEY_PROVIDER1_API, "") ?: ""
                val p1Name = prefs.getString(KEY_PROVIDER1_NAME, "") ?: ""
                prefs.edit {
                    putString(KEY_PROVIDER2_NAME, p1Name.ifBlank { "OpenAI-совместимый" })
                    putString(KEY_PROVIDER2_BASE_URL, p1Url)
                    putString(KEY_PROVIDER2_API, p1Key)
                    putString(KEY_PROVIDER1_NAME, DEFAULT_PROVIDER1_NAME)
                    putString(KEY_PROVIDER1_BASE_URL, DEFAULT_PROVIDER1_BASE_URL)
                    putString(KEY_PROVIDER1_API, "")
                    putInt(KEY_ACTIVE_PROVIDER, 1)
                }
            }
            prefs.edit { putBoolean(KEY_MIGRATED_KIRO_FIRST, true) }
        }
        // Migrate STT provider: builds prior to this one defaulted to "groq" which is an
        // external dependency. New default is the on-device Android SpeechRecognizer.
        if (!prefs.getBoolean(KEY_MIGRATED_STT_ON_DEVICE, false)) {
            val current = prefs.getString(KEY_STT_PROVIDER, "") ?: ""
            prefs.edit {
                if (current.isBlank() || current == "groq") {
                    putString(KEY_STT_PROVIDER, DEFAULT_STT_PROVIDER)
                }
                putBoolean(KEY_MIGRATED_STT_ON_DEVICE, true)
            }
        }
        // Migrate to per-slot transport selector: builds with the Kiro-native client need
        // slot 1 to default to the native Kiro transport pointing at the official endpoint.
        if (!prefs.getBoolean(KEY_MIGRATED_KIRO_NATIVE_V1, false)) {
            prefs.edit {
                if ((prefs.getString(KEY_PROVIDER1_TRANSPORT, "") ?: "").isBlank()) {
                    putString(KEY_PROVIDER1_TRANSPORT, TRANSPORT_KIRO)
                }
                if ((prefs.getString(KEY_PROVIDER2_TRANSPORT, "") ?: "").isBlank()) {
                    putString(KEY_PROVIDER2_TRANSPORT, TRANSPORT_OPENAI)
                }
                val p1Url = prefs.getString(KEY_PROVIDER1_BASE_URL, "") ?: ""
                if (p1Url.isBlank()) {
                    putString(KEY_PROVIDER1_BASE_URL, DEFAULT_KIRO_BASE_URL)
                }
                putBoolean(KEY_MIGRATED_KIRO_NATIVE_V1, true)
            }
        }
        // One-shot migration: previous builds defaulted reasoning_effort to "low" and
        // unconditionally sent it on every chat call. Most non-OpenAI models reject this
        // with HTTP 400, so we now ship with "" by default. Existing installs are migrated
        // exactly once: any "low" value (the previous default) is cleared.
        if (!prefs.getBoolean(KEY_MIGRATED_REASONING_V3, false)) {
            val current = prefs.getString(KEY_REASONING_EFFORT, "") ?: ""
            prefs.edit {
                if (current == "low") putString(KEY_REASONING_EFFORT, "")
                putBoolean(KEY_MIGRATED_REASONING_V3, true)
            }
        }
        // One-shot migration: previous builds defaulted to "never auto-stop"
        // (autoPauseOnIdle=false, waitForMessages=true) which surprised users —
        // the agent never finished on its own. The user explicitly asked for the
        // opposite default: auto-stop on `done`, with a toggle to keep it alive.
        // Apply the new defaults to existing installs exactly once — users who
        // explicitly flipped the toggle later will keep their preference because
        // the migration flag stays set.
        if (!prefs.getBoolean(KEY_MIGRATED_AUTOSTOP_V1, false)) {
            prefs.edit {
                putBoolean(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
                putBoolean(KEY_WAIT_FOR_MESSAGES, DEFAULT_WAIT_FOR_MESSAGES)
                putBoolean(KEY_MIGRATED_AUTOSTOP_V1, true)
            }
        }
    }

    // -- Two-provider model -------------------------------------------------------------------
    //
    // The app supports two simultaneously configured OpenAI-compatible providers. Slot 1 is
    // "Kiro AI" by default (the app's primary target); slot 2 is a generic OpenAI-compatible
    // fallback. The legacy `apiKey` / `baseUrl` properties below proxy to whichever provider
    // is currently active, so all existing call-sites (Agent, LlmClient, STT) keep working
    // without touching them.

    var activeProvider: Int
        get() = prefs.getInt(KEY_ACTIVE_PROVIDER, 1).coerceIn(1, 2)
        set(value) = prefs.edit { putInt(KEY_ACTIVE_PROVIDER, value.coerceIn(1, 2)) }

    var provider1Name: String
        get() = prefs.getString(KEY_PROVIDER1_NAME, DEFAULT_PROVIDER1_NAME) ?: DEFAULT_PROVIDER1_NAME
        set(value) = prefs.edit { putString(KEY_PROVIDER1_NAME, value) }

    var provider1BaseUrl: String
        get() = prefs.getString(KEY_PROVIDER1_BASE_URL, DEFAULT_PROVIDER1_BASE_URL)
            ?: DEFAULT_PROVIDER1_BASE_URL
        set(value) = prefs.edit { putString(KEY_PROVIDER1_BASE_URL, value) }

    var provider1ApiKey: String
        get() = prefs.getString(KEY_PROVIDER1_API, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROVIDER1_API, value) }

    /**
     * Optional pool of additional API keys for slot 1, separated by newlines.
     * When the active key fails with auth (401/403/Expired/AccessDenied) or quota,
     * KiroClient/LlmClient rotate through this pool and promote the first working
     * one to be the new [provider1ApiKey] so the rotation persists across runs.
     */
    var provider1ExtraApiKeys: String
        get() = prefs.getString(KEY_PROVIDER1_EXTRA_KEYS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROVIDER1_EXTRA_KEYS, value) }

    /** Full key pool for slot 1: primary key first, then each non-blank line of extras. */
    val provider1ApiKeyPool: List<String>
        get() = buildKeyPool(provider1ApiKey, provider1ExtraApiKeys)

    /** Transport for slot 1: [TRANSPORT_KIRO] or [TRANSPORT_OPENAI]. */
    var provider1Transport: String
        get() = prefs.getString(KEY_PROVIDER1_TRANSPORT, TRANSPORT_KIRO) ?: TRANSPORT_KIRO
        set(value) = prefs.edit { putString(KEY_PROVIDER1_TRANSPORT, value) }

    var provider2Name: String
        get() = prefs.getString(KEY_PROVIDER2_NAME, DEFAULT_PROVIDER2_NAME) ?: DEFAULT_PROVIDER2_NAME
        set(value) = prefs.edit { putString(KEY_PROVIDER2_NAME, value) }

    var provider2BaseUrl: String
        get() = prefs.getString(KEY_PROVIDER2_BASE_URL, DEFAULT_PROVIDER2_BASE_URL)
            ?: DEFAULT_PROVIDER2_BASE_URL
        set(value) = prefs.edit { putString(KEY_PROVIDER2_BASE_URL, value) }

    var provider2ApiKey: String
        get() = prefs.getString(KEY_PROVIDER2_API, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROVIDER2_API, value) }

    /** Optional pool of additional API keys for slot 2, same shape as [provider1ExtraApiKeys]. */
    var provider2ExtraApiKeys: String
        get() = prefs.getString(KEY_PROVIDER2_EXTRA_KEYS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROVIDER2_EXTRA_KEYS, value) }

    /** Full key pool for slot 2. */
    val provider2ApiKeyPool: List<String>
        get() = buildKeyPool(provider2ApiKey, provider2ExtraApiKeys)

    /** Key pool for the currently active provider — read by the LLM clients. */
    val apiKeyPool: List<String>
        get() = if (activeProvider == 1) provider1ApiKeyPool else provider2ApiKeyPool

    /**
     * Promote [newPrimary] to the primary key of the currently active provider. Called by
     * KiroClient/LlmClient after a fallback key in the pool successfully authenticated, so
     * the next run doesn't waste a request retrying the dead primary. The previous primary
     * is preserved at the top of the extras list (so the user can still see what failed).
     */
    fun promoteApiKey(newPrimary: String) {
        if (newPrimary.isBlank()) return
        val active = activeProvider
        val currentPrimary = if (active == 1) provider1ApiKey else provider2ApiKey
        if (currentPrimary == newPrimary) return
        val extras = if (active == 1) provider1ExtraApiKeys else provider2ExtraApiKeys
        val extrasList = extras.lines().map { it.trim() }.filter { it.isNotBlank() }
        val rebuiltExtras = buildList {
            // Keep the dead primary visible at the top of extras so the user knows which
            // key got replaced. Filter out duplicates.
            if (currentPrimary.isNotBlank()) add(currentPrimary)
            for (k in extrasList) if (k != newPrimary && k != currentPrimary) add(k)
        }.joinToString("\n")
        if (active == 1) {
            provider1ApiKey = newPrimary
            provider1ExtraApiKeys = rebuiltExtras
        } else {
            provider2ApiKey = newPrimary
            provider2ExtraApiKeys = rebuiltExtras
        }
    }

    private fun buildKeyPool(primary: String, extras: String): List<String> {
        val out = ArrayList<String>()
        if (primary.isNotBlank()) out.add(primary.trim())
        for (line in extras.lines()) {
            val t = line.trim()
            if (t.isNotBlank() && t !in out) out.add(t)
        }
        return out
    }

    /** Transport for slot 2: [TRANSPORT_KIRO] or [TRANSPORT_OPENAI]. */
    var provider2Transport: String
        get() = prefs.getString(KEY_PROVIDER2_TRANSPORT, TRANSPORT_OPENAI) ?: TRANSPORT_OPENAI
        set(value) = prefs.edit { putString(KEY_PROVIDER2_TRANSPORT, value) }

    // ---- Legacy proxy properties (read from the active provider) --------------------------------

    var apiKey: String
        get() = if (activeProvider == 1) provider1ApiKey else provider2ApiKey
        set(value) {
            if (activeProvider == 1) provider1ApiKey = value else provider2ApiKey = value
        }

    var baseUrl: String
        get() = if (activeProvider == 1) provider1BaseUrl else provider2BaseUrl
        set(value) {
            if (activeProvider == 1) provider1BaseUrl = value else provider2BaseUrl = value
        }

    /** Transport for the currently active provider. */
    var transport: String
        get() = if (activeProvider == 1) provider1Transport else provider2Transport
        set(value) {
            if (activeProvider == 1) provider1Transport = value else provider2Transport = value
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
        set(value) = prefs.edit { putInt(KEY_MAX_STEPS, value) }

    /** Sampling temperature. Stored as a float; 0.0..2.0. */
    var temperature: Float
        get() = prefs.getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)
        set(value) = prefs.edit { putFloat(KEY_TEMPERATURE, value) }

    /** Maximum completion tokens for a single LLM turn. 0 means "do not send" (use server default). */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(value) = prefs.edit { putInt(KEY_MAX_TOKENS, value) }

    /** Reasoning effort for reasoning-enabled models (e.g. gpt-oss-*). Empty = do not send. */
    var reasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, DEFAULT_REASONING_EFFORT) ?: DEFAULT_REASONING_EFFORT
        set(value) = prefs.edit { putString(KEY_REASONING_EFFORT, value) }

    /** System prompt steering the agent. Empty = use built-in default. */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SYSTEM_PROMPT, value) }

    // --- Game-assistant features ---------------------------------------------------------------

    /** Frames-per-second for screen captures used by `take_screenshot` / `read_screen_text`.
     *  Special value 0.0 = "auto" (capture on demand, after each agent action). */
    var screenFps: Float
        get() = prefs.getFloat(KEY_SCREEN_FPS, DEFAULT_SCREEN_FPS)
        set(value) = prefs.edit { putFloat(KEY_SCREEN_FPS, value) }

    /** Where the agent listens by default: `mic` (always works) or `system` (MediaProjection,
     *  requires Android 10+ AND that the source app allows playback capture). */
    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, DEFAULT_AUDIO_SOURCE) ?: DEFAULT_AUDIO_SOURCE
        set(value) = prefs.edit { putString(KEY_AUDIO_SOURCE, value) }

    /** Speech-to-text provider: `android` (built-in SpeechRecognizer, no external service)
     *  or `openai` (POST to the active provider's `/audio/transcriptions` endpoint). */
    var sttProvider: String
        get() = prefs.getString(KEY_STT_PROVIDER, DEFAULT_STT_PROVIDER) ?: DEFAULT_STT_PROVIDER
        set(value) = prefs.edit { putString(KEY_STT_PROVIDER, value) }

    /** TTS engine name. Currently only `android` (built-in TextToSpeech) is implemented. */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, DEFAULT_TTS_ENGINE) ?: DEFAULT_TTS_ENGINE
        set(value) = prefs.edit { putString(KEY_TTS_ENGINE, value) }

    /** Speech rate for TTS. 1.0 = normal. */
    var ttsRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
        set(value) = prefs.edit { putFloat(KEY_TTS_RATE, value) }

    /** File access mode: `saf` (only user-picked folders), `all` (MANAGE_EXTERNAL_STORAGE), or
     *  `app` (only this app's private storage). */
    var fileAccessMode: String
        get() = prefs.getString(KEY_FILE_MODE, DEFAULT_FILE_MODE) ?: DEFAULT_FILE_MODE
        set(value) = prefs.edit { putString(KEY_FILE_MODE, value) }

    /** Set of `content://` SAF tree URIs the user has granted to the agent. */
    var allowedFolders: Set<String>
        get() = prefs.getStringSet(KEY_ALLOWED_FOLDERS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_ALLOWED_FOLDERS, value) }

    /** Overlay opacity (0..1). Default 0.5 = 50% as the user requested. */
    var overlayAlpha: Float
        get() = prefs.getFloat(KEY_OVERLAY_ALPHA, DEFAULT_OVERLAY_ALPHA)
        set(value) = prefs.edit { putFloat(KEY_OVERLAY_ALPHA, value) }

    /**
     * When true, the agent attaches a downscaled screenshot to the next user message every time
     * `read_screen` / `take_screenshot` runs. Requires a vision-capable model
     * (e.g. gpt-4o on OpenAI or any other vision-capable model exposed by the active provider).
     * Text-only models will reject the request.
     */
    var sendScreenshots: Boolean
        get() = prefs.getBoolean(KEY_SEND_SCREENSHOTS, DEFAULT_SEND_SCREENSHOTS)
        set(value) = prefs.edit { putBoolean(KEY_SEND_SCREENSHOTS, value) }

    /** Maximum dimension (px) for screenshots sent to the model. Smaller = fewer tokens. */
    var screenshotMaxDim: Int
        get() = prefs.getInt(KEY_SCREENSHOT_MAX_DIM, DEFAULT_SCREENSHOT_MAX_DIM)
        set(value) = prefs.edit { putInt(KEY_SCREENSHOT_MAX_DIM, value) }

    /**
     * When true, the agent loop pauses after the model calls `done` or returns a plain text
     * reply with no tool calls. The user resumes with «Продолжить». When false (default for
     * the gameplay use-case) the agent keeps running until the user taps the overlay STOP
     * button — useful when you want it to coach you live during a game.
     */
    var autoPauseOnIdle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PAUSE, DEFAULT_AUTO_PAUSE)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PAUSE, value) }

    /**
     * When true, the agent automatically captures a fresh screenshot at the start of every
     * step and injects it into the conversation as a user message. Means the model never has
     * to remember to call `read_screen` itself; it always has the current screen state.
     * Default true — required for gameplay where the screen is changing constantly.
     */
    var autoScreenshotEachTurn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCREENSHOT, DEFAULT_AUTO_SCREENSHOT)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SCREENSHOT, value) }

    /**
     * Two-model mode: the controller (`model`) is text-only, and a separate vision model
     * (`visionDescriberModel`) is asked to describe each fresh screenshot. The controller then
     * receives the textual description plus the accessibility tree and decides actions. Lets
     * the user run a powerful but text-only model (e.g. gpt-oss-120b) as the brain while
     * delegating pixel parsing to a vision-capable model.
     */
    var useVisionDescriber: Boolean
        get() = prefs.getBoolean(KEY_USE_VISION_DESCRIBER, DEFAULT_USE_VISION_DESCRIBER)
        set(value) = prefs.edit { putBoolean(KEY_USE_VISION_DESCRIBER, value) }

    /** Model id used by the vision describer when [useVisionDescriber] is enabled. */
    var visionDescriberModel: String
        get() = prefs.getString(KEY_VISION_DESCRIBER_MODEL, DEFAULT_VISION_DESCRIBER_MODEL)
            ?: DEFAULT_VISION_DESCRIBER_MODEL
        set(value) = prefs.edit { putString(KEY_VISION_DESCRIBER_MODEL, value) }

    // --- Virtual joystick overlay ---------------------------------------------------------------

    /** When true, the floating virtual joystick is shown on top of all apps. */
    var joystickEnabled: Boolean
        get() = prefs.getBoolean(KEY_JOYSTICK_ENABLED, DEFAULT_JOYSTICK_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_JOYSTICK_ENABLED, value) }

    /** X coordinate (px, screen-space) of the joystick base centre. */
    var joystickX: Int
        get() = prefs.getInt(KEY_JOYSTICK_X, DEFAULT_JOYSTICK_X)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_X, value) }

    /** Y coordinate (px, screen-space) of the joystick base centre. */
    var joystickY: Int
        get() = prefs.getInt(KEY_JOYSTICK_Y, DEFAULT_JOYSTICK_Y)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_Y, value) }

    /** Radius (px) of the joystick base. The thumb travels within this circle. */
    var joystickRadius: Int
        get() = prefs.getInt(KEY_JOYSTICK_RADIUS, DEFAULT_JOYSTICK_RADIUS)
        set(value) = prefs.edit { putInt(KEY_JOYSTICK_RADIUS, value) }

    /**
     * When true, dragging the overlay joystick generates a synchronized in-app drag at the
     * SAME screen coordinates via the AccessibilityService. Use case: place the overlay
     * joystick directly over the game's built-in joystick — the gesture passes through.
     */
    var joystickDispatch: Boolean
        get() = prefs.getBoolean(KEY_JOYSTICK_DISPATCH, DEFAULT_JOYSTICK_DISPATCH)
        set(value) = prefs.edit { putBoolean(KEY_JOYSTICK_DISPATCH, value) }

    /**
     * When true, a persistent ⚙️ floating button is shown on top of all apps. Tapping it
     * expands a small panel with toggles for joystick / two-model mode / auto-screenshot etc.
     * Lets the user adjust settings without leaving the game.
     */
    var settingsOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_OVERLAY, DEFAULT_SETTINGS_OVERLAY)
        set(value) = prefs.edit { putBoolean(KEY_SETTINGS_OVERLAY, value) }

    // --- Memory of user actions + wait-for-message --------------------------------------------

    /**
     * When true, the agent loop suspends after each round of activity (no tool calls, OR a
     * `done` signal) and waits for a fresh user message via the floating overlay (voice or
     * text). When the message arrives, the agent resumes with full conversation history,
     * including a summary of everything the user did between turns. Differs from
     * [autoPauseOnIdle] which exits the loop entirely and requires the user to press
     * «Продолжить» in the in-app UI.
     *
     * Default ON — matches the user's gameplay-assistant request: "ждать нового сообщения,
     * как только пришло — читает и отвечает на него".
     */
    var waitForMessages: Boolean
        get() = prefs.getBoolean(KEY_WAIT_FOR_MESSAGES, DEFAULT_WAIT_FOR_MESSAGES)
        set(value) = prefs.edit { putBoolean(KEY_WAIT_FOR_MESSAGES, value) }

    /**
     * When true, the AccessibilityService records user-initiated taps / scrolls / text input
     * / app switches into [com.aiagent.android.agent.UserActionLog]. The agent then drains
     * that log at the start of each LLM turn so the controller knows what the user did
     * between messages — the foundation of "запоминает промежуточные действия". Default ON.
     */
    var recordUserActions: Boolean
        get() = prefs.getBoolean(KEY_RECORD_USER_ACTIONS, DEFAULT_RECORD_USER_ACTIONS)
        set(value) = prefs.edit { putBoolean(KEY_RECORD_USER_ACTIONS, value) }

    // --- Device-control master switch ----------------------------------------------------------

    /**
     * Master switch: "agent is allowed to control my phone".
     *
     * When OFF (the default — the user explicitly asked for accessibility to be on-demand):
     *   - The agent's UI/device-control tools (tap, swipe, type_text, press_*, open_app,
     *     read_screen, take_screenshot, joystick_move, list_apps that drive Intents …) return
     *     a soft error explaining that the user disabled device control. The model receives
     *     the error and can either ask the user to enable it or proceed without that tool.
     *   - Floating overlays (STOP island, ⚙ overlay, joystick, thoughts island) are NEVER
     *     shown — even if the user previously enabled them or the agent is running.
     *   - The AccessibilityService still passes through events untouched, but the agent
     *     never calls into it.
     *
     * The user toggles this from inside the app (Settings → "Управлять телефоном"). When ON,
     * regular behaviour resumes. The model is told about this state via the system prompt so
     * it doesn't repeatedly try to call tools that will fail.
     */
    var deviceControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_CONTROL_ENABLED, DEFAULT_DEVICE_CONTROL_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_DEVICE_CONTROL_ENABLED, value) }

    /**
     * When true, agent runs continue inside a foreground service so the OS doesn't kill the
     * task when the user switches away from the app. A notification with a STOP action is
     * shown while the agent works, and a completion notification arrives when the agent is
     * done (tool=done, or it ran out of steps, or threw an error). The user explicitly asked
     * for this: «Добавь чтоб в фоне работало пока агент работает а потом приходит уведомление».
     */
    var runInBackground: Boolean
        get() = prefs.getBoolean(KEY_RUN_IN_BACKGROUND, DEFAULT_RUN_IN_BACKGROUND)
        set(value) = prefs.edit { putBoolean(KEY_RUN_IN_BACKGROUND, value) }

    /**
     * Master toggle for the reasoning (chain-of-thought) panel in chat. When ON, the UI
     * shows the model's reasoning stream above the final answer and offers an
     * «Ответить сразу» button to skip it. When OFF, reasoning is suppressed entirely
     * (no panel, no streaming of `<think>` blocks) — the agent only emits the final answer.
     * Default ON because the user explicitly requested the feature.
     */
    var reasoningModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_REASONING_MODE_ENABLED, DEFAULT_REASONING_MODE_ENABLED)
        set(value) = prefs.edit { putBoolean(KEY_REASONING_MODE_ENABLED, value) }

    /**
     * When ON, the app is brought back to the foreground the moment the agent finishes an
     * answer while the user was in another app. Off by default so users who want the
     * background-completion notification flow aren't surprised by the app stealing focus.
     */
    var openAppAfterAnswer: Boolean
        get() = prefs.getBoolean(KEY_OPEN_APP_AFTER_ANSWER, DEFAULT_OPEN_APP_AFTER_ANSWER)
        set(value) = prefs.edit { putBoolean(KEY_OPEN_APP_AFTER_ANSWER, value) }

    companion object {
        const val PREFS_NAME = "agent_prefs"
        // Two-provider defaults: Kiro AI is the primary slot (the app's entire reason for
        // existing). Slot 2 is left as a generic OpenAI-compatible fallback that the user can
        // fill in if they have a secondary provider (OpenAI, OpenRouter, Together, vLLM, …).
        // Both URLs start empty — the user pastes their own endpoint and key in Settings.
        /** Official Kiro AI / Amazon Q Developer endpoint used by `kiro-cli` and our
         *  native client. The `ksk_…` API key authenticates directly here — no proxy. */
        const val DEFAULT_KIRO_BASE_URL = "https://q.us-east-1.amazonaws.com"

        const val TRANSPORT_KIRO = "kiro"
        const val TRANSPORT_OPENAI = "openai"

        const val DEFAULT_PROVIDER1_NAME = "Kiro AI"
        const val DEFAULT_PROVIDER1_BASE_URL = DEFAULT_KIRO_BASE_URL
        const val DEFAULT_PROVIDER2_NAME = "OpenAI-совместимый"
        const val DEFAULT_PROVIDER2_BASE_URL = ""
        const val DEFAULT_BASE_URL = DEFAULT_PROVIDER1_BASE_URL
        const val DEFAULT_MODEL = "claude-opus-4.7"
        // Agent runs unbounded — the model decides when it's done. Kept as a high cap to
        // protect against truly broken loops, but the UI no longer surfaces this knob.
        const val DEFAULT_MAX_STEPS = Int.MAX_VALUE
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_MAX_TOKENS = 2048
        // Empty by default: most providers/models do NOT accept this parameter and will
        // return HTTP 400 if it is sent. The Settings UI lets the user opt in.
        const val DEFAULT_REASONING_EFFORT = ""

        /** 0.0 = auto (capture-on-demand). Otherwise frames per second. */
        const val DEFAULT_SCREEN_FPS = 0.0f

        const val DEFAULT_AUDIO_SOURCE = "mic"
        // On-device SpeechRecognizer by default: no external service required. Users who
        // want a Whisper-style endpoint can switch to "openai" in Settings; the app then
        // POSTs to the active provider's /audio/transcriptions — the same shape as the
        // OpenAI / Groq / self-hosted-Whisper API.
        const val DEFAULT_STT_PROVIDER = "android"
        const val DEFAULT_TTS_ENGINE = "android"
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_FILE_MODE = "saf"
        const val DEFAULT_OVERLAY_ALPHA = 0.5f
        const val DEFAULT_SEND_SCREENSHOTS = false
        const val DEFAULT_SCREENSHOT_MAX_DIM = 1024
        // Chatbot-mode default: auto-pause after the model reports `done`. Matches the user's
        // explicit ask: «сделай чтоб он сам останавливался по умолчанию и если я не хочу чтоб не
        // останавливался». Flip AND turn ON «Не останавливаться» in Settings to get the
        // live-coach behaviour (previously the default). [waitForMessages] takes priority
        // over this flag in the agent loop — see Agent.run().
        const val DEFAULT_AUTO_PAUSE = true
        const val DEFAULT_AUTO_SCREENSHOT = true
        // Two-model mode is OFF by default; user opts in. When ON, the user picks a
        // vision-capable model exposed by their active provider.
        const val DEFAULT_USE_VISION_DESCRIBER = false
        // Empty by default: the user picks a vision-capable model on their own provider
        // when they opt in to two-model mode. No hardcoded third-party model id.
        const val DEFAULT_VISION_DESCRIBER_MODEL = ""

        const val DEFAULT_JOYSTICK_ENABLED = false
        const val DEFAULT_JOYSTICK_X = 250
        const val DEFAULT_JOYSTICK_Y = 900
        const val DEFAULT_JOYSTICK_RADIUS = 180
        const val DEFAULT_JOYSTICK_DISPATCH = true
        const val DEFAULT_SETTINGS_OVERLAY = false

        // Wait-for-message: OFF by default. Combined with [autoPauseOnIdle]=true above,
        // this gives the user the chatbot-like behaviour they asked for — the agent
        // answers, hits `done` and exits the loop cleanly until the next instruction.
        // Users who want the live-coach loop (model stays running, listens for voice /
        // chat interrupts indefinitely) flip the «Не останавливаться» toggle in Settings.
        const val DEFAULT_WAIT_FOR_MESSAGES = false
        const val DEFAULT_RECORD_USER_ACTIONS = true

        // Device-control master switch: off by default. The user explicitly asked for
        // accessibility (and floating overlays) to only kick in when they actively want the
        // agent to drive the phone. Until they flip this on, the app is a pure chat assistant.
        const val DEFAULT_DEVICE_CONTROL_ENABLED = false

        // Foreground service so the agent keeps running when the app is backgrounded; on by
        // default — matches the user's request.
        const val DEFAULT_RUN_IN_BACKGROUND = true

        // Reasoning panel master switch. ON by default — the user explicitly asked for the
        // feature and the UI keeps a per-message collapsible block so it doesn't crowd the
        // transcript when the model isn't emitting reasoning.
        const val DEFAULT_REASONING_MODE_ENABLED = true

        // Open-app-after-answer: OFF by default. When the agent runs in the background
        // we already post a completion notification — bringing the app to the front on
        // every reply would steal focus. Users who want that behaviour opt in.
        const val DEFAULT_OPEN_APP_AFTER_ANSWER = false

        // Legacy SharedPreferences keys — still read on first migration only.
        @Suppress("unused")
        private const val KEY_API = "api_key"
        @Suppress("unused")
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_PROVIDER1_NAME = "provider1_name"
        private const val KEY_PROVIDER1_BASE_URL = "provider1_base_url"
        private const val KEY_PROVIDER1_API = "provider1_api_key"
        private const val KEY_PROVIDER1_EXTRA_KEYS = "provider1_extra_api_keys"
        private const val KEY_PROVIDER2_NAME = "provider2_name"
        private const val KEY_PROVIDER2_BASE_URL = "provider2_base_url"
        private const val KEY_PROVIDER2_API = "provider2_api_key"
        private const val KEY_PROVIDER2_EXTRA_KEYS = "provider2_extra_api_keys"
        private const val KEY_MIGRATED_PROVIDERS_V1 = "migrated_providers_v1"
        private const val KEY_MIGRATED_KIRO_FIRST = "migrated_kiro_first_v1"
        private const val KEY_MIGRATED_STT_ON_DEVICE = "migrated_stt_on_device_v1"
        private const val KEY_MIGRATED_KIRO_NATIVE_V1 = "migrated_kiro_native_v1"
        private const val KEY_PROVIDER1_TRANSPORT = "provider1_transport"
        private const val KEY_PROVIDER2_TRANSPORT = "provider2_transport"
        private const val KEY_MODEL = "model"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_MIGRATED_MAX_STEPS_V4 = "migrated_max_steps_v4"
        private const val KEY_MIGRATED_REASONING_V3 = "migrated_reasoning_v3"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        private const val KEY_SCREEN_FPS = "screen_fps"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_FILE_MODE = "file_mode"
        private const val KEY_ALLOWED_FOLDERS = "allowed_folders"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
        private const val KEY_SEND_SCREENSHOTS = "send_screenshots"
        private const val KEY_SCREENSHOT_MAX_DIM = "screenshot_max_dim"
        private const val KEY_AUTO_PAUSE = "auto_pause_on_idle"
        private const val KEY_MIGRATED_AUTOSTOP_V1 = "migrated_autostop_v1"
        private const val KEY_AUTO_SCREENSHOT = "auto_screenshot_each_turn"
        private const val KEY_USE_VISION_DESCRIBER = "use_vision_describer"
        private const val KEY_VISION_DESCRIBER_MODEL = "vision_describer_model"
        private const val KEY_JOYSTICK_ENABLED = "joystick_enabled"
        private const val KEY_JOYSTICK_X = "joystick_x"
        private const val KEY_JOYSTICK_Y = "joystick_y"
        private const val KEY_JOYSTICK_RADIUS = "joystick_radius"
        private const val KEY_JOYSTICK_DISPATCH = "joystick_dispatch"
        private const val KEY_SETTINGS_OVERLAY = "settings_overlay"
        private const val KEY_WAIT_FOR_MESSAGES = "wait_for_messages"
        private const val KEY_RECORD_USER_ACTIONS = "record_user_actions"
        private const val KEY_DEVICE_CONTROL_ENABLED = "device_control_enabled"
        private const val KEY_RUN_IN_BACKGROUND = "run_in_background"
        private const val KEY_REASONING_MODE_ENABLED = "reasoning_mode_enabled"
        private const val KEY_OPEN_APP_AFTER_ANSWER = "open_app_after_answer"
    }
}
