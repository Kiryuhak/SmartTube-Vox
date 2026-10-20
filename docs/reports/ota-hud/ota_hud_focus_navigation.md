# SmartTube VOX OTA HUD — Leanback TV D-Pad Focus & Navigation Invariants

## 1. Overview

Android TV and 10-foot UI design requirements mandate that all interactive elements can be operated exclusively via a physical D-pad remote control without requiring touch or mouse pointers.

This document describes the focus order, key event policies, and remote navigation flows for all screens within the SmartTube VOX OTA HUD.

---

## 2. Global Focus & Input Policies

1. **Primary Action Default Focus:**
   - Every modal automatically places initial focus on the recommended primary action button (e.g. `[Обновить]`, `[Продолжить]`, `[В фон]`, `[Установить]`).
   - Secondary and destructive buttons are reachable via Down or Right arrow navigation.

2. **D-Pad Directional Sound & Highlighting:**
   - All interactive controls implement standard Leanback TV focus scaling (1.05x), subtle white glow stroke (`bg_vox_ota_btn_primary.xml`, `bg_vox_ota_btn_secondary.xml`), and system click feedback.

3. **Back Key (`KEYCODE_BACK`) Policy:**
   - **Offer Dialog (States 1, 1b, 1c):** Dismisses dialog without initiating download.
   - **Download Confirmation (State 2):** Cancels download preparation and dismisses dialog.
   - **Full Progress Modal (States 3, 4a, 4b, 4c):** Minimizes modal directly to bottom-right floating mini-HUD. The download **does not stop**; user can continue browsing or watching videos.
   - **Mini-HUD (State 5):** Does not consume back key; back key navigates underlying player/browse screens as usual. Mini-HUD remains pinned to bottom-right corner.
   - **Download Complete (State 7):** Dismisses modal for later installation.
   - **Installation In-Progress (State 8):** Non-cancelable (`setCancelable(false)`), Back key is consumed to prevent corrupted APK handoff.

---

## 3. Screen-by-Screen Navigation Matrix

### State 1 / 1b / 1c: Update Available Offer Dialog

```
               [ Dialog Header ]
               [ Changelog Box ]
                      |
                      v
             [ [Обновить] (FOCUS) ]  <--- Default Focus
                      |
                   DPAD_DOWN
                      |
                      v
             [ [Подробнее] ]
                      |
                   DPAD_DOWN
                      |
                      v
             [ [Позже] ]
```

- **Initial Focus:** `btn_update` (`[Обновить]`).
- **Down:** Focuses `btn_details` (`[Подробнее]`).
- **Down:** Focuses `btn_later` (`[Позже]`).
- **Up:** Traverses backwards (`[Позже]` -> `[Подробнее]` -> `[Обновить]`).
- **Back Key:** Closes dialog.

---

### State 2: Metered / Wi-Fi Confirmation Modal

```
               [ Warning Card: Wi-Fi Recomm. ]
               [ File Size: 368 МБ ]
                      |
                      v
             [ [Продолжить] (FOCUS) ]  <--- Default Focus
                      |
                   DPAD_DOWN
                      |
                      v
             [ [Отмена] ]
```

- **Initial Focus:** `btn_confirm_proceed` (`[Продолжить]`).
- **Down:** Focuses `btn_confirm_cancel` (`[Отмена]`).
- **Back Key:** Closes dialog without starting download.

---

### State 3 / 4a / 4b / 4c: Full Progress Modal

```
   [ Header: Загрузка обновления... ]   [ [—] btn_minimize ] [ [×] btn_close ]
   [ Progress Bar: 42% ]
   [ Speed: 6,8 МБ/с | Size: 156/368 МБ ]
                      |
                      v
             [ [В фон] (FOCUS) ]       <--- Default Focus
                      |
                   DPAD_DOWN
                      |
                      v
             [ [Отмена] ]
```

- **Initial Focus:** `btn_progress_minimize` (`[В фон]`).
- **Up:** Navigates to top-right window action buttons (`btn_top_minimize` and `btn_top_close`).
- **Down:** Navigates to `btn_progress_cancel` (`[Отмена]`).
- **Back Key:** Minimizes dialog immediately to mini-HUD without interrupting network stream.

---

### State 5: Floating Bottom-Right Mini-HUD

```
   +--------------------------------------------------------+
   | (Icon) Загрузка... 42%      [Развернуть] (F) [Отмена]  |
   | [====================--------------------------------] |
   +--------------------------------------------------------+
```

- **Position:** Anchored at bottom-right corner (`Gravity.BOTTOM | Gravity.END`).
- **Initial Focus:** `btn_expand` (`[Развернуть]`).
- **Right:** Navigates to `btn_cancel` (`[Отмена]`).
- **OK/Select on `[Развернуть]`:** Restores full modal dialog (State 6).
- **OK/Select on `[Отмена]`:** Aborts download and dismisses mini-HUD.

---

### State 7: Download Complete Modal

```
               [ Checkmark Icon: Обновление загружено ]
               [ Файл успешно скачан и проверен ]
                      |
                      v
             [ [Установить] (FOCUS) ]   <--- Default Focus
                      |
                   DPAD_DOWN
                      |
                      v
             [ [Позже] ]
```

- **Initial Focus:** `btn_install_now` (`[Установить]`).
- **Down:** Focuses `btn_install_later` (`[Позже]`).
- **Back Key:** Closes modal, APK remains preserved in cache.

---

### State 8: Installation In-Progress Modal

```
               [ Gear Icon: Установка обновления... ]
               [ [===] 1. Подготовка файлов...      ]
               [ [===] 2. Установка приложения...    ]
               [ [---] 3. Завершение...             ]
```

- **Focus:** No interactive buttons exposed.
- **Back Key:** Blocked (`dialog.setCancelable(false)`). Prevents interrupting package manager intent handoff.

---

## 4. Verification Checkpoint

All focus transitions and key events have been automatically executed on `emulator-5554` via `scripts/verify_ota_hud_suite.py` with 100% pass rate.
