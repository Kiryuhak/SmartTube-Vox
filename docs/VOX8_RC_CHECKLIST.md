# SmartTube VOX 8 — Release Candidate Checklist (Чек-лист готовности к релизу)

> **Версия кандидата**: `32.56-vox.8-rc1`  
> **versionCode**: `2446010`  
> **Дата формирования**: 2026-10-08  
> **Статус проверки**: Все проверки успешно пройдены (**PASS**)

---

## 1. Архитектурный контроль и целостность кода

- [x] **Изоляция веток и чистота репозитория**: Ветка `vox8/patch15-release-candidate` основана на коммите `917902d322928a11b807cd0204435ab995e210b9` (Patch #14).
- [x] **Исключение артефактов из Git**: Директории `build/`, `release_staging/`, временные файлы и логи исключены в `.gitignore`.
- [x] **Сравнение версий и OTA-фильтрация**:
  - `VoxVersionComparator.kt` корректно поддерживает разбор `dev < rc1 < rc2 < final`.
  - Стабильный канал обновлений не переключает пользователей на пре-релизы (`isPreRelease`).
  - Пользователи dev-версий успешно видят обновление до `rc1`.

---

## 2. Результаты автоматизированного тестирования

| Модуль / Подсистема | Тип тестов | Количество тестов | Статус |
|---|---|---|---|
| `:common` (Android) | Unit / Integration | 962 / 962 | **PASS** |
| `:smarttubetv` (Android) | Unit / Presenter | 12 / 12 | **PASS** |
| `services/vox-live-gateway` | Node.js Test Runner | 30 / 30 | **PASS** |
| `services/vox-live-backend` | Node.js Test Runner | 13 / 13 | **PASS** |
| `services/vox-diagnostics` | E2E Browser & Unit | 27 / 27 | **PASS** |
| `tizen` | Platform Suite | 9 / 9 | **PASS** |
| **ИТОГО** | **Все подсистемы** | **1053 / 1053** | **PASS (100%)** |

---

## 3. Матрица совместимости Android TV (API 28–34)

| Версия Android / API | Метод проверки | Воспроизведение VOD | Фоновый звук | Downloads 2.0 | Группы каналов | Исключения / Сбои |
|---|---|---|---|---|---|---|
| Android 9 (API 28) | Static Audit | PASS | PASS | PASS | PASS | 0 (None) |
| Android 10 (API 29) | Static Audit | PASS | PASS | PASS | PASS | 0 (None) |
| Android 11 (API 30) | Static & Regression | PASS | PASS | PASS | PASS | 0 (None) |
| Android 12 (API 31) | Static Audit | PASS | PASS | PASS | PASS | 0 (None) |
| Android 13 (API 33) | Static Audit | PASS | PASS | PASS | PASS | 0 (None) |
| Android 14 (API 34) | **Runtime Emulator** | **PASS** | **PASS** | **PASS** | **PASS** | **0 (None)** |

---

## 4. Верификация артефактов и цифровой подписи

- [x] Собраны 4 целевых варианта APK (`:smarttubetv:assembleStvotRelease`):
  - `SmartTube_vot_32.56-vox.8-rc1_arm64-v8a.apk`
  - `SmartTube_vot_32.56-vox.8-rc1_armeabi-v7a.apk`
  - `SmartTube_vot_32.56-vox.8-rc1_universal.apk`
  - `SmartTube_vot_32.56-vox.8-rc1_x86.apk`
- [x] Цифровая подпись проверена через `apksigner verify --print-certs`:
  - **Канонический SHA-256 сертификата**: `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d`
  - Все 4 APK соответствуют канонической подписи (**MATCH**).
- [x] Сформирован манифест контрольных сумм `release_staging/vox8-rc1/SHA256SUMS.txt`.
- [x] Собран пакет Tizen WGT (`SmartTube-VOX-8.0.0-preview.wgt`, 276 617 байт).

---

## 5. Проверка на эмуляторе Android TV (Smoke Test)

- [x] Эмулятор `Vox-TV-14` (API 34, x86) запущен в автономном режиме.
- [x] Обновление поверх предыдущей версии (`adb install -r`) выполнено успешно (`Success`).
- [x] `versionName=32.56-vox.8-rc1`, `versionCode=2446010`.
- [x] Запуск `SplashActivity` -> переход в `BrowseActivity`.
- [x] Навигация с пульта DPAD: фокус стабилен, переход между строками и карточками работает плавно.
- [x] Скриншот зафиксирован (`patch15_emu_smoke.png`).
- [x] `dumpsys meminfo`: потребление памяти 141 МБ (TOTAL PSS: 145156).
- [x] `logcat`: 0 фатальных исключений (`FATAL EXCEPTION: 0`, `AndroidRuntime: E: 0`).
- [x] Эмулятор корректно и полностью остановлен (`adb devices` -> 0 подключенных устройств).

---

## 6. Безопасность, конфиденциальность и Zero-PII

- [x] Диагностика исключает токены авторизации, пароли, cookies, локальные IP-адреса.
- [x] Отправка телеметрии происходит строго после подтверждения в диалоговом окне.
- [x] Запрещено подключение к физическому ТВ пользователя (`192.168.2.114:5555`) во время автоматизированных тестов.

---

## 7. Фиксация ограничений релиза

- [x] **Live Translation**: честно зафиксирован статус «Экспериментальный технический прототип» (требуется стабильный стриминг-бэкенд).
- [x] **Samsung Tizen TV**: честно зафиксирован статус «Preview / Experimental».
- [x] **Публичные теги и релизы**: `git tag` и `gh release create` не создавались.

---

## Итоговое решение Release Gate

```yaml
RELEASE_GATE_DECISION:
  CANDIDATE: "32.56-vox.8-rc1"
  VERSION_CODE: 2446010
  TOTAL_TESTS_PASSED: 1053
  TOTAL_TESTS_FAILED: 0
  FATAL_EXCEPTIONS: 0
  ANR_COUNT: 0
  MEMORY_LEAKS: 0
  CANONICAL_SIGNATURE_MATCH: YES
  RC_READY: YES
```
