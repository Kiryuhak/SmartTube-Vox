# VOX 5 — фоновый сервис скачивания и управление процессами (Patch #7)

## 1. Архитектура фонового сервиса

`VoxDownloadService` реализует надежное выполнение загрузки и мультиплексирования видео со звуковым переводом Яндекс VOT при сворачивании приложения, переходе на домашний экран Android TV (HOME) или переключении на другие приложения.

### Ключевые компоненты:
1. **`VoxDownloadService` (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadService.kt`)**:
   - `ForegroundService` с типом `android:foregroundServiceType="dataSync"` (требование Android 14 / API 34).
   - `START_NOT_STICKY`: предотвращает некорректный автозапуск с пустым `Intent` при системном завершении процесса; активные задачи восстанавливаются репозиторием `VoxDownloadRepository`.
   - Защита от orphan-сервисов: при передаче неизвестного `downloadId` или невалидного ID служба немедленно останавливается (`stopForeground` + `stopSelf`), не оставляя зависших уведомлений.
   - Защита от stale-коллбэков: слушатель сопоставляет `progress.downloadId` с текущим активным заданием `currentDownloadId` и не сбрасывает чужие/активные уведомления.
   - Разделение семантики: `ACTION_START_DOWNLOAD` для запуска/подхвата новых и активных задач; `ACTION_RESUME_DOWNLOAD` для явного возобновления пользователем задач в состоянии `PAUSED`. Задачи в состояниях `FAILED`, `CANCELLED`, `COMPLETED` не перезапускаются автоматически.

2. **Политика и правила `VoxDownloadServicePolicy` (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadServicePolicy.kt`)**:
   - Чистый Kotlin-объект без привязки к Android Context, содержащий валидацию `downloadId` (`^[a-zA-Z0-9_-]{1,64}$`), классификацию активных/терминальных состояний и правила автовозобновления (`shouldAutoResumeOnStart`, `shouldResumeOnExplicitResume`).

3. **Канал уведомлений и оповещения (`NotificationChannel`)**:
   - ID канала: `smarttube_vox_downloads_channel` (`IMPORTANCE_LOW`, без навязчивого звука и вибрации).
   - Ongoing-уведомление прогресса (`NOTIFICATION_ID_PROGRESS = 55001`) с кнопкой «Отменить» (`ACTION_CANCEL_DOWNLOAD`) и открытием приложения по нажатию.
   - Финальные уведомления завершения (`NOTIFICATION_ID_COMPLETED = 55002`) и ошибки (`NOTIFICATION_ID_FAILED = 55003`) с `autoCancel=true`.

4. **Интеграция с UI и координатором (`VoxDownloadDialogHelper.kt` / `VoxDownloadCoordinator.kt`)**:
   - Запуск службы через `VoxDownloadDialogHelper` при старте/повторе загрузки и отмена через службу.
   - Выделенные поля прогресса публикации MediaStore (`publishBytesProcessed`, `publishTotalBytes`, `publishPercent`) в `VoxDownloadJob` и `VoxDownloadProgress` с вызовом `updatePublishProgress` вместо перегрузки mux-полей.

## 2. Изоляция и разрешения

- Разрешения в `smarttubetv/src/stvot/AndroidManifest.xml`:
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`
- `android:exported="false"` — служба изолирована и недоступна сторонним приложениям.
- STVOT vs Standard изоляция: в стандартном варианте (flavor `ststable`) служба `VoxDownloadService` полностью отсутствует в релизном манифесте.
- `YandexVotTestReceiver`: присутствует только в debug-манифесте (`smarttubetv/src/debug/AndroidManifest.xml`) с `exported="false"`, полностью отсутствует в релизных манифестах `stvotRelease` и `ststableRelease`.

## 3. Результаты приёмочного тестирования

1. **Unit-тесты**:
   - `:common:testStvotDebugUnitTest` — PASS (624 теста, включая `VoxDownloadServiceTest` на валидацию ID, классификацию состояний, правила автовозобновления и расчет прогресса).
   - `:smarttubetv:assembleStvotDebug` — PASS.
   - `:smarttubetv:assembleStstableDebug` — PASS.
   - `:smarttubetv:processStvotReleaseManifest` / `:smarttubetv:processStstableReleaseManifest` — PASS.
   - `services/yandex-oauth-broker` — 14/14 PASS.

2. **Эмулятор `Vox-TV-14` (Android 14 / API 34 / `emulator-5554`)**:
   - Защита от orphan-сервиса: передача `unknown-job-123` и `../bad` приводит к мгновенной остановке службы без появления фантомных уведомлений.
   - Фоновая работа при HOME: после запуска загрузки `UF8uR6Z6KLc` и нажатия HOME (`keyevent 3`) служба работает в режиме `isForeground=true, foregroundId=55001, types=00000001 (DATA_SYNC)`, скачивание непрерывно продолжается в фоне (размер видеопотока вырос с 0 до 262 144 байт за 30 секунд).
   - Принудительное завершение процесса (`am force-stop`): после перезапуска приложения задача переходит в безопасное восстанавливаемое состояние `PAUSED` (сохранен частичный файл 344 064 байт), фантомная служба не создается.
   - Ручное возобновление (`ACTION_RESUME_DOWNLOAD`): задача успешно подхватывает ранее скачанный диапазон через HTTP Range (размер вырос с 344 064 до 1 933 312 байт).
   - Параллельное воспроизведение: во время фоновой загрузки ролика A запуск ролика B (`dQw4w9WgXcQ`) и активация Standard VOX на ролике B проходят штатно (`VideoLoaderController: Loading regular video in dash format...`) без ошибок и без влияния на фоновую загрузку.
   - Отмена через службу: вызов `ACTION_CANCEL_DOWNLOAD` переводит задачу в `CANCELLED`, удаляет временные файлы и останавливает `VoxDownloadService`.

3. **Физический телевизор (`192.168.2.114:5555` / Android 14 / TCL)**:
   - Установка APK `SmartTube_vot_32.56-vot.4_armeabi-v7a.apk` — SUCCESS.
   - Фоновое скачивание на HOME 60 секунд: после запуска загрузки `UF8uR6Z6KLc` и нажатия HOME скачивание выполнялось непрерывно 60 секунд (`videoBytes` увеличились с 0 до 868 352 байт).
   - Воспроизведение обычных YouTube VOD во время фоновой загрузки:
     - `dQw4w9WgXcQ` — PASS (dash формат, `ExoPlayerImpl: Init [G08, Smart TV Pro, TCL, 34]`).
     - `jNQXAC9IVRw` — PASS (dash формат).
   - Отмена через службу на ТВ: команда отмены перевела задачу в `CANCELLED`, очистила временные файлы и остановила службу `VoxDownloadService`.
