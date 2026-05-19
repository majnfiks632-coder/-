# Exam Assistant — голосовой ассистент для экзамена

Нативное Android-приложение для голосового ассистирования через bluetooth-наушник во время устного экзамена. На входе — стриминг STT, на выходе — TTS в наушник. Под капотом — **Kiro AI (Claude Opus 4.7 / 4.6)** через прямой `KiroClient` без прокси, мульти-ключ ротация, параллельные слоты ответов, веб-поиск через саб-агента, и аппаратный пульт на **ESP8266** с 3 кнопками.

> Стадия: PR-1, «бутстрап» из исходников `kiro-agent-source-v5`. Основной чат-функционал и интеграция с Kiro работают; голосовой/слотовый/ESP-функционал добавляется в следующих PR (см. план в [docs/PLAN.md](docs/PLAN.md)).

## Возможности (целевая)

- Постоянная запись с микрофона (foreground service).
- Стриминг STT (Soniox / Deepgram / Yandex / Android-native — переключаемый).
- Семантическая детекция конца фразы и распознавание спикеров (Soniox).
- Маршрутизация в Bluetooth-наушник для TTS-ответов.
- Параллельные слоты ответов (3) с общим контекстом, чтобы агенты не конфликтовали.
- Менеджер `ksk_`-ключей Kiro: health-check на старте, ротация, маркировка нестабильных, отдельный резервный ключ.
- Веб-поиск через саб-агента на отдельных ключах (не задействует резерв).
- ESP8266 пульт с 3 кнопками, captive-portal Wi-Fi, WebSocket к телефону.
- VPN-watchdog: при обрыве VPN автопереключение на Android-native STT.

## Что работает прямо сейчас (PR-1)

- Чат с Kiro AI через `KiroClient` (прямой AWS CodeWhisperer Streaming protocol, без прокси).
- Поддержка `claude-opus-4.7`, `claude-opus-4.6`, `claude-opus-4.5` (тариф Kiro Pro и выше).
- Adaptive thinking через prompt-инжект.
- Мульти-ключ rotation с авто-promote рабочего ключа.
- Полный набор tool-call'ов агента (Accessibility, screenshot, OCR, web search).
- Compose UI: чат, выбор модели, настройки.

## Сборка

Требования: Android Studio Hedgehog или новее, JDK 17, Android SDK API 34.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

В CI собирается через `.github/workflows/android.yml`.

## Настройка Kiro

1. Открыть приложение → ⚙ → «Активный провайдер» → `#1 Kiro AI`.
2. Вставить твой `ksk_…` ключ (или несколько — по одному на строку) в поле «API ключ».
3. Base URL по умолчанию — `https://q.us-east-1.amazonaws.com` (можно не менять).
4. Открыть пилюлю модели и выбрать `claude-opus-4.7`.

VPN с выходом в США (us-east-1) обязателен — Kiro endpoint недоступен из РФ напрямую.

## Архитектура (текущая)

```
app/src/main/kotlin/com/aiagent/android/
├── agent/          Автономный агент: Agent.kt, tool-calling loop, SubAgentManager
├── llm/            OpenAI-совместимый ChatBackend (Ktor) + общие модели
├── kiro/           Прямой клиент Kiro AI (AWS CodeWhisperer Streaming + Event Stream)
├── service/        Accessibility / ScreenRecorder / MediaProjection / Foreground
├── audio/          MicRecorder (16kHz PCM → WAV)
├── stt/            On-device SpeechRecognizer + /audio/transcriptions
├── tts/            Wrapper над TextToSpeech
├── web/            DDG + Wikipedia поиск (саб-агент)
├── ocr/            MLKit text recognition
├── files/          SAF + MANAGE_EXTERNAL_STORAGE
├── overlay/        Floating-окна
├── device/         Запросы к системе (battery, packages, …)
└── ui/             Jetpack Compose + Material3
    ├── theme/      KiroTheme + точная цветовая палитра
    └── chat/       Главный экран, выбор модели, settings, markdown
```

В следующих PR добавится `audio/AudioPipeline.kt`, `stt/SonioxStreamingStt.kt`, `agent/AnswerOrchestrator.kt`, `kiro/KeyManager.kt`, `bluetooth/BluetoothRouter.kt`, `esp/EspChannel.kt`, ESP8266 прошивка в `firmware/`.

## Лицензия

Базируется на `kiro-agent-source-v5` автора, перенесено в этот репозиторий для дальнейшего развития под устный экзамен.
