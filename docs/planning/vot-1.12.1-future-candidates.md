# VOT 1.12.1 Future Candidates Backlog (Post-9.1 / VOX 9.2)

> **Status:** DEFERRED TO VOX 9.2  
> **Policy:** Zero Production Changes in VOX 9.1 Hardening  
> **Audit Reference:** `build/reports/vot-1.12.1-audit/port-plan.md`  

---

## 1. Candidate Overview

Based on the static audit of `[VOT] - Voice Over Translation-1.12.1.user.js` vs. baseline `1.11.17`, all critical protobuf schemas, API endpoints, and HMAC signatures are 100% compatible with the current SmartTube VOX implementation. No breaking protocol changes or core bug fixes were found.

Two optional non-breaking enhancements have been identified and formally deferred to VOX 9.2:

---

## 2. Candidate A: Yandex Browser Component Version Update (`COMPONENT_VERSION`)

- **Candidate ID:** `CANDIDATE-VOT-01`
- **Source Feature:** VOT 1.12.1 configuration bump (`componentVersion: "26.10.0.1796"`).
- **Target File:** `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vot/yandex/YandexVotApiClient.java`.
- **Target Release:** VOX 9.2 (planned post-9.1 release).
- **Description:** Update static constant `COMPONENT_VERSION` from `"26.4.1.1026"` to `"26.10.0.1796"` and synchronize browser `User-Agent` version string.
- **Expected Benefit:** Proactively mirrors the latest desktop Yandex Browser component build string, mitigating any future server-side client version filtering or deprecation checks.
- **Identified Risks:** Negligible / Low. Yandex API currently accepts both versions without rejection.
- **Required Tests:**
  - Unit: `YandexVotApiClientTest` request header serialization test.
  - Emulator: `VOX_TV_API34` translation session handshake verification.
- **Rollback Strategy:** Revert constant string back to `"26.4.1.1026"`.

---

## 3. Candidate B: Music Clip Translation Suppression (`detectMusicYT`)

- **Candidate ID:** `CANDIDATE-VOT-02`
- **Source Feature:** VOT 1.12.1 `detectMusicYT` (InnerTube `WEB_REMIX` `musicVideoType` detection).
- **Target File:** `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/models/playback/controllers/VoiceTranslateController.java` & `VoiceTranslatePresenter.java`.
- **Target Release:** VOX 9.2 (planned post-9.1 release).
- **Description:** Provide an optional setting and UX notification to suppress automatic voice translation on official music videos where neural dubbing produces garbled or nonsensical output over singing vocals. SmartTube already has native metadata `video.isMusic()`.
- **Expected Benefit:** Improved user experience; eliminates unwanted vocal dubbing on music clips while leaving manual override option available.
- **Identified Risks:** Low. Must ensure user can still force-enable translation if desired.
- **Required Tests:**
  - Unit: Music classification predicate test in translation presenter.
  - UI/Emulator: Verify HUD prompt behavior when opening music videos with auto-translate enabled.
- **Rollback Strategy:** Feature flag default disabled or instant toggle disable.

---

## 4. Privacy & Telemetry Rejection Notice

- **Feature:** VOT 1.12.1 Sentry Ingest (`https://ingest.nirel.cc/api/1/envelope/`).
- **Verdict:** **PERMANENTLY REJECTED**.
- **Rationale:** External crash reporting to third-party endpoints violates SmartTube privacy standards. SmartTube VOX strictly uses its own self-hosted, telemetry-masked incident reporting system (`vox-diagnostics`) with Telegram notifications.
