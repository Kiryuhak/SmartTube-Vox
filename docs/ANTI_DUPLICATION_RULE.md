# SMARTTUBE VOX — ANTI-DUPLICATION RULE & FEATURE AUDIT SPECIFICATION

## 1. Core Principle
SmartTube VOX is an advanced fork of SmartTube. Maintaining codebase sanity, performance on low-end Android TV hardware, and long-term stability requires strict prevention of subsystem duplication.

Before introducing ANY new feature, preference, network route, or UI element:
```
SEARCH EXISTING IMPLEMENTATION
       ↓
   CLASSIFY
       ↓
REUSE / EXTEND (Preferred)
       ↓
CREATE ONLY IF REALLY ABSENT
```

---

## 2. Classification Matrix

Every proposed capability must be classified into exactly one of five states:
1. **ALREADY_IMPLEMENTED**: A working solution already exists in the codebase (e.g., Cronet QUIC/HTTP2, YouTube client fallback, Shorts translation).
   - *Action*: Document, test, and reuse. Never duplicate.
2. **PARTIALLY_IMPLEMENTED**: Core building blocks exist but need integration (e.g., startup profiler timeline stages, pre-release channel detection).
   - *Action*: Connect and finalize existing components.
3. **IMPLEMENTED_BUT_HIDDEN**: Functionality exists in code/preferences but lacks a user-facing entry or toggle.
   - *Action*: Expose through existing canonical presenters and settings groups.
4. **IMPLEMENTED_BUT_WEAK**: Functionality exists but suffers from edge cases or defects (e.g., Leanback HUD visibility caching, image request retry noise).
   - *Action*: Harden the canonical path. Do not build an alternative parallel manager.
5. **NOT_IMPLEMENTED**: Capability is genuinely absent from the codebase (e.g., pause-while-preparing translation toggle).
   - *Action*: Implement using canonical models (`VotData`, `PlayerData`, `PlayerTweaksData`) and standard presenters.

---

## 3. Strict Prohibitions

Developers and AI pair programmers are **strictly forbidden** from:
- Creating a second player or HUD controller (e.g., never create `VoxHudManager` when `PlayerUIController` and `PlaybackFragment` exist).
- Creating duplicate preferences or keys with the same semantic meaning (e.g., never duplicate `target_language` or `auto_translate_enabled`).
- Creating parallel network transport engines (e.g., never create a second Cronet/OkHttp client when `CronetDataSource` and `OkHttpDataSource` are canonical).
- Creating duplicate translation playback pipelines (e.g., never create `ShortsVotClient` when `VoiceTranslateController` attaches to all playback items).
- Creating duplicate diagnostics or telemetry subsystems.

---

## 4. External Reference Rule

When assessing feature ideas or code patterns from third-party applications (e.g., ReVanced Extended, NewPipe, or third-party APKs):
- **Never copy blindly**: Android TV is fundamentally different from mobile (D-Pad navigation, remote control latency, fixed Wi-Fi/Ethernet, low memory).
- **Evaluate Tradeoffs**:
  - UX (Leanback and 10-foot UI friendliness)
  - Architecture & maintainability
  - Performance and memory impact on TV chipsets
  - Security and privacy
- **Adopt or Reject**:
  - If existing VOX implementation is better: keep ours.
  - If external feature has proven benefit: integrate it cleanly into existing VOX architecture.
  - If mobile-centric (e.g., per-network cellular quality): **explicitly reject**.

---

## 5. Patch #38 Feature Audit Ledger

| Subsystem | Existing Canonical Path | Classification | Decision / Action | Duplicate Created |
| :--- | :--- | :--- | :--- | :--- |
| **Diagnostics Incident Scheduler** | `VoxDiagnosticsScheduler`, `VoxDiagnosticsPolicy` | `EXTENDED` | Incident aggregation, cooldown, and bounded offline queue | **NO** |
| **Shorts Playback & Translation** | `PlaybackPresenter`, `VoiceTranslateController` | `ALREADY_IMPLEMENTED` | Verified session isolation and volume restoration; suite tests added | **NO** |
| **Network Transport & Protocol** | `CronetEngine`, `VoxStartupProfiler` | `EXTENDED` | Transport protocol telemetry added; auto h3->h2 preserved | **NO** |
| **Playback Startup Timeline** | `VoxStartupProfiler` | `ALREADY_IMPLEMENTED` | Validated on emulator for 1080p and 4K VP9 streams | **NO** |
| **Rebuffer Root Cause** | `AdaptiveBufferingManager` | `ALREADY_IMPLEMENTED` | Re-verified false-positive suppression for low bitrate streams | **NO** |
| **Image Retry & Recovery** | `Glide`, `VoxSafeLogger` | `ALREADY_IMPLEMENTED` | Noise filtering and final failure escalation verified | **NO** |
| **HUD Lifecycle & Self-Healing** | `PlaybackFragment`, `PlayerUIController` | `EXTENDED` | Linked `HUD_STATE_INCONSISTENT` to `VoxDiagnosticsScheduler` | **NO** |
| **OTA Updater & Checks** | `VoxOtaUpdateManager`, `AppUpdatePresenter` | `ALREADY_IMPLEMENTED` | Verified `NO_UPDATE` / downgrade protection («У вас установлена последняя версия») | **NO** |
| **HDR Stream Handling** | `TrackSelectorUtil`, `AndroidVoxCapabilityProvider` | `ALREADY_IMPLEMENTED` | Canonical track scoring maintained; no fake setting | **NO** |
| **DRC Audio** | ExoPlayer DASH manifest format parser | `ALREADY_IMPLEMENTED` | No platform DSP available; fake setting rejected | **NO** |

---

## 6. Patch #39 Feature Audit Ledger

| Subsystem / Feature | Existing Canonical Path | Classification | Decision / Action | Duplicate Created |
| :--- | :--- | :--- | :--- | :--- |
| **Offline Diagnostics Persistence** | `VoxDiagnosticsScheduler`, `vox_offline_diagnostics.json` | `EXTENDED` | Added atomic disk persistence, corruption self-healing, and session reload without duplicate queue | **NO** |
| **Idempotency & Re-upload Protection** | `VoxDiagnosticReport`, `storage.mjs` | `EXTENDED` | Stable client-generated `reportId` + D1 `ON CONFLICT DO UPDATE` prevents duplicates | **NO** |
| **Bounded Retry Backoff** | `VoxDiagnosticsScheduler`, `VoxDiagnosticsPolicy` | `EXTENDED` | Exponential backoff (15s to 15min) bounded at 5 attempts, preventing network tight loops | **NO** |
| **Startup Statistical Profiler** | `VoxStartupProfiler` | `EXTENDED` | Ring buffer for 50 samples, calculating median, p90, min/max, slowest stage and stage medians | **NO** |
| **Friendly Russian Issue Names** | `triage.mjs`, `admin_app.js` | `EXTENDED` | Added `USER_FRIENDLY_ISSUE_LABELS_RU` dictionary while preserving machine codes for triage | **NO** |
| **Image Pipeline Noise Filtering** | `VoxImageRetryPolicy` | `ALREADY_IMPLEMENTED` | Verified retry/recovered transitions and cancellation ignoring on fast scrolls | **NO** |
| **User Documentation** | `README.md` | `EXTENDED` | Complete Russian rewrite: user-friendly explanations, zero technical noise, links to `docs/` | **NO** |
