# SmartTube VOX 8 — Отчёт по исправлению критических пользовательских регрессий (Patch #12)

## 1. Пользовательские инциденты (User Reports)

В релизах SmartTube VOX 6.1 и 7 были зафиксированы четыре критических пользовательских инцидента, потребовавших проведения глубокого аудита и экстренного устранения регрессий:

1. **BUG A — Фоновое воспроизведение (Background Playback)**:
   * **Отчёт**: `VOX-A-AD9BF2` (Устройство: *TUVIO TD50UFBSV1*, Android 11 / API 30).
   * **Симптомы**: При нажатии клавиши Home или переходе в фоновый режим с настройкой «Только аудио» звук мгновенно прекращается («значок убегает вверх и тишина»), значок PiP либо исчезает, либо застревает в верхней части экрана. До релиза 6.1 функционал работал корректно.
2. **BUG B — Ошибка упаковки скачивания (Download Packaging / Finalize)**:
   * **Отчёт**: `VOX-A-CECBBE` (Устройство: *DuneHD Pro Vision 4K*, Android 11 / API 30).
   * **Симптомы**: На финальном этапе объединения треков и публикации MKV в пользовательское хранилище возникает ошибка `DOWNLOAD_FAILED: Ошибка скачивания: UNKNOWN`. Проблема воспроизводится на разных видеороликах («через птичку и через КВН»).
3. **BUG C — Ошибка встроенного обновления OTA (OTA Update 6.1 → 7)**:
   * **Отчёт**: Множественные обращения пользователей при попытке обновиться с VOX 6.1 на VOX 7 через диалог «Проверить обновления».
   * **Симптомы**: Приложение находит обновление, но процесс падает с сырым исключением Java (`IllegalStateException: Error while download. Install path is null` или `JSONException: Value <!DOCTYPE of type String cannot be converted to JSONObject`).
4. **BUG D — Сбой локального / оффлайн-воспроизведения (Offline / Local Playback)**:
   * **Отчёт**: `VOX-DA7404` (Устройство: *Google W2*, Google TV, Android 11 / API 30).
   * **Симптомы**: В диагностическом журнале зафиксировано событие `PLAYER_LOCAL_SOURCE_ERROR` с причиной `InvalidResponseCodeException`, а также замедленный старт воспроизведения `PLAYER_STARTUP_SLOW` (5300–5400 мс).

---

## 2. Фоновое воспроизведение (Background Playback / Audio-Only)

### 2.1. Анализ первопричины (Root Cause)
1. **Ложный переход в PiP вместо Audio-Only**:
   В методе `wannaEnterToPip()` класса `PlaybackActivity` условие проверяло:
   ```java
   getPlayerData().getBackgroundMode() == PlayerData.BACKGROUND_MODE_PIP || isEngineBlocked()
   ```
   При переходе в режим «Только аудио» (`BACKGROUND_MODE_SOUND`) вызывался метод `blockEngine(true)`. В результате `wannaEnterToPip()` возвращал `true`, и система пыталась свернуть полноэкранное окно в Picture-in-Picture («значок убегает вверх»), что приводило к конфликту поверхности рендеринга и системной анимации.
2. **Зависание декодера без Surface**:
   При скрытии Activity окно `SurfaceView` уничтожалось. Видеодекодер ExoPlayer (`MediaCodecVideoRenderer`), не имея валидной поверхности и продолжая запрашивать кадры, застревал в состоянии `STATE_BUFFERING`. Это блокировало продвижение тактовой синхронизации аудиоренедера, вызывая тишину.
3. **Отсутствие активной Foreground Service**:
   Сервис `BackgroundPlaybackService` не запускался, в результате чего Android 11 (API 30) классифицировал процесс плеера как фоновый кэшируемый и замораживал потоки декодирования через несколько секунд после сворачивания.

### 2.2. Реализованные исправления
1. **Строгая изоляция PiP и звукового режима**:
   В `PlaybackActivity.java` метод `wannaEnterToPip()` очищен от флага блокировки движка — переход в PiP разрешён только при явном выборе режима `BACKGROUND_MODE_PIP`.
2. **Динамическое отключение видеотрека (`setVideoTrackEnabled`)**:
   В `TrackSelectorManager`, `ExoPlayerController` и `PlaybackFragment` реализовано управление активностью видеорендерера через `DefaultTrackSelector.setRendererDisabled(RENDERER_INDEX_VIDEO, true)`. При сворачивании в режим `BACKGROUND_MODE_SOUND` видеопоток отключается, предотвращая зависание аппаратного декодера без Surface. При возврате в приложение видеотрек восстанавливается.
3. **Полноценный жизненный цикл `BackgroundPlaybackService`**:
   Сервис обновлён под требования API 26+ / API 29+ (`foregroundServiceType="mediaPlayback"`). Создаётся системный канал уведомлений с низким приоритетом и ongoing-уведомлением, обеспечивающим бесперебойную работу фонового аудиоплеера.
4. **Управление AudioFocus**:
   При переходе в фон регистрируется слушатель `OnAudioFocusChangeListener`, корректно удерживающий `STREAM_MUSIC` и фиксирующий события потери и возврата аудиофокуса.

---

## 3. Обновление OTA (OTA Update 6.1 → 7)

### 3.1. Анализ первопричины (Root Cause)
1. **Отсутствие JSON-манифеста в релизе GitHub**:
   В URL проверки обновлений был указан статический путь `https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.json`. В релизе `v32.56-vox.7` данный файл отсутствовал, и GitHub возвращал HTML-страницу с кодом 404 (`<!DOCTYPE html>...`). Стандартный парсер пытался разобрать её как JSON, вызывая `org.json.JSONException`.
2. **Конфликт ассетов релиза (.wgt и SHA256SUMS)**:
   В релизе присутствуют пакет Tizen (`SmartTube-VOX-7.0.0.wgt`) и текстовый файл `SHA256SUMS.txt`. Старый загрузчик не фильтровал ассеты по расширению и пытался скачать первый попавшийся URL, приводя к `IllegalStateException: Error while download. Install path is null`.
3. **Отображение сырых исключений**:
   `AppUpdatePresenter` форматировал текст ошибки как `error.getMessage()`, показывая пользователю имена Java-классов.

### 3.2. Архитектура `VoxOtaUpdateManager`
Создана изолированная подсистема обновления `com.liskovsoft.smartyoutubetv2.common.vox.ota`:
* **Отказоустойчивый парсинг (`VoxOtaReleaseParser`)**: автоматическое распознавание HTML-страниц ошибок, поддержка формата GitHub Releases API и классических манифестов.
* **Резервный канал опроса**: если `smarttube_vox.json` недоступен, автоматически опрашивается официальный GitHub Releases API (`https://api.github.com/repos/Kiryuhak/SmartTube-Vox/releases/latest`).
* **Селектор ассетов (`VoxReleaseAssetSelector`)**: строгая фильтрация не-APK файлов (`.wgt`, `.txt`, `.json`, `.md`), сопоставление с архитектурой процессора устройства (`arm64-v8a`, `armeabi-v7a`, `x86`) с резервным переходом на `universal.apk`.
* **Компаратор версий (`VoxVersionComparator`)**: корректное сравнение версий (`6.1 < 7`, `7 == 7`), предотвращение обновления стабильного канала на dev-сборки.
* **Проверка безопасности (`VoxOtaSecurityVerifier`)**:
  * Сверка хэша SHA-256 по `SHA256SUMS.txt`.
  * Проверка цифровой подписи APK с эталонным отпечатком сертификата VOX: `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d`.
* **Безопасная обработка ошибок**: типизированные коды `VoxOtaErrorCode` с понятными локализованными русскими сообщениями. Исключения `java.lang...` полностью исключены из UI.

---

## 4. Упаковка и финализация скачивания (Download Packaging / DuneHD)

### 4.1. Анализ первопричины (Root Cause)
1. **Сбой MediaStore на Android 11 (API 30)**:
   На приставках DuneHD Pro Vision 4K со специфической реализацией Scoped Storage метод `resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)` или открытие `openOutputStream` возвращали ошибку или пустой дескриптор. У загрузчика отсутствовал резервный переход на прямое файловое хранилище (`publishLegacyStorage`).
2. **Ошибка классификации в UNKNOWN**:
   В `VoxDownloadCoordinator.notifyError` исключения `STORAGE_ERROR`, `FINALIZE_FAILED`, `OUTPUT_MOVE_FAILED` не обрабатывались в блоке `when (code)`, в результате чего пользователю выводилось неинформативное сообщение `Ошибка скачивания: UNKNOWN`.
3. **Ложное срабатывание валидации размера**:
   На content:// URI вызов `openFileDescriptor().statSize` часто возвращал `-1` (неизвестный размер до завершения индексации MediaScanner), из-за чего проверка `storage.getPublishedFileSize() == finalFileBytes` ложно прерывала загрузку готового файла.

### 4.2. Реализованные исправления
1. **Graceful Fallback на Legacy Storage**:
   В `VoxMediaStorePublisher` при сбое `publishScopedStorage` выполняется автоматический перехват ошибки и публикация через `publishLegacyStorage` в каталог `Movies/SmartTube VOX/`.
2. **Буферизованный cross-mount fallback (128 KiB)**:
   При сбое атомарного переименования между точками монтирования (`EXDEV`) копирование выполняется с буфером 128 KiB с обязательной последующей сверкой размера файла перед удалением временного `.tmp`.
3. **Точное определение размера файла через FileChannel и OpenableColumns**:
   В `VoxDownloadStorage.getPublishedFileSize` добавлен опрос размера через `FileInputStream(pfd.fileDescriptor).channel.size()` и `OpenableColumns.SIZE`, гарантирующий корректность данных на любых устройствах.
4. **Устранение кода ошибки UNKNOWN**:
   Все этапы упаковки и финализации строго типизированы под категории `PACKAGING_FAILED`, `FINALIZE_FAILED`, `OUTPUT_MOVE_FAILED` и `STORAGE_FULL`.

---

## 5. Локальное и оффлайн-воспроизведение (Offline / Local Playback)

### 5.1. Анализ первопричины (Root Cause)
1. **Безусловная классификация любой ошибки источника как локальной**:
   В `ExoPlayerController.onPlayerError` блок `error.type == ExoPlaybackException.TYPE_SOURCE` безусловно выставлял код `PLAYER_LOCAL_SOURCE_ERROR`. При сетевом воспроизведении потока YouTube CDN возвращал HTTP 403/404 (`InvalidResponseCodeException`), порождая ложную диагностику локальной ошибки.
2. **Тихий fallback в интернет для скачанных видео**:
   В `VideoLoaderController` при отсутствии локального файла код переходил к `YouTubeServiceManager.instance().getFormatInfoObserve(...)`, пытаясь запросить поток с серверов YouTube. При отсутствии сети или истечении ссылки возникал сбой.

### 5.2. Реализованные исправления
1. **Строгая классификация типов источников (`sourceType`)**:
   Введены категории: `LOCAL_FILE`, `CONTENT_URI`, `HTTP`, `DOWNLOAD_CACHE`, `OTHER`.
   * Для локальных файлов логируется `PLAYER_LOCAL_SOURCE_ERROR`.
   * Для удалённых HTTP-потоков логируется `PLAYER_HTTP_ERROR` с безопасным кодом ответа `responseCode` без URL.
2. **Запрет удалённого отката (`REMOTE_FALLBACK: NO`)**:
   Для видео с `video.isLocal == true` при недоступности файла выводится понятное сообщение «Локальный файл не найден или повреждён», без попыток обращения к YouTube CDN.
3. **Безопасный контекст для `PLAYER_STARTUP_SLOW`**:
   Диагностическое событие дополнено метриками: `sourceType`, `codec`, `networkLimitedConfidence` (0.0 для локальных файлов), `initialBufferMs`. Это позволяет однозначно отделять проблемы диска/декодера от сетевого троттлинга.

---

## 6. Диагностическое покрытие (Diagnostics 2.1)

Добавлены 2 новые категории и полный спектр диагностических кодов:
* **Категория `BACKGROUND` («Фоновый режим»)**:
  `BACKGROUND_PLAYBACK_REQUESTED`, `BACKGROUND_PLAYBACK_ALLOWED`, `BACKGROUND_PLAYBACK_BLOCKED`, `BACKGROUND_AUDIO_ONLY_ENTER`, `BACKGROUND_AUDIO_ONLY_EXIT`, `BACKGROUND_PLAYER_CONTINUED`, `BACKGROUND_PLAYER_PAUSED`, `BACKGROUND_SERVICE_STARTED`, `BACKGROUND_SERVICE_STOPPED`, `BACKGROUND_AUDIO_FOCUS_GAIN`, `BACKGROUND_AUDIO_FOCUS_LOSS`, `BACKGROUND_MEDIASESSION_ACTIVE`, `BACKGROUND_MEDIASESSION_RELEASED`.
* **Категория `OTA` («Обновление ПО»)**:
  `OTA_CHECK_STARTED`, `OTA_RELEASE_FOUND`, `OTA_VERSION_COMPARED`, `OTA_ASSET_SELECTED`, `OTA_DOWNLOAD_STARTED`, `OTA_DOWNLOAD_COMPLETED`, `OTA_HASH_VERIFIED`, `OTA_SIGNATURE_VERIFIED`, `OTA_INSTALL_REQUESTED`, `OTA_FAILED`.
* **Категории `DOWNLOAD` и `PLAYER`**:
  `DOWNLOAD_PACKAGING_FAILED`, `DOWNLOAD_FINALIZE_FAILED`, `PLAYER_HTTP_ERROR`, `PLAYER_LOCAL_SOURCE_ERROR`.
* **Полное соответствие между платформами**:
  Аналогичные коды и категории внедрены в `TizenSafeLogger.js`.

---

## 7. Результаты регрессионного тестирования

| Подсистема | Результат | Комментарий |
|---|:---:|---|
| **Background Playback (API 30/34)** | **PASS** | Нет ложного PiP, видеорендерер отключается, ForegroundService активен |
| **OTA Updater (6.1 → 7)** | **PASS** | Устойчив к HTML 404, фильтрует .wgt, сверяет SHA256 и подпись |
| **Download Packaging (DuneHD)** | **PASS** | Автоматический fallback на прямое хранилище, 128 KiB буфер |
| **Offline Playback & Source Types** | **PASS** | `REMOTE_FALLBACK: NO`, точная классификация HTTP vs Local |
| **Downloads 2.0 Performance** | **PASS** | Сохранён параллелизм сокетов, Range resume и очередь FIFO |
| **Zero Telemetry / Privacy** | **PASS** | Никаких PII, URL видео, токенов или названий в логах |
| **Tizen Diagnostics Parity** | **PASS** | 9 из 9 тестов пройдены |

---

## 8. Оставшиеся задачи

Все заявленные инциденты приоритета P0 и P1 успешно локализованы, устранены и покрыты автоматизированными тестами. В следующем патче (Patch #13) планируется полировка UI-диалогов и следующий шаг развития локального инференса Live-бэкенда.
