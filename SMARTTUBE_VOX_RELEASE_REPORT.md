# SmartTube VOX Final Release Report

**Проект**: `C:\Users\user\Documents\SmartTube`  
**Версия**: `32.45-vot.9`  
**Код версии (versionCode)**: `2435009`  
**Базовая версия upstream**: `SmartTube 32.45`  
**Идентификатор пакета**: `io.github.kiryuhak.smarttubevot.stable`  
**Дата подготовки**: 2026-09-17  

---

## 1. Phase 1 — Git Final Audit

- **Ветка**: `main`
- **Состояние репозитория**: `working tree clean` (исходный код, UI/UX и VOT-логика не изменялись).
- **Удалённые репозитории**:
  - `origin`: `https://github.com/Kiryuhak/SmartTube-Vox.git`
  - `upstream`: `https://github.com/yuliskov/SmartTube.git`
- **Последние коммиты**:
  - `6878dca` — Обновлён CHANGELOG.md: актуализированы изменения выпуска 32.45-vot.9
  - `c49443d` — Усилены проверки состояния VOT, политики аудиодорожек и UX отката живого голоса
  - `77889aa` — Добавлен резервный переход с живых голосов на обычную озвучку
- **Временные и debug-файлы**: отсутствуют в отслеживаемых файлах.
- **Аудиторский документ**: сформирован файл `SMARTTUBE_VOX_RELEASE_AUDIT.md`.

---

## 2. Phase 2 — Release APK Audit

Сборка релизных пакетов выполнена задачей: `:smarttubetv:assembleStvotRelease`.

| Ассет | Размер | Архитектура | SHA-256 сертификата | Результат |
| :--- | :--- | :--- | :--- | :--- |
| `SmartTube_vot_32.45-vot.9_universal.apk` | 23.8 МБ | Universal | `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d` | **PASS** |
| `SmartTube_vot_32.45-vot.9_arm64-v8a.apk` | 18.8 МБ | arm64-v8a | `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d` | **PASS** |
| `SmartTube_vot_32.45-vot.9_armeabi-v7a.apk` | 18.1 МБ | armeabi-v7a | `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d` | **PASS** |
| `SmartTube_vot_32.45-vot.9_x86.apk` | 19.0 МБ | x86 | `e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d` | **PASS** |

### Верификация атрибутов пакета
- `applicationId`: `io.github.kiryuhak.smarttubevot.stable`
- `versionName`: `32.45-vot.9`
- `versionCode`: `2435009`
- `minSdkVersion`: `21`
- `targetSdkVersion`: `34`
- `compileSdkVersion`: `34`

### Проверка цифровой подписи (`apksigner verify`)
- Схемы подписи: v1: **true**, v2: **true**
- Сертификат: `CN=SmartTube VOT, OU=SmartTube VOT, O=SmartTube VOT, L=Sterlitamak, ST=Sterlitamak, C=RU`
- Совпадение отпечатка SHA-256 с production keystore: **100% PASS**

---

## 3. Phase 3 — Security Check

- **Авторизационные данные**:
  - Токены, пароли, куки, клиентские секреты OAuth в исходном коде отсутствуют.
  - Проверены паттерны: `Authorization`, `Bearer`, `OAuth`, `secret`, `token`, `cookie`.
- **Защита URL и логирование**:
  - Подписанные URL потоков исключены из логов.
  - Активен механизм санитизации URL (SABR sanitizer), удаляющий параметры подписи и авторизационные заголовки.
  - Избыточное HTTP-логирование отключено.

---

## 4. Phase 4 — OTA Preparation

Подготовлен и валидирован файл обновления `smarttube_vox.json` (в корне и в директории `updates/`):

```json
{
  "package": {
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.apk"
  },
  "32.45-vot.9": {
    "versionCode": 2435009,
    "changelog": [
      "Improved Lively Voice fallback handling",
      "Added VOT state checks",
      "Improved audio track stability",
      "Fixed translation timers",
      "Improved Yandex VOT error checks"
    ],
    "changelog_ru": [
      "Улучшена обработка Lively Voice fallback",
      "Добавлены проверки состояния VOT",
      "Улучшена стабильность аудиодорожек",
      "Исправлены таймеры перевода",
      "Улучшены проверки ошибок Yandex VOT"
    ]
  }
}
```

- **downloadUrl**: валидирован (`https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.apk`).
- **versionCode**: `2435009`.
- **versionName**: `32.45-vot.9`.
- **changelog_ru**: актуален.
- **Старые префиксы**: устаревшее имя `smarttube_vot` полностью исключено.

---

## 5. Phase 5 — Changelog

Файл `CHANGELOG.md` актуализирован. Раздел `32.45-vot.9` содержит только фактические подтверждённые изменения:

- улучшена обработка Lively Voice fallback
- добавлены проверки состояния VOT
- улучшена стабильность аудиодорожек
- исправлены таймеры перевода
- улучшены проверки ошибок Yandex VOT

---

## 6. Phase 6 — GitHub Release Prepare

> [!NOTE]
> Релиз на GitHub **НЕ создан**. Ниже представлены подготовленные метаданные для публикации после подтверждения владельца.

### Название релиза
`SmartTube VOX 32.45-vot.9`

### Описание релиза
```markdown
Stable VOX release based on SmartTube 32.45.

Includes:
- Yandex VOT improvements
- Lively fallback protection
- audio track handling improvements
- stability fixes
```

### Список файлов для релиза
1. `smarttube_vox.apk` (Universal APK)
2. `smarttube_vox-arm64-v8a.apk` (ARM64 APK)
3. `smarttube_vox-armeabi-v7a.apk` (ARMv7 APK)
4. `smarttube_vox-x86.apk` (x86 APK)
5. `smarttube_vox.json` (Манифест OTA-обновлений)

---

## 7. Phase 7 — Physical TV Final Check List

> [!IMPORTANT]
> Данный чек-лист **НЕ запускается автоматически**.
> Список предназначен для финальной ручной проверки владельцем перед публикацией релиза:

1. **Lively Voice с Yandex ID**:
   - Авторизовать аккаунт Яндекс в настройках плеера («Закадровый перевод (Яндекс)» -> «Войти в Яндекс ID»).
   - Включить «Живой голос» и запустить иностранное видео.
   - Убедиться, что перевод воспроизводится живым голосом.

2. **Fallback: Lively -> Standard Voice**:
   - Проверить поведение при отказе бэкенда в живом голосе (или при исчерпании квоты).
   - Убедиться, что плеер отображает уведомление и переключается на стандартную озвучку без сбоя воспроизведения.

3. **Russian YouTube Dub restore**:
   - Открыть видео с официальным русским дубляжом YouTube.
   - Включить закадровый перевод Яндекс (VOT).
   - Выключить перевод и проверить корректное восстановление дублированной дорожки YouTube.

4. **Native Russian protection**:
   - Открыть русскоязычное видео.
   - Убедиться, что фоновый автоперевод не запускается и оригинальный звук воспроизводится без приглушения.

5. **Long playback**:
   - Непрерывный просмотр видео более 30 минут с активным переводом.
   - Контроль отсутствия утечек памяти, рассинхронизации аудио и заиканий.

---

STATUS:

READY FOR RELEASE

Но:

НЕ RELEASE
НЕ TAG
НЕ UPLOAD

Ждать подтверждения владельца.