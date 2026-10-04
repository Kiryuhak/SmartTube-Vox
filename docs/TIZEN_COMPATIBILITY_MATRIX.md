# SmartTube VOX — Samsung Tizen Compatibility Matrix

> **Статус валидации**: `STATIC_VALIDATED` / `DESKTOP_PREVIEW`  
> **Физическая валидация**: `NOT_TESTED_PHYSICAL` (физический Samsung Tizen TV не подключался)  
> **Schema Profile**: `vox-device-profile-v1`

---

## 1. Обзор архитектуры платформы Tizen

SmartTube VOX для Samsung Tizen построен как гибридное веб-приложение (W3C Widget / Tizen Web Application), использующее прямое сопряжение с медиа-стеком Samsung через **Samsung Product API (`webapis.avplay`)** и fallback на **HTML5 Video / MSE**.

Платформа следует единой архитектурной модели VOX 7:
- **Единый профиль устройства (`vox-device-profile-v1`)**;
- **Строгая трехзначная логика возможностей (`SUPPORTED`, `UNSUPPORTED`, `UNKNOWN`)** с обязательным указанием источника детекции (`REAL_PLATFORM_API`, `BROWSER_CAPABILITY_HINT`, `UNKNOWN`);
- **Защита от ложных заявлений о поддержке аппаратного ускорения**: `HTMLMediaElement.canPlayType()` классифицируется только как `BROWSER_CAPABILITY_HINT` и никогда не выдаётся за подтверждённый аппаратный декодер;
- **Автоматическая настройка кодеков (`auto`, `max_quality`, `max_compatibility`, `custom`)** с диалогом предупреждения о рисках при ручном переопределении;
- **Безопасный экспорт и отправка телеметрии диагностики (`POST /v1/report`)** без персональных данных (PII), токенов и IP-адресов.

---

## 2. Матрица совместимости по версиям Tizen OS

| Tizen OS | Годы ТВ | Web Engine | Плеер по умолчанию | Видеокодеки (Hardware) | Аудиокодеки | HDR / Цветовые пространства | VOX Sync (Target: 150ms) | Статус валидации |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Tizen 3.0** | 2017 (M/MU/Q) | Chromium 47 | AVPlay (`webapis.avplay`) | AVC (H.264), HEVC (H.265), VP9 Profile 0 | AAC, AC3, EAC3, MP3 | HDR10 | AVPlay / HTML5 Sync | `STATIC_VALIDATED` |
| **Tizen 4.0** | 2018 (N/NU/Q*FN) | Chromium 56 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9 Profile 0/2 | AAC, AC3, EAC3, Opus | HDR10, HDR10+ | AVPlay Sub-track Sync | `STATIC_VALIDATED` |
| **Tizen 5.0** | 2019 (R/RU/Q*R) | Chromium 69 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9 Profile 0/2 | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Dual Stream Sync | `STATIC_VALIDATED` |
| **Tizen 5.5** | 2020 (T/TU/Q*T) | Chromium 76 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9 Profile 0/2, AV1 (флагманы 8K/4K) | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Dual Stream Sync | `STATIC_VALIDATED` |
| **Tizen 6.0** | 2021 (A/AU/QN*A) | Chromium 85 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9, AV1 (большинство 4K/8K) | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Precision Sync | `STATIC_VALIDATED` |
| **Tizen 6.5** | 2022 (B/BU/QN*B) | Chromium 94 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9, AV1 | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Precision Sync | `STATIC_VALIDATED` |
| **Tizen 7.0** | 2023 (C/CU/QN*C) | Chromium 108 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9, AV1 | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Precision Sync | `STATIC_VALIDATED` |
| **Tizen 8.0** | 2024 (D/DU/QN*D) | Chromium 120 | AVPlay (`webapis.avplay`) | AVC, HEVC, VP9, AV1 | AAC, AC3, EAC3, Opus | HDR10, HDR10+, HLG | AVPlay Precision Sync | `STATIC_VALIDATED` |
| **Tizen TV Emulator** | Эмулятор SDK | Chromium (Host) | HTML5 / Mock AVPlay | Host-dependent (AVC/VP9/AV1) | Host-dependent (AAC/Opus) | Host-dependent | HTML5 WebAudio Sync | `EMULATOR_SUPPORTED` |
| **Desktop Preview** | Браузер разработчика | Chromium / WebKit | HTML5 Player Engine | Host-dependent (AVC/VP9/AV1) | Host-dependent (AAC/Opus) | sRGB / Display P3 | HTML5 WebAudio Sync | `DESKTOP_PREVIEW` |

---

## 3. Модель возможностей и определение кодеков

### 3.1. Трехзначные состояния (Tri-State)
Все проверки возможностей в `TizenCapabilityProvider.js` возвращают строгий `TriState`:
- `SUPPORTED`: Подтверждено реальным API платформы (`webapis.avplay`, `tizen.systeminfo`);
- `UNSUPPORTED`: Проверено платформенным API и возвращён отрицательный результат;
- `UNKNOWN`: Данные отсутствуют или API платформы недоступен (строгое правило: `UNKNOWN != UNSUPPORTED`).

### 3.2. Источники детекции (`DetectionSource`)
1. `REAL_PLATFORM_API`: Вызовы `webapis.avplay.isStreamSupport`, `tizen.systeminfo`, Samsung Tizen Product API;
2. `BROWSER_CAPABILITY_HINT`: Запросы через `HTMLMediaElement.canPlayType()`, `MediaSource.isTypeSupported()`;
3. `UNKNOWN`: Окружение не предоставило достоверного источника.

### 3.3. Иерархия автоподбора кодеков
- **Видео**: `AV1` (если поддерживается аппаратно) → `VP9` (Profile 2 / Profile 0) → `AVC (H.264)` (базовый безопасный кодек);
- **Аудио**: `EAC3` (Dolby Digital Plus) → `AC3` (Dolby Digital) → `Opus` → `AAC` (базовый стерео кодек).

---

## 4. Режимы автоматической настройки (Auto Device Tuning)

| Режим | Описание | Поведение видео | Поведение аудио | Предупреждение о рисках |
| :--- | :--- | :--- | :--- | :--- |
| `auto` (По умолчанию) | Автоматический выбор оптимального кодека на основе возможностей устройства | Рекомендованный кодек профиля | Рекомендованный аудиокодек профиля | Нет |
| `max_quality` | Приоритет наивысшего разрешения и качества (AV1 / VP9 4K HDR) | Максимально возможный кодек | Многоканальный / высокобитрейтный | Да (диалог предупреждения) |
| `max_compatibility` | Приоритет максимальной стабильности и совместимости | AVC / H.264 1080p | AAC Stereo | Нет |
| `custom` | Пользовательские ручные настройки кодеков и битрейтов | Пользовательский выбор | Пользовательский выбор | Да (диалог предупреждения) |

---

## 5. Воспроизведение и синхронизация перевода (VOX Audio Engine)

1. **AVPlay Engine (`TizenAvPlayAdapter.js`)**:
   - Прямое аппаратное декодирование через Samsung AVPlay NDK/JS API;
   - Поддержка переключения аудиодорожек и независимого потока озвучки VOX;
   - Нормализованная машина состояний: `IDLE`, `PREPARING`, `READY`, `PLAYING`, `PAUSED`, `BUFFERING`, `ENDED`, `ERROR`.

2. **HTML5 Engine (`TizenHtml5PlayerAdapter.js`)**:
   - Чистый fallback для браузерного предпросмотра, эмулятора и сред без `webapis.avplay`;
   - Синхронизация потоков через `WebAudio API` / `HTMLAudioElement` с допустимым фазовым сдвигом $\le 150$ мс.

---

## 6. Безопасная диагностика и телеметрия

1. **Пользовательский интерфейс диагностики (`TizenDiagnosticsDialog.js`)**:
   - **Посмотреть отчёт**: просмотр JSON-отчёта на экране ТВ;
   - **Скопировать отчёт**: копирование безопасного отчёта в буфер обмена;
   - **Отправить разработчику**: отправка на бэкенд диагностики с обязательным запросом согласия пользователя.
2. **Безопасность данных**:
   - Полная маскировка токенов авторизации Яндекс, ключей сессий и IP-адресов;
   - В отчёт включаются: версия приложения, модель ТВ, версия Tizen OS, профиль устройства (`vox-device-profile-v1`), состояние плеера, стек ошибки и безопасная категория (`VOX_AUDIO_FETCH_FAILED`, `VOX_DESYNC`, `AVPLAY_ERROR`, `NETWORK_OFFLINE` и т.д.);
   - При успешной отправке пользователю возвращается короткий идентификатор отчёта (например, `VOX-A1B2C3`).

---

## 7. Текущие ограничения и статус реализации

- **Загрузки (Downloads)**: В текущей версии для Tizen модуль загрузок зафиксирован со статусом `DOWNLOADS_NOT_IMPLEMENTED_TIZEN`. Локальное скачивание и сохранение на накопитель ТВ будет реализовано в последующих версиях.
- **DRM**: Widevine Modular / PlayReady через AVPlay планируется к интеграции в релизах ветки 7.x.
- **Физическая валидация**: Настоящий документ отражает статус `STATIC_VALIDATED` и `DESKTOP_PREVIEW`. Физическое тестирование на реальных ТВ Samsung Tizen 2017-2024 годов запланировано на отдельный этап физической приёмки.
