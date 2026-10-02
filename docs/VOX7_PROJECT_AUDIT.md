# SmartTube VOX 7 — Журнал аудита и план разработки

## Статус спринта

- **Поколение**: VOX 7
- **Текущий патч**: Patch #5 (Предрелизная стабилизация и верификация сборки VOX 7)
- **Статус патча**: Готов к финальной ТВ-приёмке (VOX7_PATCH5_READY_FOR_FINAL_TV)
- **Дата релиза спринта**: 2026-10-16

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
