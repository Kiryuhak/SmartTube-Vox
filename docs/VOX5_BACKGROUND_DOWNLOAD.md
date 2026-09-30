# VOX 5 — фоновый сервис скачивания и управление процессами (Patch #7)

## 1. Архитектура фонового сервиса

`VoxDownloadService` реализует надежное выполнение загрузки и мультиплексирования видео со звуковым переводом Яндекс VOT при сворачивании приложения, переходе на домашний экран Android TV (HOME) или переключении на другие приложения.

### Ключевые компоненты:
1. **`VoxDownloadService` (`common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadService.kt`)**:
   - `ForegroundService` с типом `android:foregroundServiceType="dataSync"` (требование Android 14 / API 34).
   - `START_NOT_STICKY`: предотвращает некорректный автозапуск с пустым `Intent` при системном завершении процесса; активные задачи восстанавливаются репозиторием `VoxDownloadRepository`.
   - Автоматическая остановка (`stopForeground` + `stopSelf`) при завершении всех активных задач (COMPLETED, FAILED, CANCELLED, PAUSED).
   - Безопасная валидация `downloadId` регулярным выражением `^[a-zA-Z0-9_-]{1,64}$`.

2. **Канал уведомлений и оповещения (`NotificationChannel`)**:
   - ID канала: `smarttube_vox_downloads_channel` (`IMPORTANCE_LOW`, без навязчивого звука и вибрации).
   - Ongoing-уведомление прогресса (`NOTIFICATION_ID_PROGRESS = 55001`) с кнопкой «Отменить» (`ACTION_CANCEL_DOWNLOAD`) и открытием приложения по нажатию.
   - Финальные уведомления завершения (`NOTIFICATION_ID_COMPLETED = 55002`) и ошибки (`NOTIFICATION_ID_FAILED = 55003`) с `autoCancel=true`.

3. **Интеграция с диалоговым интерфейсом (`VoxDownloadDialogHelper.kt`)**:
   - Запуск сервиса при создании/перезапуске задания (`VoxDownloadService.start(context, downloadId)`).
   - Отмена через сервис (`VoxDownloadService.cancel(context, downloadId)`).

4. **Прогресс публикации MediaStore (`VoxDownloadPublisher.kt` & `VoxDownloadCoordinator.kt`)**:
   - Коллбэк прогресса `onProgress: ((bytesCopied, totalBytes, percent) -> Unit)` при копировании финального MKV из внутреннего кэша в Scoped Storage / Legacy Storage.

## 2. Изоляция и разрешения

- Разрешения в `smarttubetv/src/stvot/AndroidManifest.xml`:
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`
- `android:exported="false"` — сервис недоступен сторонним приложениям.
- STVOT vs Standard изоляция: в стандартном варианте (flavor `ststable`) сервис не регистрируется и не используется.

## 3. Результаты приёмки

1. **Unit-тесты**:
   - `:common:testStvotDebugUnitTest` — PASS (624 теста, включая `VoxDownloadServiceTest`).
   - `:smarttubetv:assembleStvotDebug` — PASS.
   - `:smarttubetv:assembleStstableDebug` — PASS.
   - `services/yandex-oauth-broker` — 14/14 PASS.

2. **Эмулятор (`Vox-TV-14` / Android 14 / API 34)**:
   - Старт загрузки -> нажатие HOME -> загрузка продолжается в фоне -> `isForeground=true, foregroundId=55001, types=00000001 (DATA_SYNC)`.
   - Уведомление с кнопкой «Отменить» отображается.
   - Отмена корректно останавливает сервис, удаляет временные файлы и очищает состояние.
   - Воспроизведение обычных YouTube VOD (`dQw4w9WgXcQ`) не нарушено.

3. **Физический телевизор (`192.168.2.114:5555` / Android 14 / TCL)**:
   - Установка `SmartTube_vot_32.56-vot.4_armeabi-v7a.apk` — SUCCESS.
   - Воспроизведение YouTube VOD (`dQw4w9WgXcQ`) — PASS (`ExoPlayerImpl: Init [G08, Smart TV Pro, TCL, 34]`).
   - Фоновая служба и жизненный цикл работают штатно.
