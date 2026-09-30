# VOX 5 — Архитектура и реализация ядра скачивания (Patch #4)

Документ описывает реализацию изолированного Kotlin-ядра скачивания медиа-потоков для SmartTube VOX 5.

---

## 1. Обзор архитектуры ядра скачивания

Ядро скачивания расположено в пакете `com.liskovsoft.smartyoutubetv2.common.vox.download` и реализовано на **Kotlin** в соответствии с Kotlin-First стратегией VOX 5.

Цель этапа (Patch #4):
- Загрузка видео-потока YouTube (DASH/SABR/Adaptive), оригинального аудио-потока и дорожки перевода Яндекс VOT в изолированные временные файлы.
- Доведение задания до состояния `READY_FOR_MUX` (подготовка к сборке MKV в Patch #5).

---

## 2. Ключевые компоненты

### 2.1. Модели данных и машина состояний (`VoxDownloadModels.kt`, `VoxDownloadJob.kt`)
- **`VoxDownloadRequest`**: Неизменяемая модель запроса (`downloadId`, `videoId`, `videoTitle`, `selectedQuality`, `translationMode`).
- **`VoxDownloadState`**:
  - `IDLE` — задание создано;
  - `PREPARING_TRANSLATION` — опрос и ожидание генерации перевода на серверах Яндекс VOT;
  - `RESOLVING_STREAMS` — резолвинг прямых URL для видео и оригинального аудио через `YouTubeServiceManager`;
  - `DOWNLOADING_VIDEO` — потоковая загрузка видео (`video.part`);
  - `DOWNLOADING_ORIGINAL_AUDIO` — потоковая загрузка оригинального аудио (`original_audio.part`);
  - `DOWNLOADING_TRANSLATED_AUDIO` — потоковая загрузка перевода (`translated_audio.part`);
  - `READY_FOR_MUX` — все три дорожки успешно скачаны и проверены, файлы готовы к мультиплексированию;
  - `PAUSED` — скачивание приостановлено пользователем или восстановлено после перезапуска приложения;
  - `COMPLETED` — финальный файл собран и опубликован (резерв для Patch #5/#6);
  - `CANCELLED` — задание отменено пользователем (временные файлы удалены);
  - `FAILED` — неустранимая ошибка скачивания.
- **`generationToken`**: Атомарный токен жизненного цикла задания для предотвращения утечек и устаревших асинхронных коллбэков (stale callback protection).

### 2.2. Защита от SSRF и валидация URL (`VoxUrlSecurityValidator.kt`)
- Валидация протокола: разрешен только `https://`.
- Валидация хостов: белый список официальных CDN-доменов YouTube (`googlevideo.com`, `youtube.com`) и Яндекс VOT (`browser.yandex.ru`, `yandex.net`, `storage.yandex.net`).
- Блокировка приватных и локальных IP-адресов: запрет резолвинга в `localhost`, `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `::1`, `fc00::/7`, `fe80::/10`.
- Запрет небезопасных URI схем (`file://`, `ftp://`, `content://`, `http://`).

### 2.3. Изоляция хранилища и персистентность (`VoxDownloadStorage.kt`)
- Каталог хранения: приватная изолированная директория `<filesDir>/vox-downloads/<downloadId>/`.
- Временные файлы дорожек:
  - `video.part` — видео-поток (H.264 / VP9 / AV1);
  - `original_audio.part` — оригинальная дорожка (Opus / AAC);
  - `translated_audio.part` — дорожка перевода (MP3);
  - `job.json` — сохраненное состояние задания.
- **Безопасность метаданных**: В `job.json` сохраняются только идентификаторы, прогресс в байтах и настройки. Подписанные временные URL (CDN-токены) и OAuth-токены на диск **не записываются**.
- **Контроль дискового пространства**: Проверка доступного места на накопителе (`StatFs`) перед началом скачивания и динамически в процессе.

### 2.4. Резолвер медиа-потоков YouTube (`VoxStreamResolver.kt`)
- Получение адаптивных форматов видео и оригинального звука через `YouTubeServiceManager.getPlaybackService()`.
- Выбор видео-формата в соответствии с запрошенным качеством (`VoxQualityProfile`) и кодеком (`H.264`, `VP9`, `AV1`).
- Выбор оптимального оригинального аудио-потока с наилучшим битрейтом для итогового файла.

### 2.5. Резолвер перевода Яндекс VOT (`VoxTranslationResolver.kt`)
- Интеграция с `YandexVotApi` / `YandexVotApiClient`.
- Поддержка режимов `STANDARD` и `LIVELY` с проверкой OAuth-авторизации.
- Полноценная обработка всех статусов генерации: `STATUS_FINISHED`, `STATUS_PART_CONTENT`, `STATUS_WAITING`, `STATUS_LONG_WAITING`, `STATUS_AUDIO_REQUESTED`, `STATUS_SESSION_REQUIRED`.
- Безопасное поллинг-ожидание с валидацией возвращаемого URL.

### 2.6. Потоковый загрузчик с докачкой (`VoxSegmentDownloader.kt`)
- Ограниченный размер буфера (64 KB) для предотвращения OOM на Android TV с малым объемом ОЗУ.
- Поддержка HTTP Range:
  - Код `206 Partial Content`: корректное возобновление скачивания с существующего смещения.
  - Код `200 OK`: сервер не поддерживает Range или отдал файл целиком — безопасный перезапуск дорожки с 0.
  - Код `403 Forbidden` / `410 Gone`: автоматический запрос свежего подписанного URL через callback `urlRefreshProvider` и прозрачное продолжение скачивания.
  - Код `416 Range Not Satisfiable`: проверка полноты файла при совпадении размера.
  - Коды `429 Too Many Requests` и `5xx Server Error`: ограниченный экспоненциальный backoff с повтором попыток (до 5 раз).

### 2.7. Репозиторий и восстановление после сбоев (`VoxDownloadRepository.kt`)
- Потокобезопасное хранение активных заданий в памяти (`ConcurrentHashMap`).
- Восстановление незавершенных заданий при старте приложения: все незавершенные задания восстанавливаются в состоянии **`PAUSED`**, исключая спонтанный фоновый сетевой трафик без ведома пользователя.

### 2.8. Координатор загрузок (`VoxDownloadCoordinator.kt`)
- Синглтон-оркестратор жизненного цикла скачиваний.
- Гарантия одного активного скачивания в каждый момент времени (очередь/блокировка `activeJobLock`).
- Мост слушателей для Java-кода (`VoxDownloadListener`).
- Управление фоновым пулом потоков и безопасное завершение потоков при отмене.

---

## 3. Результаты модульного и интеграционного тестирования

1. **Модульные тесты ядра (`:common:testStvotDebugUnitTest`)**:
   - `VoxUrlSecurityValidatorTest` — PASS (проверка allowlist, SSRF, IPv4/IPv6 private ranges, port injection).
   - `VoxDownloadStorageTest` — PASS (проверка изоляции каталогов, безопасного JSON, очистки).
   - `VoxSegmentDownloaderTest` — PASS (тесты Range 206, 200 truncate, 403 URL refresh, 429 backoff retry, cancellation).
   - `VoxDownloadCoordinatorTest` — PASS (сквозной пайплайн, переход в `READY_FOR_MUX`, восстановление в `PAUSED`, отмена).
   - Все 349 тестов модуля `:common` успешно пройдены (594 checks total).

2. **Тесты OAuth-брокера**:
   - `services/yandex-oauth-broker`: 14/14 PASS.

3. **Сборка артефактов**:
   - `:smarttubetv:assembleStvotDebug` — PASS.
   - `:smarttubetv:assembleStstableDebug` — PASS.
