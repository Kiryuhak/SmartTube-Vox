# SmartTube VOX 8 — Patch #19: Стабилизация Release Candidate на эмуляторе

## 1. Базовая информация
- **Base Commit**: `70c5bc3d73492a2dd63349a57c5f1cbbfc564193` (Patch #18 merge)
- **Branch**: `vox8/patch19-rc-emulator-stabilization`
- **App Version**: `32.56-vox.8-rc1` (versionCode `2446010`)
- **Эмулятор**: `Vox-TV-14` (Google TV / Android TV, Android 14, API 34 x86)
- **Физический TV**: НЕ использовался (`PHYSICAL_TV_TESTED: NO`, `PHYSICAL_TV_FINAL_ACCEPTANCE: PENDING`)

---

## 2. Реализованные изменения и аудит
1. **Live Watchdog & Stall Recovery**:
   - Проведён детальный аудит контроллера `VoxLiveStallRecoveryController.kt` и интеграции в `ExoPlayerController.java`.
   - Подтверждены переходы состояний: `IDLE` -> `BUFFERING_OBSERVED` -> `STALL_DETECTED` -> `REFRESHING_MANIFEST` -> `RESEEKING` -> `RECREATING_SOURCE` -> `FAILED`.
   - Защита от бесконечного переключения сетевых движков: `NetworkEngineRecoveryPolicy` с ограничением частоты (grace period 60с, cooldown предыдущего движка 120с, бюджет 2 переключения за 5 мин).
   - Расширены модульные тесты: игнорирование короткой буферизации (< 3.5с), переход в `FAILED` при превышении лимита попыток, anti-thrashing cooldown, безопасный clampseek при маленьких окнах трансляции, сброс состояния при смене источника.

2. **Единый арбитраж оверлеев (Single Download Status)**:
   - Подтверждена архитектура `VoxStatusHost`: загрузка (`DOWNLOAD = 2`) имеет строгий приоритет над переводом (`TRANSLATION = 1`), исключая одновременное появление нескольких плашек статуса на экране.
   - Одиночный экземпляр `VoxDownloadOverlay` обновляет состояние внутри одной плашки (`PREPARING`, `DOWNLOADING`, `MUXING`, `COMPLETED`, `FAILED`, `CANCELLED`).

3. **Комплексные тесты на эмуляторе Android TV (API 34)**:
   - **Live Soak Test**: 35 минут непрерывного воспроизведения прямого эфира без единого сбоя, ANR или падения.
   - **Live DVR Matrix**:
     - Перемотка назад -30с: успешный переход и продолжение воспроизведения.
     - Перемотка назад -2мин: стабильное воспроизведение из буфера DVR.
     - Глубокая перемотка -5мин: корректное позиционирование без бесконечного спиннера.
     - Возврат к прямому эфиру: мгновенный переход к live edge.
   - **VOD Regression & Player Stability**:
     - 10 циклов background/foreground/play/pause без утечек и дублирования плеера.
     - 350 случайных событий DPAD (стресс-тест интерфейса) без зависаний фокуса и крашей.

---

## 3. Метрики ресурсов и стабильности

| Метрика | Значение |
|---|---|
| **Длительность Live Soak** | 35 минут (непрерывно) |
| **События Stall/Unbounded** | 0 unbounded stalls |
| **Переключения сетевого движка** | 0 (стабильное удержание) |
| **FATAL Exceptions** | 0 |
| **ANR** | 0 |
| **OutOfMemoryError** | 0 |
| **PSS Start** | 94.2 MB (96 474 kB) |
| **PSS Peak** | 507.0 MB (активный 35-мин Live стриминг) |
| **PSS End** | 75.4 MB (77 185 kB) |
| **Потоки (Threads)** | 51 -> 47 |
| **Дескрипторы файлов (FD)** | 145 -> 146 |

---

## 4. Результаты тестовых наборов

- **Android Unit Tests**: 987 / 987 PASS (`:common:testStvotDebugUnitTest`, `:smarttubetv:testStvotDebugUnitTest`)
- **Diagnostics Worker**: 41 / 41 PASS (`services/vox-diagnostics`)
- **Live Gateway**: 30 / 30 PASS (`services/vox-live-gateway`)
- **Live Backend**: 13 / 13 PASS (`services/vox-live-backend`)
- **Yandex OAuth Broker**: 14 / 14 PASS (`services/yandex-oauth-broker`)
- **Tizen**: 9 / 9 PASS (`tizen`)

---

## 5. Статус готовности к релизу (Release Readiness)
- **Автоматические тесты и эмулятор**: `PASS`
- **Физический Android TV**: `PENDING` (остаётся отдельным финальным этапом)
- **Samsung Tizen TV**: `NOT_TESTED`
- **Публичные релизы / теги**: `NONE` (версия сохранена: `32.56-vox.8-rc1`, код: `2446010`).
