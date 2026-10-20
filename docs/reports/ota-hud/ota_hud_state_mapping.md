# SmartTube VOX OTA HUD — State Mapping & Design Specification Cross-Reference

## 1. Overview

This document provides an exhaustive cross-reference mapping between the 8-state design mockup (`media_1792498589713_cd5336fa.jpg`), the underlying Kotlin data models (`VoxOtaHudState`), Android layout XML files, drawables, and the generated verification screenshots in `build/reports/ota-hud/`.

---

## 2. Comprehensive State Matrix

| Design State # | State Name | UI Layout | Model Class | Key Identifiers / Views | Generated Screenshot |
|---|---|---|---|---|---|
| **1** | Stable Offer | `vox_ota_hud_offer.xml` | `VoxOtaHudState.Offer` (`STABLE`) | `txt_title` ("Доступно обновление"), `txt_version` ("SmartTube Vox - 9.1"), `txt_current_version` ("Текущая версия: 9.0"), `changelog_container`, `btn_update`, `btn_details`, `btn_later` | `screenshot_stable_offer.png` |
| **1b** | Beta Offer | `vox_ota_hud_offer.xml` | `VoxOtaHudState.Offer` (`BETA`) | `txt_version` ("SmartTube Vox - 9.2 Beta"), `txt_current_version` ("Текущая версия: 9.1"), Amber accent | `screenshot_beta_offer.png` |
| **1c** | Dev Offer | `vox_ota_hud_offer.xml` | `VoxOtaHudState.Offer` (`DEV`) | `txt_version` ("SmartTube Vox - 9.3 Dev"), `txt_current_version` ("Текущая версия: 9.2"), Purple accent | `screenshot_dev_offer.png` |
| **2** | Confirm Download | `vox_ota_hud_confirm.xml` | `VoxOtaHudState.Confirmation` | `txt_confirm_title` ("Подтверждение загрузки"), `card_wifi_recommend` ("Рекомендуется подключение по Wi-Fi"), `txt_file_size` ("Размер файла: 368 МБ"), `btn_confirm_proceed`, `btn_confirm_cancel` | `screenshot_download_confirm.png` |
| **3 / 4a** | Progress: Downloading | `vox_ota_hud_progress_full.xml` | `VoxOtaHudState.Progress` (`DOWNLOADING`) | `txt_progress_title` ("Загрузка обновления..."), `txt_progress_percent` ("42%"), `txt_speed_value` ("6,8 МБ/с"), `txt_size_value` ("156 МБ / 368 МБ"), `btn_progress_minimize` ("В фон"), `btn_top_minimize`, `btn_top_close` | `screenshot_download_progress_full.png` |
| **4b** | Progress: Verifying | `vox_ota_hud_progress_full.xml` | `VoxOtaHudState.Progress` (`VERIFYING`) | `txt_progress_title` ("Проверка обновления..."), `txt_progress_note` ("Проверяем файл..."), `img_progress_icon` (`ic_vox_ota_gear`), `txt_progress_percent` ("75%") | `screenshot_verify_stage.png` |
| **4c** | Progress: Preparing | `vox_ota_hud_progress_full.xml` | `VoxOtaHudState.Progress` (`PREPARING`) | `txt_progress_title` ("Подготовка к установке..."), `txt_progress_note` ("Распаковка файла..."), `img_progress_icon` (`ic_vox_ota_package`), `txt_progress_percent` ("90%") | `screenshot_prepare_stage.png` |
| **5** | Mini-HUD Overlay | `vox_ota_hud_mini.xml` | `VoxOtaHudState.Progress` (`isMinimized=true`) | `img_mini_icon`, `txt_mini_title` ("Загрузка обновления... 42%"), `progress_mini_bar`, `btn_mini_expand` ("Развернуть"), `btn_mini_cancel` ("Отмена") | `screenshot_minihud.png` |
| **6** | Restored Modal | `vox_ota_hud_progress_full.xml` | `VoxOtaHudState.Progress` (`isMinimized=false`) | Restores full modal dialog preserving active metrics (`67%`, `7,3 МБ/с`, `246 МБ / 368 МБ`) | `screenshot_restored_full.png` |
| **7** | Download Complete | `vox_ota_hud_complete.xml` | `VoxOtaHudState.DownloadComplete` | `img_complete_icon` (`ic_vox_ota_check`), `txt_complete_title` ("Обновление загружено"), `txt_complete_message` ("Файл успешно скачан и проверен"), `btn_install_now`, `btn_install_later` | `screenshot_download_complete.png` |
| **8** | Installing Modal | `vox_ota_hud_installing.xml` | `VoxOtaHudState.Installing` | `img_install_icon` (`ic_vox_ota_gear`), `txt_install_title` ("Установка обновления..."), Step 1 ("Подготовка файлов"), Step 2 ("Установка приложения"), Step 3 ("Завершение") | `screenshot_install_progress.png` |

---

## 3. Layout and Resource Specification

### Visual Layout Files (`common/src/main/res/layout/`)
- `vox_ota_hud_offer.xml`: Card-based modal dialog (500dp width) with rounded corners (`16dp`), dark background (`#161B22`), changelog list container, and vertically stacked action buttons.
- `vox_ota_hud_confirm.xml`: Card modal (480dp width) displaying warning card with Wi-Fi recommendation icon and file size metrics.
- `vox_ota_hud_progress_full.xml`: Extended progress card (560dp width) supporting dynamic metric toggles, speed/size displays, top-right window controls, and bottom action bar.
- `vox_ota_hud_mini.xml`: Floating non-modal overlay (350x72dp) with glassmorphism surface (`#1F242C`), horizontal layout, and quick action buttons.
- `vox_ota_hud_complete.xml`: Success card modal (480dp width) with bright green accent badge and installation CTA buttons.
- `vox_ota_hud_installing.xml`: Installation status card (480dp width) with animated gear icon and 3-step vertical progress rows.

### Drawables & Vectors (`common/src/main/res/drawable/`)
- Vector Icons:
  - `ic_vox_ota_arrow_up.xml`: Cloud download / update offer indicator.
  - `ic_vox_ota_arrow_down.xml`: Downloading stream indicator.
  - `ic_vox_ota_gear.xml`: Verifying and system installing indicator.
  - `ic_vox_ota_package.xml`: File unpacking and preparation indicator.
  - `ic_vox_ota_check.xml`: Green verification checkmark.
  - `ic_vox_ota_wifi.xml`: Wi-Fi recommendation network indicator.
  - `ic_vox_ota_minimize.xml`: Window minimize action.
  - `ic_vox_ota_expand.xml`: Overlay expand action.
  - `ic_vox_ota_close.xml`: Dialog dismiss action.
  - `ic_vox_ota_speed.xml`: Network throughput speedometer icon.
  - `ic_vox_ota_file.xml`: Storage file size icon.
- Backgrounds & Buttons:
  - `bg_vox_ota_card.xml`: 16dp rounded corner dark card with subtle border stroke (`#30363D`).
  - `bg_vox_ota_mini_card.xml`: Floating card with 12dp corners and elevation shadow.
  - `bg_vox_ota_btn_primary.xml`: High-contrast Leanback button with blue gradient, white glow focus state, and 8dp radius.
  - `bg_vox_ota_btn_secondary.xml`: Transparent background with `#30363D` border and white glow focus state.
  - `bg_vox_ota_btn_danger.xml`: Red-accented button for cancellation confirmation.
  - `bg_vox_ota_progressbar.xml`: 8dp smooth progress bar with `#1F6FEB` progress fill and dark track.
  - `bg_vox_ota_progressbar_mini.xml`: 4dp compact progress bar for bottom-right mini-HUD.
