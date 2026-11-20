# SmartTube VOX 11 (32.71-vox.11)

## 📦 Релизная информация / Release Metadata

| Параметр | Значение |
| :--- | :--- |
| **Релизный тег (Tag)** | `32.71-vox.11` |
| **Название выпуска** | `SmartTube VOX 11 (32.71-vox.11)` |
| **Имя пакета (Package)** | `io.github.kiryuhak.smarttubevot.stable` |
| **Код версии (versionCode)** | `2461014` |
| **Имя версии (versionName)** | `32.71-vox.11` |
| **Основной файл (Asset)** | `SmartTube_vot_32.71-vox.11_2461014_universal_SIGNED.apk` |
| **Размер файла** | **67 832 239 байт** |
| **Хэш SHA-256** | `890c7f94567c584e43a97a3a4ddb2657a4f8721dbe8fdae433bdf18e1b24aa74` |
| **Отпечаток сертификата** | `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d` |
| **Версия движка VOT** | `1.12.6` (зафиксирована / pinned) |
| **Базовая версия SmartTube** | `32.71` |
| **Поддерживаемые платформы** | Android TV, Google TV (Android 5.0 – 14.0) |
| **Поддерживаемые архитектуры** | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (Universal APK) |

---

## 🇷🇺 Описание выпуска (Russian)

Представляем крупное стабильное обновление **SmartTube VOX 11**! В этой версии кодовая база плеера синхронизирована с базовой версией SmartTube 32.71, зафиксирован стабильный движок закадрового голосового перевода VOT 1.12.6, решены ключевые проблемы запуска видеопотоков YouTube и обеспечено бесшовное обновление с предыдущих версий.

### 🌟 Главные изменения:

1. **Обновление до SmartTube 32.71:**
   - Переход на кодовую базу SmartTube 32.71 с обновлёнными декодерами, парсерами YouTube и повышенной плавностью интерфейса.
2. **Фиксация и стабилизация Voice Over Tube (VOT 1.12.6):**
   - Движок голосового перевода зафиксирован на проверенной стабильной версии 1.12.6.
   - Обеспечена надёжная работа авторизации в Яндекс ID для режима «Живой голос» и точная синхронизация русской аудиодорожки.
3. **Восстановление воспроизведения VOD-потоков (DASH / SABR Streams):**
   - Реализован механизм автоматического отката (fallback) при ошибках старта воспроизведения (HTTP 400 на ряде современных потоков YouTube) с переключением на альтернативные рабочие форматы и защитой от бесконечных циклов.
4. **Стабилизация звука при изменении скорости воспроизведения:**
   - Устранено искажение тембра и дрожание тональности звука при ускорении видео (1.25x – 2.0x) во время работы закадрового перевода.
5. **Приоритет голосового перевода Яндекс VOT:**
   - При наличии в видео встроенного русского дубляжа YouTube обеспечен корректный выбор и приоритет нейросетевого перевода Яндекс VOT.
6. **Повышенная надёжность Live / DVR трансляций:**
   - Плавная перемотка назад в прямом эфире и возврат к актуальному моменту стрима.
7. **Совместимость архитектуры пакета при обновлении (In-App OTA Compatibility):**
   - Идентичный пакет, неизменный сертификат RSA-4096 и строго возрастающий versionCode обеспечивают корректную работу Android PackageInstaller. В контрольных тестах на стенде пользовательские данные, история и сессии входа сохранились без сброса.
8. **Минимизация и санитизация диагностических данных 2.0:**
   - Полностью обновлена панель технической диагностики. В случае редких сбоев отчёт формируется строго без персональных данных и содержит короткий удобный код ошибки (`VOX-XXXX`).

---

## 🇬🇧 Release Notes (English)

We are pleased to introduce the major stable release of **SmartTube VOX 11 (32.71-vox.11)**! This version synchronizes the codebase with SmartTube 32.71 core, pins the rock-solid Voice Over Tube (VOT) 1.12.6 engine, fixes playback startup errors on modern YouTube streams, and ensures a seamless update path for all existing users.

### 🌟 Key Highlights:

1. **SmartTube 32.71 Core:**
   - Synchronized with SmartTube 32.71 media core, updated stream parsers, and improved UI responsiveness on Android TV / Google TV.
2. **Pinned Voice Over Tube (VOT 1.12.6):**
   - Pinned and verified VOT 1.12.6 engine for smooth, reliable AI voiceover generation and robust Yandex ID authentication.
3. **VOD Playback Startup Recovery (DASH / SABR):**
   - Implemented automated fallback mechanisms for investigated stream startup failure scenarios (including HTTP 400 startup errors).
4. **Audio Stability on Variable Playback Speeds:**
   - Eliminated audio pitch artifacts and stutter when watching videos with translation at 1.25x–2.0x speeds.
5. **Yandex VOT Priority:**
   - Proper precedence of neural Yandex VOT over built-in YouTube Russian dub tracks.
6. **Enhanced Live & DVR Streaming:**
   - Improved rewind stability in live buffers and instant synchronization with the live edge.
7. **Package Compatibility for Upgrades (In-App OTA Compatibility):**
   - Matching package identity, identical RSA-4096 signing certificate, and strictly increasing versionCode ensure compatibility with Android PackageInstaller. User data, history, and accounts are preserved.
8. **Privacy-Focused Diagnostics 2.0:**
   - Modernized telemetry with sanitized data minimization and convenient short report IDs (`VOX-XXXX`).

---

## 📋 Инструкция по обновлению для пользователей

### Способ 1: Автоматическое обновление по воздуху (In-App OTA)
1. В установленном приложении перейдите в **Настройки → О приложении → Проверить обновления**.
2. В появившемся окне нажмите **«Скачать и установить»**.
3. Дождитесь загрузки и подтвердите установку в системном диалоге Android.
4. Все ваши подписки, история просмотров и авторизованные аккаунты Google и Яндекс сохранятся. Не удаляйте старую версию перед обновлением.

### Способ 2: Ручная установка APK через флешку
1. Загрузите файл `SmartTube_vot_32.71-vox.11_2461014_universal_SIGNED.apk` из блока ассетов внизу страницы релиза.
2. Запишите его на USB-накопитель и подключите к ТВ или приставке.
3. Откройте файл через любой файловый менеджер и нажмите «Обновить».

---

## ⚠️ Известные особенности и рекомендации (Operational Advisory)

1. **Распространение обновления через CDN:**
   - После публикации релиза глобальным серверам CDN может потребоваться некоторое время для распространения манифеста обновлений. Если обновление не появилось сразу, повторите проверку чуть позже.
2. **Адаптивные потоки YouTube:**
   - Механизм восстановления потоков VOD автоматически подбирает рабочий формат при получении ошибок от серверов YouTube. На телевизорах со слабыми чипсетами рекомендуется оставлять кодек в режиме `AUTO` или `AVC`.
3. **Устройства на базе Android 9 (Issue #118):**
   - В ядре 32.71 обновлены сетевые транспорты, а регрессионный сэмпл из Issue #118 успешно воспроизводится на тестовом стенде. Проблема остаётся открытой до получения подтверждения от пользователей физических устройств на базе Android 9. Обратная связь приветствуется!
