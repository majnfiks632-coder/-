# Kiro Agent — нативный гибрид Kiro + AI Agent

Это нативное Android-приложение, которое скрещивает **Kiro Mobile Chat** (UI) и
**AI Agent Minefics** (функционал) в одну сборку без веб-вью, Electron'ов и
прочих гибридов. UI один в один как у Kiro: тёмная тема, бейдж K, пилюли
выбора модели и квоты, markdown-пузыри. А внутри — весь автономный
агент: Accessibility-тапы и свайпы, MediaProjection-скриншоты, OCR,
STT/TTS, файлы, джойстик, 30+ tool-call'ов.

## Запуск

1. Открыть проект в Android Studio (Hedgehog / Iguana или новее, AGP 8.2+).
2. Подключить устройство по ADB (нужен Android 8.0+ / API 26+).
3. Жмякнуть Run — APK подпишется `app/debug.keystore`, тем же ключом,
   которым подписывают CI и Devin'овские сборки, чтобы апдейты
   накатывались без «не удалось обработать пакет».

Локальная сборка из терминала:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Архитектура

```
app/src/main/kotlin/com/aiagent/android/
├── agent/          # Автономный агент: Agent.kt, tool-calling loop, AgentLog
├── llm/            # OpenAI-совместимый клиент (ktor + kotlinx-serialization)
├── service/        # AccessibilityService, ScreenRecorderService,
│                   #   ScreenCaptureService, foreground service
├── ocr/            # MLKit text recognition
├── stt/  tts/      # On-device голос
├── files/          # SAF + MANAGE_EXTERNAL_STORAGE
├── overlay/        # Floating-окна (settings overlay, joystick)
├── audio/          # Запись микрофона
└── ui/
    ├── theme/      # KiroTheme + KiroColors (точный палет Kiro)
    └── chat/
        ├── KiroModels.kt         # Реестр моделей: Auto, Claude*, Qwen, …
        ├── ChatModels.kt         # ChatMessage, ToolEvent, UsageStats, …
        ├── ChatStorage.kt        # SharedPreferences + JSON (как localStorage)
        ├── ChatViewModel.kt      # Связь UI ↔ Agent.run()
        ├── MarkdownText.kt       # Свой минимальный markdown-рендер
        ├── MessageBubble.kt      # Пузыри + tool-карточки
        ├── ChatHeader.kt         # K-бейдж + model pill + quota pill + ⚙
        ├── MessageInput.kt       # Textarea + attach + send/stop
        ├── ModelPickerSheet.kt   # Bottom-sheet выбора модели
        ├── QuotaPanelSheet.kt    # Bottom-sheet с квотой + clear chat
        ├── SettingsSheet.kt      # Все агент-настройки
        └── ChatScreen.kt         # Сборка экрана
```

UI слой — полностью **Jetpack Compose** + Material3, никакого WebView и
никаких React'ов. ViewModel вызывает существующий `Agent.run(...)`, который
парсит tool-calls от LLM и дёргает реальные системные API.

## Лицензия и кредиты

Базируется на двух проектах автора:

* `kiro-mobile-chat` — UI-донор (Next.js PWA → Kotlin Compose port).
* `ai-agent-minefics` — функциональный донор (нативный Android).
