# SmartTube VOX Test Inventory & Pyramid Architecture

## Overview
This document categorizes all test suites across the SmartTube VOX codebase into a 5-level testing pyramid, designed to optimize developer velocity and ensure rock-solid stability without requiring full regression runs on every edit.

---

## 1. The 5-Level VOX Test Pyramid

```
                ▲
               / \
              / L5\     Level 5: Full Regression & Physical Release Gate
             /-----\    (1400+ Android + Backend + Physical TV Gate)
            /  L4   \   Level 4: Fast Release Gates & Golden Smoke (15s - 45s)
           /---------\  (verify_ota_forward_compatibility, golden-smoke)
          /    L3     \ Level 3: Cross-Component & Workflow Hardening (5s - 15s)
         /-------------\ (Double-back, Live sync, Rebuffer classification)
        /      L2       \ Level 2: Subsystem Integration & Services (2s - 8s)
       /-----------------\ (VOT translation suite, Admin API, Auto-triage)
      /        L1         \ Level 1: Pure Unit & Domain Invariants (< 2s)
     /---------------------\ (Version selection, Error parsing, Status lifecycle)
```

---

## 2. Test Suites by Subsystem

### A. Over-The-Air Update (OTA)
| Suite Name | Target Classes / Scripts | Level | Approx Time | Purpose |
|---|---|---|---|---|
| `ota-fast` | `scripts/verify_ota_forward_compatibility.sh` | L4 | ~5s | Verifies version jumping, APK contract, monotonic versionCode |
| `ota-integration` | `com.liskovsoft.smartyoutubetv2.common.vox.ota.*` | L2 | ~4s | Full Android OTA suite (22+ unit tests) |
| `ota-e2e` | `ota-fast` + `ota-integration` | L3 | ~8s | Complete OTA validation before any build change |

### B. Video Translation (VOT)
| Suite Name | Target Classes | Level | Approx Time | Purpose |
|---|---|---|---|---|
| `vot-fast` | `com.liskovsoft.smartyoutubetv2.common.vox.translation.*` | L2 | ~5s | Translation state machine, cache-first lookups, retry policies |
| `vot-full` | `vot-fast` + `com.liskovsoft.smartyoutubetv2.common.vox.mux.*` | L3 | ~8s | Audio track sync, Matroska muxer, Opus header validation |

### C. Player & Playback
| Suite Name | Target Classes | Level | Approx Time | Purpose |
|---|---|---|---|---|
| `player-fast` | `playback.*`, `player.*`, `DoubleBackExitTest` | L2 | ~6s | Double-back exit, pause suppression, buffer policy, DPAD wake |
| `player-full` | `player-fast` + `tv.ui.*` | L3 | ~10s | Complete UI focus and player lifecycle on Android TV |

### D. Diagnostics & Dashboard Backend
| Suite Name | Target Files | Level | Approx Time | Purpose |
|---|---|---|---|---|
| `diagnostics-fast` | `services/vox-diagnostics/test/*.mjs` | L2 | ~2s | 96 backend tests (API, triage, telegram, storage, audit) |
| `dashboard-fast` | `dashboard_operational_hardening.test.mjs`, `admin_interactive.test.mjs` | L1 | ~200ms | Quick status buttons, deep links, lifecycle transitions |
| `telegram-fast` | `telegram_notifications.test.mjs` | L1 | ~1.5s | Sanitization, JSON bundle, rate limiting, duplicate suppression |

### E. Live Streams & Downloads
| Suite Name | Target Classes | Level | Approx Time | Purpose |
|---|---|---|---|---|
| `live-fast` | `playback.Live*`, `playback.VoxLive*`, `translation.VoxLive*` | L2 | ~4s | Live stream sync, adaptive segment controller |
| `downloads-fast` | `vox.download.*` | L2 | ~4s | Download state invariants, YouTube retry, URL validation |
| `downloads-full` | `downloads-fast` + `vox.mux.*` | L3 | ~7s | Download pipeline and Matroska muxing |

---

## 3. Composite Gates & Execution Guide

### `golden-smoke` (~15s)
Runs targeted smoke tests across all core subsystems:
```bash
./scripts/run_smart_tests.sh golden-smoke
```
Includes: `ota-fast`, `vot-fast`, `player-fast`, `diagnostics-fast`.

### `auto` (Default Smart Selection)
Inspects `git diff` and executes only the test suites corresponding to modified files:
```bash
./scripts/run_smart_tests.sh
```

### `full-regression` (~1-2m)
Runs all 1400+ Android unit tests across `:common` and `:smarttubetv` plus the entire `vox-diagnostics` test suite:
```bash
./scripts/run_smart_tests.sh full-regression
```
*Note: Run only once at the conclusion of a full hardening iteration.*
