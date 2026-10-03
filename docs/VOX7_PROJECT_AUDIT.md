# SmartTube VOX 7 — Журнал аудита и план разработки

## Статус спринта

- **Поколение**: VOX 7
- **Текущий патч**: Patch #9 (Единая платформа Android TV + Samsung Tizen, профили возможностей устройств, политика кодеков, диагностика)
- **Статус патча**: Готов к PR (VOX7_UNIFIED_FOUNDATION_READY)
- **Дата релиза спринта**: 2026-10-16

---

## 9. Единая платформа Android TV + Samsung Tizen (Patch #9)

- **Контекст**: Объединение SmartTube VOX для Android TV / Google TV и Samsung Tizen в единый репозиторий и продуктовый ряд.
- **Решение**:
  - Разработана кроссплатформенная трисостоятельная модель аппаратных возможностей (`vox-device-profile-v1`, `TriStateCapability`, `VoxDeviceProfile`).
  - Реализованы политики выбора кодеков (`AUTO`, `MAX_QUALITY`, `MAX_COMPATIBILITY`, `CUSTOM`) в `VoxCodecPolicy`.
  - Для Android TV реализовано автосканирование возможностей устройства (`MediaCodecList`, `AudioTrack`, `DisplayManager`), диалог первого запуска и пункт настроек «Совместимость устройства» (`VoxCompatibilitySettingsPresenter`).
  - Добавлен генератор очищенных отчётов диагностики совместимости (`VoxDiagnosticsPresenter`, маскирование чувствительных данных).
  - Создана независимая clean-room основа веб-приложения Samsung Tizen (`tizen/`) с поддержкой D-Pad навигации, оверлея VOX, синхронизации перевода (допуск 150 мс), диалогов диагностики и тестов.
  - Подготовлен инструмент сборки пакета `build-wgt.js` с прозрачной обработкой отсутствия локального Tizen CLI (`TIZEN_WGT_NOT_RUN_TOOLCHAIN_MISSING`).
  - Полностью сохранены все существующие функции VOX 7: загрузки (HUD, боковое меню, настройки), бейджи качества, чат и надёжность перевода VOT.
  - Написана подробная архитектурная документация в `docs/VOX7_UNIFIED_PLATFORM_ARCHITECTURE.md`.
- **Статус**: DONE
- **Файлы изменений**:
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/capability/*`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/VoxCompatibilitySettingsPresenter.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/VoxDiagnosticsPresenter.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/utils/VoxCompatibilityOnboardingHelper.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/AppDataSourceManager.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/VotData.java`
  - `common/src/main/res/values/strings.xml`, `common/src/main/res/values-ru/strings.xml`
  - `tizen/*`
  - `docs/VOX7_UNIFIED_PLATFORM_ARCHITECTURE.md`
  - `docs/VOX7_PROJECT_AUDIT.md`

---

## 8. Консолидированные отзывы форума (Patch #8)

- **Контекст**: Реализация предложений и устранение дефектов из обратной связи пользователей форума по 4 подсистемам (загрузки, кнопка HUD, чат, аудио, перевод).
- **Решение**:
  - Улучшена обнаруживаемость раздела загрузок (плитка в настройках, закрепление в боковом меню, понятное пустое состояние).
  - В оверлей плеера добавлена кнопка «Скачать» (`VideoPlayerGlue`, `DownloadAction`, `VoxDownloadPlayerController`, настройка `PLAYER_BUTTON_DOWNLOAD`).
  - Расширено распознавание кодеков AC3 (`ac-3`, `ac3`), EAC3 (`ec-3`, `eac3`), Opus и AAC в `TrackSelectorUtil`.
  - Исправлен сбой перевода («работает только ~10% видео») путём автоопределения исходного языка речи (устранена принудительная подстановка `"en"`).
  - Улучшена классификация и локализация ошибок перевода через `VotErrorCategory`.
  - Добавлено переключение режимов онлайн-чата («Интересные сообщения» / «Все сообщения») в `ChatController` и `PlayerData`.
  - Добавлены юнит-тесты и пройдено тестирование на эмуляторе Android TV (API 34).
- **Статус**: DONE
- **Файлы изменений**:
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/DownloadAction.java`
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/other/VideoPlayerGlue.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/VoxDownloadPlayerController.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/VoiceTranslateController.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/ChatController.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/selector/TrackSelectorUtil.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/PlayerData.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/PlayerTweaksData.java`
  - `docs/VOX7_FORUM_FEEDBACK.md`
  - `docs/VOX7_PROJECT_AUDIT.md`

---

## 6. Длительное стресс-тестирование на эмуляторе (Patch #6)

- **Контекст**: Комплексный прогон регрессионных и нагрузочных сценариев VOX 7 на эмуляторе Android TV (API 34) перед физической ТВ-приёмкой.
- **Решение**:
  - Выполнено 20 циклов навигации D-Pad по всем ключевым разделам (лента, боковое меню, скачанное видео, настройки, плеер, VOX).
  - Выполнено 10 циклов принудительного перезапуска процесса (`force-stop`).
  - Проверена динамика памяти: PSS_0=118 МБ, PSS_1=135 МБ, PSS_2=142 МБ, PSS_3=142 МБ, PSS_IDLE=130 МБ (подтвержден статус PLATEAU_OBSERVED, утечек нет).
  - Проверен полный цикл загрузок на реальном runtime: запуск, защита от дубликатов, постановка в очередь, отмена, перезапуск, прерывание сети и force-stop.
  - Проверена изоляция прокси (fail-closed для Яндекса, YouTube напрямую).
  - Проверено отсутствие артефактов переиспользования бейджей при быстром скролле.
  - Проведен аудит безопасности логов, отсутствия зомби-сервисов и уведомлений.
- **Статус**: DONE
- **Файлы изменений**:
  - `docs/VOX7_EMULATOR_SOAK.md`
  - `docs/VOX7_PROJECT_AUDIT.md`

---

## 5. Предрелизная стабилизация и верификация сборки VOX 7 (Patch #5)

- **Контекст**: Сборка, верификация и тестирование на реальном эмуляторе Android TV всех подсистем VOX 7 перед физической приёмкой на ТВ.
- **Решение**:
  - Обновлён парсер суффиксов в `smarttubetv/build.gradle` (`-(?:vot|vox)\.(\d+)`) и выставлен `votVersionSuffix=-vox.7-dev` с корректным `versionCode` (2446007).
  - Проверена изоляция пакета `io.github.kiryuhak.smarttubevot.stable` и лейбла `SmartTube VOX`.
  - Пройден полный цикл сценариев: холодный старт, библиотека скачанного, прямое локальное воспроизведение без интернета, офлайн-режим, удаление файлов извне, массовая очистка диска, пустое состояние.
  - Проведен аудит безопасности Logcat на отсутствие утечек OAuth-токенов.
  - Проверена отказоустойчивость селективного VOX-прокси и изоляция прямого трафика YouTube.
  - Все тесты (Java Unit Tests, JS Broker Tests) и сборки (StvotDebug, StstableDebug, StvotReleaseManifest) успешно пройдены.
- **Статус**: DONE
- **Файлы изменений**:
  - `gradle.properties`
  - `smarttubetv/build.gradle`
  - `docs/VOX7_PRERELEASE_HARDENING.md`
  - `docs/VOX7_PROJECT_AUDIT.md`

## 4. Доработка интерфейса загрузок, бейджей и навигации (Patch #4)

- **Контекст**: Комплексный аудит и финализация UI/UX для загрузок, библиотеки медиа, бейджей качества видео, селективного прокси и авторизации после релиза hotfix 6.1.
- **Решение**:
  - Улучшены строковые ресурсы статуса авторизации («Яндекс ID: Авторизация сохранена»).
  - Проверена интеграция библиотеки «Скачанные видео» в боковом меню и контекстного меню «Скачать с переводом».
  - Проверена логика предложения удаления видео после окончания просмотра (`isLocal`).
  - Проверена защита от нехватки дискового пространства (<300 МБ) с предупреждением.
  - Проверено наложение бейджей качества (`4K`, `2K`, `FHD`, `HD`, `SD`) без искажения сетки и без лишних сетевых запросов.
  - Проведен аудит аппаратных возможностей (кодеки VP9/AV1/AVC, 4K, AFR, разблокировка форматов).
  - Проверено разделение autoplay превью карточек и autoplay рекомендаций плеера.
  - Пройдено тестирование на эмуляторе Android TV (API 34).
- **Статус**: DONE
- **Файлы изменений**:
  - `common/src/stvot/res/values-ru/strings.xml`
  - `common/src/stvot/res/values/strings.xml`
  - `docs/VOX7_UI_HARDENING.md`
  - `docs/VOX7_PROJECT_AUDIT.md`

---

## 3. Сохранение авторизации Яндекс ID и отказоустойчивое шифрование (Patch #3)

- **Проблема**: После прохождения Device Code авторизации (`ya.ru/device`) статус оставался «Не авторизован» из-за исключения `AndroidKeyStore` (`InvalidAlgorithmParameterException: Caller-provided IV but use of caller-provided IVs not permitted`) и отсутствия fallback-ключа на API 23+.
- **Решение**:
  - Исправлена инициализация AES-GCM шифрования: для `AndroidKeyStore` IV генерируется аппаратно и извлекается через `cipher.getIV()`, без передачи внешнего `GCMParameterSpec` при шифровании.
  - Добавлен автоматический fallback на 256-битный закрытый AES-ключ приложения (`vot_sec_key.bin`) при сбоях или недоступности `AndroidKeyStore`.
  - В диалоге `YandexDeviceAuthDialog` добавлена верификация успешности сохранения токена в `VotData` перед закрытием.
  - Подключен селективный VOX-прокси в `YandexBrokerClient` через `VoxHttpClientFactory`.
  - Очищен интерфейс `PlayerSettingsPresenter`: устранены дублирующиеся кнопки авторизации и обеспечено закрытие диалога настроек при открытии OAuth.
- **Статус**: DONE (Влито в `main` через PR #56 и бэкпортировано в Hotfix 6.1)
- **Файлы изменений**:
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/oauth/YandexOAuthTokenStore.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/oauth/YandexBrokerClient.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/oauth/YandexDeviceAuthDialog.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/PlayerSettingsPresenter.java`
  - `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/oauth/YandexOAuthTokenStoreTest.java`
  - `docs/VOX7_YANDEX_AUTH.md`

---

## 2. Библиотека скачанных видео и интерфейс загрузок (Patch #2)

- **Проблема**: Загрузки в VOX 5 работали в фоне, но на TV не было удобного раздела для просмотра всех скачанных материалов, контроля свободного места на диске и простого управления воспроизведением/удалением.
- **Решение**:
  - Создан раздел «Скачанные видео» в основном боковом меню приложения.
  - Кнопка «Скачать» в контекстном меню видео стала адаптивной к текущему состоянию загрузки.
  - Реализованы карточки скачанных материалов с отображением статуса, размера, качества и режима озвучки.
  - Поддержано управление с пульта (D-Pad): воспроизведение по клику, меню действий и массовое удаление по долгому нажатию.
  - Добавлена настройка предложения удаления видео после окончания просмотра.
  - Реализованы бейджи качества (4K, 2K, FHD, HD, SD) на карточках видео.
- **Статус**: DONE
- **Файлы изменений**:
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/BrowsePresenter.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/service/SidebarService.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/dialogs/menu/VideoMenuPresenter.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/VideoLoaderController.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/badge/VoxBadgeHelper.kt`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadCoordinator.kt`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadDialogHelper.kt`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadStorage.kt`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/VotData.java`
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/presenter/VideoCardPresenter.java`
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/widgets/complexcardview/ComplexImageCardView.java`
  - `smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/widgets/complexcardview/ComplexImageView.java`
  - `docs/VOX7_DOWNLOAD_LIBRARY_UI.md`

---

## 1. Селективный прокси для VOX (Patch #1)

- **Проблема**: Блокировка или нестабильность запросов к бэкенду Яндекс VOT (`api.browser.yandex.ru`, `vtrans.s3.yandex.net`, `oauth.yandex.com`) на уровне некоторых интернет-провайдеров при сохранении прямого доступа к YouTube.
- **Решение**: Изолированная архитектура `VoxProxyConfig` + `VoxHttpClientFactory` с поддержкой HTTP и SOCKS5 прокси, per-client аутентификацией и раздельным скачиванием дорожек.
- **Статус**: DONE
- **Файлы изменений**:
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/proxy/VoxProxyConfig.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/proxy/VoxHttpClientFactory.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/proxy/VoxProxyDialog.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/VotData.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/VotHttp.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/yandex/YandexVotApiClient.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/yandex/SmartTubeYandexVotAudioStreamReader.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/oauth/YandexDeviceCodeClient.java`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/download/VoxDownloadCoordinator.kt`
  - `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/settings/PlayerSettingsPresenter.java`
  - `common/src/main/res/layout/vox_proxy_dialog.xml`
  - `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vox/proxy/VoxProxyConfigTest.java`
  - `common/src/test/java/com/liskovsoft/smartyoutubetv2/common/vox/proxy/VoxHttpClientFactoryTest.java`
  - `docs/VOX7_SELECTIVE_PROXY.md`
