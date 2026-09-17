# SmartTube VOX Release Audit — v32.45-vot.9

**Дата аудита**: 2026-09-17  
**Версия**: `32.45-vot.9`  
**Код версии (versionCode)**: `2435009`  
**Базовая версия upstream**: `SmartTube 32.45`  
**Идентификатор приложения (applicationId)**: `io.github.kiryuhak.smarttubevot.stable`  

---

## 1. Git Repository Audit

- **Текущая ветка**: `main`
- **Удалённые репозитории**:
  - `origin`: `https://github.com/Kiryuhak/SmartTube-Vox.git`
  - `upstream`: `https://github.com/yuliskov/SmartTube.git`
- **Состояние рабочего дерева (Working Tree)**:
  - Исходный код ядра и UI/UX: без изменений.
  - Нет незакоммиченных изменений в коде.
  - Нет временных, промежуточных или отладочных файлов.
- **История последних 10 коммитов (`git log -10`)**:
  1. `6878dca` — Обновлён CHANGELOG.md: актуализированы изменения выпуска 32.45-vot.9
  2. `c49443d` — Усилены проверки состояния VOT, политики аудиодорожек и UX отката живого голоса
  3. `77889aa` — Добавлен резервный переход с живых голосов на обычную озвучку
  4. `eaecf91` — Добавлен резервный переход с живых голосов на обычную озвучку
  5. `deadc28` — Подготовлен выпуск SmartTube VOX 32.45-vot.9
  6. `7c8cf3d` — Убраны песочные часы из времени окончания видео
  7. `beaada7` — Объединены исправления безопасного сетевого логирования
  8. `a52b94e` — Настроен доступ CI к исправлению SharedModules
  9. `13f058e` — Исключено логирование перенаправлений SABR
  10. `564776c` — Исключены URL из сетевых ошибок VOX

---

## 2. Release APK Audit

Сборка выполнена задачей Gradle: `:smarttubetv:assembleStvotRelease`.

### Список проверенных артефактов APK
| Файл | Размер (байт) | Архитектура | Статус |
| :--- | :--- | :--- | :--- |
| `SmartTube_vot_32.45-vot.9_universal.apk` | 23 887 076 | Universal (все ABI) | **PASS** |
| `SmartTube_vot_32.45-vot.9_arm64-v8a.apk` | 18 843 358 | arm64-v8a | **PASS** |
| `SmartTube_vot_32.45-vot.9_armeabi-v7a.apk` | 18 106 726 | armeabi-v7a | **PASS** |
| `SmartTube_vot_32.45-vot.9_x86.apk` | 19 092 568 | x86 | **PASS** |

### Метаданные пакетов (`aapt dump badging`)
- **applicationId**: `io.github.kiryuhak.smarttubevot.stable` (подтверждено во всех 4 APK)
- **versionCode**: `2435009` (подтверждено во всех 4 APK)
- **versionName**: `32.45-vot.9` (подтверждено во всех 4 APK)
- **minSdkVersion**: `21`
- **targetSdkVersion**: `34`
- **compileSdkVersion**: `34`

### Верификация цифровой подписи (`apksigner verify --verbose --print-certs`)
- **Статус проверки**: `Verifies: true`
- **Схемы подписи**: v1 (JAR signing) — `true`, v2 (APK Signature Scheme v2) — `true`
- **Субъект сертификата (DN)**: `CN=SmartTube VOT, OU=SmartTube VOT, O=SmartTube VOT, L=Sterlitamak, ST=Sterlitamak, C=RU`
- **SHA-256 отпечаток сертификата в APK**:
  `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d`
- **SHA-256 отпечаток ключа production keystore**:
  `E0:7A:27:09:7E:3E:D7:B7:4B:3A:CE:B4:57:34:8E:84:47:49:30:A0:50:E4:F2:E2:1D:4A:64:65:BD:38:3A:2D`
- **Результат сравнения**: 100% совпадение с официальным production keystore.

---

## 3. Security Audit

Проведён статический анализ репозитория по ключевым паттернам безопасности:
- `Authorization`: используются только безопасные runtime-заголовки запроса токена Яндекс ID и закомментированные фрагменты upstream; хардкода нет.
- `Bearer`: вхождения отсутствуют (0 совпадений).
- `OAuth`: используется протокол авторизации Яндекс ID через QR-код/код устройства; секреты клиентов и токены пользователей в коде отсутствуют.
- `secret`: константы относятся к стандартным криптографическим классам Java (`SecretKeySpec`) и динамическим сессионным полям протокола VOT; секретные ключи сервера отсутствуют.
- `token`: токены передаются исключительно в динамических сессиях в памяти устройства, в логах маскируются (`token != null ? "present" : "absent"`).
- `cookie`: вхождения отсутствуют (0 совпадений).
- `debug logs`: логирование подписанных URL потока отключено, очистка URL от параметров авторизации и подписей (SABR sanitizer) активна.

---

## 4. OTA Manifest Audit (`smarttube_vox.json`)

- **Файл**: `smarttube_vox.json` (в корне и в директории `updates/`)
- **Старые префиксы**: `smarttube_vot` полностью исключены, используется `smarttube_vox`.
- **Содержимое манифеста**:
  - `downloadUrl`: `https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.apk`
  - `versionName`: `32.45-vot.9`
  - `versionCode`: `2435009`
  - `changelog_ru`:
    - Улучшена обработка Lively Voice fallback
    - Добавлены проверки состояния VOT
    - Улучшена стабильность аудиодорожек
    - Исправлены таймеры перевода
    - Улучшены проверки ошибок Yandex VOT

---

## 5. Changelog Audit (`CHANGELOG.md`)

- Раздел `32.45-vot.9` синхронизирован исключительно с фактическими проверенными изменениями.
- Отсутствуют нереализованные функции, будущие планы и неподтверждённые исправления.

---

## Итог аудита
Все этапы технического аудита релиза успешно пройдены.