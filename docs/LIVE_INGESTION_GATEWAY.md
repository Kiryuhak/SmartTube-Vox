# SmartTube VOX 8 — Controlled Live Ingestion Gateway & Adaptive Controller

## 1. Архитектурный обзор (Live Translation 2.0 Foundation)

В рамках SmartTube VOX 8 разработан изолированный шлюз живых аудиосегментов (**Controlled Live Ingestion Gateway**) и адаптивный контроллер клиента (**Adaptive Live Audio Segment Controller**).

Архитектура решает проблему отсутствия потокового стриминга у существующих переводческих провайдеров (VOD-only backend), обеспечивая стандартизированный протокол между Android TV / Tizen клиентом и серверным адаптером перевода:

```
+---------------------------------------+
| SmartTube Client (Android TV / Tizen) |
|                                       |
|  ExoPlayer Live Audio Stream          |
|              |                        |
|  VoxLiveAdaptiveSegmentController     |
|   - Sequence & Generation Tracker     |
|   - Dynamic Delay & Buffer Policy     |
|   - Fallback to Original Audio        |
+---------------------------------------+
                   |
     HTTP/2 REST (X-Vox-* headers)
     Raw Opus/AAC Segment Ingestion
                   v
+---------------------------------------+
| SmartTube VOX Live Ingestion Gateway  |
|                                       |
|  - Rate Limiter & Abuse Protection    |
|  - Generation Reset & Anti-Stale Drop |
|  - Bounded In-Memory Queue & Dedupe   |
|  - Backpressure Enforcer (429)        |
+---------------------------------------+
                   |
       LiveTranslationProvider
                   |
        +----------+----------+
        |                     |
        v                     v
+-----------------+   +--------------------+
|  Mock Provider  |   | Yandex Adapter     |
| (Deterministic  |   | (UNSUPPORTED_      |
|  latency & test |   |  INCREMENTAL_      |
|  simulation)    |   |  TRANSLATION)      |
+-----------------+   +--------------------+
```

---

## 2. Спецификация API Шлюза (REST Contract)

### 2.1. Проверка работоспособности (`GET /health`)
- **URL**: `/health` или `/`
- **Method**: `GET`
- **Response (200 OK)**:
```json
{
  "status": "ok",
  "service": "SmartTube VOX Live Gateway",
  "mode": "development"
}
```

### 2.2. Инициализация сессии (`POST /v1/live/session`)
- **Headers**:
  - `Content-Type: application/json`
  - `X-Vox-Provider: mock` (или `yandex` для проверки готовности адаптера)
- **Request Body**:
```json
{
  "sourceLanguage": "en",
  "targetLanguage": "ru",
  "audioCodec": "opus",
  "chunkDurationMs": 2000
}
```
- **Response (201 Created)**:
```json
{
  "sessionId": "vox_live_9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "serverTime": 1728280000000,
  "capabilities": {
    "supportsSequentialSegments": true,
    "supportsIncrementalAudio": true,
    "supportsCancellation": true,
    "supportsSessionContinuation": true,
    "providerId": "mock_provider"
  },
  "limits": {
    "maxSegmentSizeBytes": 4194304,
    "minSegmentDurationMs": 500,
    "maxSegmentDurationMs": 12000,
    "maxQueuedSegments": 10,
    "maxQueuedDurationMs": 30000
  },
  "state": "ACTIVE",
  "generation": 1
}
```

### 2.3. Загрузка живого аудиосегмента (`POST /v1/live/session/:sessionId/segment`)
- **Headers**:
  - `Content-Type: application/octet-stream`
  - `X-Vox-Sequence`: монотонно возрастающий номер (0, 1, 2...)
  - `X-Vox-Generation`: поколение воспроизведения (1, 2 после перемотки)
  - `X-Vox-Duration-Ms`: длительность (500 .. 12000 мс)
  - `X-Vox-Source-Start-Ms`: начало фрагмента в потоке
  - `X-Vox-Source-End-Ms`: конец фрагмента в потоке
  - `X-Vox-Codec`: `opus` / `aac`
  - `X-Vox-Checksum`: SHA-256 хэш чанка для дедупликации
- **Body**: Бинарный аудио-чанк (до 4 МБ).
- **Ответы**:
  - **202 Accepted**: сегмент принят и поставлен в очередь воркеров.
  - **200 OK (DUPLICATE_ACCEPTED)**: сегмент уже принят или переведён (повторная отправка не нагружает сервер).
  - **200 OK (STALE_GENERATION_DROPPED)**: сегмент относится к старому поколению (до перемотки) и безопасно отброшен.
  - **413 Payload Too Large**: сегмент превысил лимит 4 МБ.
  - **429 Too Many Requests (`BACKPRESSURE_EXCEEDED`)**: очередь воркеров переполнена, клиенту возвращается заголовок `Retry-After: 2`.

### 2.4. Опрос готовности перевода (`GET /v1/live/session/:sessionId/segment/:sequence`)
- **Query Params**: `generation=1`
- **Response (200 OK)**:
```json
{
  "status": "READY",
  "sequence": 0,
  "generation": 1,
  "sourceStartMs": 0,
  "sourceEndMs": 2000,
  "durationMs": 2000,
  "translatedDurationMs": 2000,
  "codec": "opus",
  "text": "[MOCK_RU_TRANSLATION_SEQ_0]",
  "audioData": "<base64_audio_payload>",
  "providerLatencyMs": 500,
  "completedAtMs": 1728280001500,
  "isRealTranslation": false
}
```

### 2.5. Закрытие сессии (`DELETE /v1/live/session/:sessionId`)
- **Response (200 OK)**: `{ "status": "CLOSED", "sessionId": "..." }`
- Мгновенно освобождает буферы памяти, отменяет фоновые задачи воркеров.

---

## 3. Модель сессий и защита поколений (Generation Protection)

1. **Жизненный цикл сессии**:
   - `CREATED` -> `ACTIVE` -> `DEGRADED` (при перегрузке или сетевых просадках) -> `CLOSING` -> `CLOSED` / `EXPIRED`.
   - Сессия автоматически закрывается при отсутствии активности более 10 минут (`SESSION_TTL_MS`).
2. **Защита поколений (Seek / Live Jump)**:
   - При перемотке или прыжке к прямому эфиру клиент увеличивает `generationId` (например, с 1 до 2).
   - Шлюз при получении нового `generationId` немедленно отменяет все ожидающие и обрабатываемые сегменты старого поколения.
   - Опоздавшие сетевые пакеты со старым `generationId` отбрасываются без обработки.
   - Это гарантирует, что устаревший звук со старой позиции никогда не прозвучит после перемотки.

---

## 4. Ограничения ресурсов и серверная безопасность

1. **Защита от SSRF / Open Proxy**:
   - Шлюз принимает исключительно входящие бинарные сегменты.
   - Запросы с параметрами вроде `?url=...` или телом с URL немедленно отклоняются со статусом `400 FORBIDDEN_EXTERNAL_URL_FETCH`.
2. **Лимиты очередей (Backpressure)**:
   - `MAX_QUEUED_SEGMENTS = 10`
   - `MAX_QUEUED_DURATION_MS = 30 000 мс`
   - При превышении отдается HTTP 429 с заголовком `Retry-After: 2`.
3. **Строгая приватность (Zero-Telemetry)**:
   - Шлюз работает исключительно в оперативной памяти (RAM).
   - Дисковое сохранение входящих аудиоданных отключено (`retention = 0 сек`).
   - Запрещены поля `videoTitle`, `signedUrl`, `cookie`, `account`, `token`.

---

## 5. Адаптивный контроллер сегментов на клиенте (Android TV)

Класс `VoxLiveAdaptiveSegmentController` реализует интеллектуальное управление потоком:

### 5.1. Решения по отправке (Adaptive Ingest Decisions)
- **`SEND`**: очередь свободна, буфер в норме -> сегмент отправляется на шлюз.
- **`WAIT`**: буфер полон или очередь шлюза заполнена -> клиент приостанавливает нарезку сегментов.
- **`DROP_STALE`**: сегмент устарел относительно текущей позиции или поколения -> сброс.
- **`FALLBACK`**: сеть недоступна или шлюз вернул ошибку -> мгновенное переключение на оригинальный звук трансляции.

### 5.2. Бесшовный Fallback (Zero Disruption Playback)
- Если переведённый буфер проседает ниже порога `minimumTranslatedBufferMs`, звук незаметно переключается на оригинальную дорожку.
- **Основной плеер YouTube Live НЕ останавливается, НЕ показывает спиннер и НЕ буферизируется!**
- При накоплении достаточного запаса переведённого аудио звук возвращается к переводу.

### 5.3. Динамическая задержка и скорость воспроизведения
- Режимы буфера:
  - `LOW_LATENCY`: мин. 2с, цель 4с, макс. 10с.
  - `BALANCED`: мин. 4с, цель 8с, макс. 20с.
  - `STABLE`: мин. 6с, цель 12с, макс. 30с.
- При увеличении скорости воспроизведения (до 1.25x – 2.0x) эффективный порог буфера пропорционально масштабируется (`effectiveMin = minBuffer * max(1.0, speed)`).

---

## 6. Локальный запуск и интеграция с эмулятором

1. **Запуск шлюза**:
```bash
cd services/vox-live-gateway
npm install
npm test
npm run dev
```
Шлюз запускается на `http://0.0.0.0:8788`.

2. **Подключение эмулятора Android TV**:
- Эмулятор связывается с локальным шлюзом хоста по стандартному адресу:
  `http://10.0.2.2:8788`
- В релизных сборках feature flag `VOX_LIVE_TRANSLATION_EXPERIMENTAL` по умолчанию равен `false`. Локальные эндпоинты в релиз не попадают.

---

## 7. Дальнейшие шаги (Future Roadmap)

- **Patch #6**: Прототип захвата живого аудиопотока из AudioTrack/MediaCodec в фоновом режиме без влияния на основной видеорендер.
- **Patch #7**: Сквозная подача переведенных чанков в вторичный AudioTrack с синхронизацией по PTS.
