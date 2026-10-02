# SmartTube VOX 7 — Журнал аудита и план разработки

## Статус спринта

- **Поколение**: VOX 7
- **Текущий патч**: Patch #1 (Изолированный прокси для VOX / Яндекс перевода)
- **Статус патча**: В разработке / Готов к проверке

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
