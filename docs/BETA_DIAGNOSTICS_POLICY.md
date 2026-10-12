# SMARTTUBE VOX — BETA AUTOMATIC DIAGNOSTICS & PRIVACY POLICY 2.0

## 1. Overview
SmartTube VOX implements a privacy-first, channel-aware automated error telemetry policy designed to catch and fix regressions rapidly in pre-release builds while respecting total user privacy.

---

## 2. Channel-Aware Policy

| Channel | Default Auto-Diagnostics | User Toggle Availability | Telemetry Scope |
| :--- | :--- | :--- | :--- |
| **DEV / ALPHA / BETA / RC** | **ON (Default)** | Yes (can disable anytime in Settings) | Aggregated, sanitized technical incidents |
| **STABLE** | **OFF (Default)** | Voluntary opt-in only | Strictly opt-in technical error telemetry |

### 2.1 Consent Reset on Channel Switch
When a user switches their update channel from `BETA` (or other pre-release) to `STABLE`, `VoxUpdateChannelManager.resetBetaConsentOnSwitchToStable()` is invoked:
- Automated diagnostics submission is reset to `false`.
- Ensures stable channel users are never tracked without explicit opt-in consent.

---

## 3. Strict Zero-PII Guarantees

All diagnostics undergo strict sanitization before transmission via `VoxDiagnosticSanitizer`:
The following data are **strictly and permanently excluded**:
- Video titles, descriptions, and watch history
- User accounts, channel names, and Google IDs
- OAuth access and refresh tokens, authorization headers
- Cookies, session tokens, and passwords
- IP addresses, MAC addresses, and Wi-Fi SSIDs
- Signed URLs (e.g. `googlevideo.com/videoplayback?...`)
- URL query parameters
- Subtitle text, translation audio/text, and user input

---

## 4. Aggregation and Rate Limiting

To protect backend bandwidth and avoid flooding error reporting:
- **Incident Window**: 45 seconds (`INCIDENT_AGGREGATION_WINDOW_MS = 45_000L`). Bursts of repetitive playback errors are deduplicated into a single report with `repeatCount`.
- **Minimum Interval**: 5 minutes (`INCIDENT_MIN_INTERVAL_MS = 300_000L`) between reports per category.
- **Bounded Offline Queue**: Maximum 10 reports (`MAX_OFFLINE_QUEUE_SIZE = 10`). Drops oldest reports first on network recovery backoff.

---

## 5. Report Purpose Classification

Reports are explicitly tagged with `reportPurpose`:
- `AUTO_BETA`: Automated sanitized reports from pre-release builds.
- `USER_MANUAL`: Explicitly submitted by the user via "Отправить отчёт разработчику".
- `EMULATOR_TEST`: Synthetic verification and soak reports from Android TV emulators.

---

## 6. Real E2E Verification & Offline Resilience (Patch #38)

- **Verification Environment**: Android TV Emulator `Vox-TV-14` (API 34).
- **Incident Flow**: Real runtime incident (`HUD_STATE_INCONSISTENT`) was generated, aggregated 3x (`repeatCount = 3`) within the 45-second window, and escalated to immediate upload upon critical trigger.
- **Backend Receipt**: D1 report `VOX-D8FA3F` received with `report_purpose = 'AUTO_BETA'`, `auto_submitted = true`, `incidentSummary` containing session IDs, normalized signature, and timing metrics.
- **Triage & Audit**: Emulator test report updated to `IGNORED_TEST` to preserve database integrity and isolate from user-facing issues.
- **Offline Bounded Queue**: Enforces max size 10 (`AUTO_DIAG_UPLOAD_QUEUED`, `AUTO_DIAG_UPLOAD_DROPPED`), retrying oldest entries on connectivity restoration with exponential backoff (`AUTO_DIAG_UPLOAD_RETRY`).
