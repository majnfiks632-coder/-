# План v0.3 — переключаемый STT, лучшие провайдеры под VPN

> Версия 0.3 — обновление по STT. Базис из v0.2 в силе, дополняю и упрощаю там, где новый исследованный провайдер закрывает несколько модулей сразу.

---

## 1. Что я искал и что нашёл

Сравнил streaming-STT провайдеров на 19 мая 2026 года по 4 критериям: точность на русском (WER), end-to-end латентность, цена, наличие "семантического конца фразы" / диаризации спикеров. Источники — Soniox benchmark, Deepgram changelog, ElevenLabs/AssemblyAI/Speechmatics/OpenAI docs, costbench и smallest.ai сравнения 2026.

### 1.1 Таблица провайдеров

| Провайдер | Russian WER | Латентность | Цена / мин | Стрим | Эндпоинт-детекция | Диаризация | Нужен VPN? |
|---|---|---|---|---|---|---|---|
| **Soniox** stt-rt | **6.2 %** | ~150 ms | ~$0.007 | WebSocket | **Семантическая** | **Встроенная** | Да |
| **Deepgram Nova-3** | 8.0 % | ~150 ms | $0.0077 | WebSocket | Да | Да | Да |
| ElevenLabs Scribe v2 RT | 8.3 % | 150 ms | $0.40/час | WebSocket | Да | Да | Да |
| OpenAI gpt-realtime-whisper | ~10 % | низкая | $0.006/мин | WebSocket/WebRTC | Встроенная VAD | Через GPT | Да |
| AssemblyAI Whisper-RT | 11.1 % | sub-300 ms | $0.15/час | WebSocket | Да | Да | Да |
| Speechmatics | 11.7 % | < 1 с | $0.0117 | WebSocket | Да | Да | Да |
| **Yandex SpeechKit Streaming** | топ (нет в бенче) | 200-500 ms | ~80 ₽/час (~$0.018) | gRPC | Да | Да | **НЕТ** |
| Android-built-in SpeechRecognizer | средне | 1-2 с | 0 | one-shot | нет стрима | нет | НЕТ |

Источник цифр WER — собственный бенчмарк Soniox на русскоязычных тестах. Источник латентности — официальные доки и страницы продуктов.

### 1.2 Победители для нашего сценария

**Лидер: Soniox**

Не просто "лучший WER" — у Soniox два убийственных фактора для нашей задачи:

1. **Semantic endpoint detection** — модель понимает, что фраза закончена, **по интонации и паузам**, а не только по тишине. Это твоя "вторая нейронка-роутер для определения вопросов" встроенная в STT. Меньше задержка, меньше ложных срабатываний.
2. **Speaker diarization прямо в стриме** — каждый токен приходит с тегом спикера (`speaker_1`, `speaker_2`, …). Не нужен отдельный модуль с ECAPA-TDNN, не нужен enrollment голоса.

Это удаляет из v0.2 целых **два модуля** (`SpeakerTagger.kt` и эвристическую часть `QuestionRouter.kt`).

**Запас: Deepgram Nova-3**

Низкая латентность, добавили русский в feb 2026. Хорошая страховка если Soniox упадёт.

**Аварийный без-VPN: Yandex SpeechKit Streaming**

VPN моргает у тебя на середине экзамена — Yandex продолжит работать с локальных серверов. Чуть выше латентность, но 100% работает в РФ.

---

## 2. Архитектура переключаемого STT

### 2.1 Интерфейс

```kotlin
interface StreamingStt : AutoCloseable {
    val providerId: String
    suspend fun open(config: SttConfig)
    suspend fun sendAudio(pcm: ByteArray)       // 16-bit PCM, sample rate из config
    val events: SharedFlow<SttEvent>
}

sealed class SttEvent {
    data class Partial(val text: String, val speaker: String?) : SttEvent()
    data class Final(val text: String, val speaker: String?, val isEndpoint: Boolean) : SttEvent()
    data class SpeakerChange(val from: String?, val to: String) : SttEvent()
    data class Error(val cause: Throwable, val retryable: Boolean) : SttEvent()
}

data class SttConfig(
    val sampleRate: Int = 16_000,
    val languageHints: List<String> = listOf("ru", "en"),
    val enableDiarization: Boolean = true,
    val enableEndpointDetection: Boolean = true,
    val maxSpeakers: Int = 4,
)
```

Каждый провайдер — свой класс:
- `stt/SonioxStreamingStt.kt` (default)
- `stt/DeepgramStreamingStt.kt`
- `stt/YandexStreamingStt.kt`
- `stt/OpenAiRealtimeStt.kt`
- `stt/ElevenLabsStt.kt`
- `stt/AssemblyAiStt.kt`
- `stt/SpeechmaticsStt.kt`
- `stt/AndroidStt.kt` (fallback при полной потере сети)

### 2.2 Менеджер `stt/SttManager.kt`

```kotlin
class SttManager(
    private val priorityOrder: List<String>,   // ["soniox", "deepgram", "yandex", "openai-rt", "android"]
    private val perProviderKeys: Map<String, String>,
) {
    suspend fun startAdaptive(): StreamingStt
}
```

Поведение:
- При старте берёт первый провайдер из `priorityOrder`, ключ которого валиден.
- При фатальной ошибке (401/403/неверный ключ/network kill) переключается на следующий, **сохраняя audio buffer** последних 5 секунд (отправит их в новый провайдер).
- При мягкой ошибке (429/timeout/transient): retry с backoff, **не** переключается сразу.
- В UI всегда видно, какой провайдер активен сейчас, плюс кнопка "Переключить вручную".

### 2.3 Settings UI (новый экран "STT")

- Радиогруппа "Активный провайдер" + чекбокс "автоматический fallback".
- Для каждого провайдера — поле ключа и optional base URL (для self-hosted Whisper).
- Порядок fallback drag-and-drop списком.
- Кнопка "Health-check всех": быстрый ping (3-сек запись с микрофона → отправка → ждём partial) — показывает зелёный/красный по каждому.

### 2.4 Что упрощается из v0.2

- **`agent/SpeakerTagger.kt` — удаляется**. Soniox даёт спикеров из коробки. Если активен не-Soniox провайдер без диаризации (Android, OpenAI без GPT-4o) — fallback на простой heuristic (микрофон на шее → "you" по громкости близкого источника).
- **`agent/QuestionRouter.kt` — упрощается**. Раньше был "слой 1 эвристика + слой 2 Opus роутер". Теперь:
  - Slot 1: **из STT приходит `Final(isEndpoint=true)` → вопрос считается завершён**. Это работает у Soniox/Deepgram/Yandex.
  - Slot 2: **Opus-роутер только для ambiguous-кейсов** (Final без endpoint, или несколько спикеров наслаиваются). Запускается раз в 1.5 сек, если есть несфинализированный текст.

Это даёт примерно **−500 мс латентности** против схемы из v0.2 и убирает один сетевой запрос на каждое решение.

---

## 3. Что ещё нужно знать про конкретных провайдеров

### Soniox
- **WebSocket**: `wss://stt-rt.soniox.com/transcribe-websocket`
- Auth: первый JSON-фрейм с `api_key`.
- Модель для русского: `stt-rt-preview` или production-релиз когда выйдет.
- Аудио: 16-bit PCM mono, любой sample rate, заявляют `audio_format: "auto"`.
- Flags: `enable_speaker_diarization: true`, `enable_endpoint_detection: true`, `enable_language_identification: true`, `language_hints: ["ru","en"]`.
- Можно дать `context.terms` — список доменно-специфичных слов (имена авторов: Пушкин, Лермонтов, …). Это сильно поднимает точность на спец-лексике.

### Deepgram Nova-3
- **WebSocket**: `wss://api.deepgram.com/v1/listen?model=nova-3&language=ru&interim_results=true&endpointing=300&diarize=true&punctuate=true`
- Auth: `Authorization: Token <key>`.
- Аудио: 16-bit PCM, sample rate в query (`encoding=linear16&sample_rate=16000`).
- Endpoint detection через параметр `endpointing` (ms тишины перед финализацией). Для нашего сценария — 300 мс.
- Диаризация работает в стриме с задержкой ~200 мс на инициализацию.

### Yandex SpeechKit Streaming
- **gRPC**: `stt.api.cloud.yandex.net:443`, service `yandex.cloud.ai.stt.v3.Recognizer/RecognizeStreaming`.
- Auth: `Authorization: Api-Key <key>` (для service-account API-ключей) или IAM-токен.
- Аудио: PCM (раздел `raw_audio`) или OGG/Opus. 16/22/48 kHz mono.
- Endpoint detection встроен. Диаризация — параметр `recognition_model.audio_processing_options.speaker_labeling: SPEAKER_LABELING_ENABLED`.
- **Главное преимущество для нас**: не требует VPN. Если у тебя моргнёт VPN в середине экзамена, остальные STT сразу отвалятся, а Yandex продолжит.

### OpenAI Realtime (`gpt-realtime-whisper`)
- **WebSocket**: `wss://api.openai.com/v1/realtime?intent=transcription`.
- Аудио: 24 kHz PCM16 mono (внимание — не 16 kHz!).
- Endpoint detection встроена через `noise_reduction` + GPT-4o VAD.
- Дорого, но точность хорошая, ещё одна страховка.

### ElevenLabs Scribe v2 Realtime
- 150 мс латентности, 90+ языков включая русский.
- WebSocket: `wss://api.elevenlabs.io/v1/speech-to-text/realtime`.
- Если используем ElevenLabs ещё и для TTS — единый ключ.

---

## 4. Обновление модулей из v0.2

- `audio/AudioPipeline.kt` — **остаётся**, но теперь у него один потребитель: текущий активный `StreamingStt`. Аудио шлётся сразу в стрим, без локального VAD (он есть у всех провайдеров).
- `stt/SpeechToText.kt` — переименовывается в `stt/AndroidStt.kt`, превращается в один из реализаторов `StreamingStt` (на самом деле он one-shot, но обёрнутый), используется только как последний fallback.
- `agent/QuestionRouter.kt` — упрощается, см. 2.4.
- `agent/SpeakerTagger.kt` — удаляется.

---

## 5. Что мне нужно от тебя

Поскольку каждый провайдер — отдельный API-ключ, минимально нужно:

| Провайдер | Что нужно | Где взять |
|---|---|---|
| Soniox (по умолчанию primary) | API-ключ | [console.soniox.com](https://console.soniox.com/) — бесплатный free tier ~2 часа |
| Deepgram (secondary) | API-ключ | [console.deepgram.com](https://console.deepgram.com/) — бесплатный $200 кредит |
| Yandex SpeechKit (no-VPN backup) | service-account API-key | [console.cloud.yandex.ru](https://console.cloud.yandex.ru/) — grant ~4000 ₽ |
| OpenAI (опционально) | sk-…, если хочешь добавить | platform.openai.com |
| ElevenLabs (опционально) | API-key, если хочешь добавить | elevenlabs.io |

Минимум — Soniox + Yandex (один платный, второй no-VPN). Запасные можно докинуть потом, переключаются в Settings без переустановки.

---

## 6. Что меняется в плане этапов

Этап C из v0.2 теперь:

- C1: реализовать общий `StreamingStt` интерфейс + `SttManager`.
- C2: имплементировать `SonioxStreamingStt` + `DeepgramStreamingStt` + `YandexStreamingStt`.
- C3: имплементировать оставшихся (`OpenAiRealtimeStt`, `ElevenLabsStt`, `AssemblyAiStt`, `SpeechmaticsStt`, `AndroidStt`).
- C4: UI экрана STT с переключением и health-check.
- C5: интеграция с `AudioPipeline` и `AnswerOrchestrator` (Soniox endpoint detection → `slot.acquire()`).

Это растягивает этап C, но он того стоит — фундамент стабильности.

---

## 7. Открытые вопросы

1. Готов получить ключи Soniox + Yandex SpeechKit сейчас (5-10 минут на регистрацию)?
2. Какие из остальных провайдеров (Deepgram / OpenAI Realtime / ElevenLabs / AssemblyAI / Speechmatics) добавить тоже?
3. Подтверди порядок fallback по умолчанию: **Soniox → Deepgram → Yandex → OpenAI → Android-native**. Или своя последовательность?

Всё остальное из плана v0.2 (Opus-only, KeyManager для Kiro ksk, 3 слота, ESP, кнопки, Bluetooth) — без изменений. Ждём `.ino` и ответы по вопросам выше.
