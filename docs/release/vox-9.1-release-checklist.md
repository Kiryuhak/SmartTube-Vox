# SmartTube Vox 9.1 — Чек-лист готовности к релизу

> **Целевая версия:** 32.56-vox.9.1  
> **VersionCode:** 2446016  
> **Пакет:** `io.github.kiryuhak.smarttubevot.stable`  

---

## Статус проверок

- [x] **Чистота кода (Code Clean):** отсутствие нерелевантных правок, защита ключевых зон (VOT бэкенд, OTA HUD, Translation HUD).
- [x] **Сборка APK (APK Builds):** чистая компиляция `assembleStvotDebug` и воспроизводимость сборки.
- [x] **Подпись APK (APK Signing):** проверка `apksigner verify` (схемы v1, v2, v3, SHA-256 fingerprint).
- [x] **Аудит секретов (Secrets Audit):** отсутствие отслеживаемых приватных ключей, токенов ботов и паролей в git.
- [x] **Player Recovery Regression:** прохождение всех тестов восстановления, устранение вечного спиннера, обработка HTTP 403.
- [x] **Telegram Diagnostics Regression:** строгая валидация статусов отправки и квитанций Telegram API.
- [x] **Adaptive Audio & Hindi Coverage:** прохождение всех 8 тестов адаптивного микшера, RMS-дакинга и телеметрии.
- [x] **Translation Regression:** прохождение тестов адаптивного ожидания длинных видео (L1-L5) и граничного теста 4h (14400s).
- [x] **OTA Regression:** прохождение тестов совместимости манифестов, валидации пакетов и каналов.
- [x] **Полный набор модульных тестов (Full Unit Suite):** все тесты `:common:testStvotDebugUnitTest` пройдены.
- [x] **Smoke-тестирование на эмуляторе (Emulator Smoke):** стабильный запуск на `VOX_TV_API34`, навигация, видео, отсутствие утечек памяти (OOM/ANR = NO).
- [x] **Материалы релиза:**
  - [x] Changelog готов (`docs/release/vox-9.1-changelog.md`)
  - [x] Заметки о выпуске готовы (`docs/release/vox-9.1-release-notes.md`)
  - [x] Известные ограничения зафиксированы (`docs/release/vox-9.1-known-limitations.md`)
  - [x] OTA Manifest Dry Run сформирован (`build/reports/release-prep/ota-manifest-dry-run.json`)
  - [x] Контрольные суммы файлов SHA-256 сформированы (`build/reports/release-prep/artifact-checksums.txt`)
- [ ] **Финальный регрессионный гейт на физическом ТВ (Physical TV Final Release Gate):**
  - Предыдущий тест OTA HUD на физическом TCL: **PASS** (проведён ранее).
  - Пост-харденинг регрессионный прогон (после коммитов Player Recovery, Telegram E2E, Adaptive Audio): **PENDING** (требуется отдельный финальный прогон перед публикацией релиза).
