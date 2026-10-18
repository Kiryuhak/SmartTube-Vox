# VOT Native Parity Status: userscript v1.11.17

**Reference Version**: 1.11.17  
**Reference SHA-256**: `4922bf1098224f545fb96a8de1202ba7a35166c144df5d39a05f69b4499ede6c`  
**Reference Lines**: 44,525 lines

---

## 1. Audit Summary Statistics

- **REFERENCE_VERSION**: 1.11.17
- **REFERENCE_SHA256**: `4922bf1098224f545fb96a8de1202ba7a35166c144df5d39a05f69b4499ede6c`
- **REFERENCE_LINES**: 44,525
- **FEATURE_GROUPS_TOTAL**: 49
- **ALREADY_EQUIVALENT**: 23
- **IMPLEMENTED_PHASE_B**: 9
- **IMPLEMENTED_PHASE_C**: 2
- **PARTIAL**: 7
- **MISSING**: 3
- **NOT_APPLICABLE_ANDROID**: 3 (Browser DOM scrapers/site adapters, Dynamic GitHub i18n localization, Keyboard hotkeys)
- **UI_PHASE_LATER**: 2 (Right translation plaque redesign, Telegram admin notifications)
- **BLOCKED_BY_EXTERNAL_SERVICE**: 0

### Functional Parity Calculation
Applicable native features: $49 - 3\text{ (NOT\_APPLICABLE)} - 2\text{ (UI\_PHASE\_LATER)} = 44$ features.
- Fully equivalent or implemented (Phase B & C): $23 + 9 + 2 = 34$ ($77.3\%$)
- Partial (Baseline tracking, Mute state, Video language cache, Auto subtitles, Subtitle styling, Account auth, Voice catalog): $7$ ($15.9\%$)
- Missing (Smart Ducking RMS speech gate, Volume Sync delta linking, Audio Booster): $3$ ($6.8\%$)
- **Core Protocol & Audio Lifecycle Parity**: **100%** of network, caching, dedup, and playback synchronization logic is fully verified.

---

## 2. What Was Already Present Prior to Hardening
- Core Yandex API HMAC-SHA256 signing and UUID device session generation (`YandexVotApiClient`, `YandexVotProtobuf`).
- ExoPlayer secondary audio track player (`TranslationAudioPlayer`, `YandexVotPlaybackAdapter`).
- L1 Local translation cache (`VoxTranslationSessionCache`).
- Bounded polling with ETA support (`YandexVotTiming`).
- Live stream translation subsystem (`VoxLive*`).
- Audio requested YouTube chunk upload & empty audio fallback bridge (`SmartTubeYandexVotAudioSourceBridge`, `YandexVotAudioTransfer`, `fail-audio-js`).
- Subtitles backend protobuf parser (`VotProtobuf`).
- Fixed volume reduction (classic auto-volume at 15%).
- Independent translation volume slider.
- Language normalization and Russian/target auto-skip policies.

---

## 3. What Was Implemented & Verified in Phase B & Phase C

### Phase B — Server-First Protocol Parity (Commit `58d837e1a`)
1. **Canonical YouTube URL**:
   - Centralized URL normalization to `https://youtu.be/<videoId>` across all entry points, eliminating divergent cache keys.
2. **Protobuf 1:1 Wire Parity**:
   - Fully synchronized `VideoTranslationRequest` (`configVersion=2`, `bypassCache=false`, `unknown0=true`, `unknown1=false`, `unknown2=true`, `translationHelp`, `wasStream`).
3. **Server-Cache-First Pipeline**:
   - Strict hierarchical lookup: `L1 Local Cache -> L2 /video-translation/cache -> Existing Job Reuse -> /translate on confirmed miss only`.
   - Classification of `CACHE_READY`, `CACHE_PROCESSING`, `CACHE_WAITING`, `CACHE_MISS`, `CACHE_ERROR`.
   - Transport/Network/Timeout/5xx errors are strictly prevented from causing duplicate translation creations (`Network != Miss`).
4. **In-Flight Deduplication & Stale Action Protection**:
   - Unified `VoxTranslationCoordinator` ensuring a single active network flow per canonical key.
   - Immediate reuse of active in-flight promises on double clicks or rapid navigation.
5. **Expired Audio URL Artifact Refresh**:
   - Re-queries existing server artifact on 403/410/resume without initiating a redundant translation task.
6. **Structured Diagnostics**:
   - Zero-leak logging with categorical hit/miss and lifecycle tokens.

### Phase C — Audio Lifecycle & AV Sync Parity (Commit `bc9a75878`)
1. **Deterministic `VoxAudioSyncPolicy`**:
   - 150ms drift tolerance window ($\le 150\,\text{ms} \implies \text{NOOP}$).
   - Hard seek on severe divergence ($> 1500\,\text{ms}$).
   - Soft seek with 3000ms cooldown hysteresis ($150\,\text{ms} < \Delta \le 1500\,\text{ms}$).
   - Duration clamping to $[0, \text{durationMs}]$.
2. **Master Video Buffering Hold**:
   - Immediately pauses follower playback when master video buffers; resumes automatically in sync when buffering completes.
3. **Live Playback Speed Synchronization**:
   - Dynamic rate updates on speed changes ($|\Delta\text{speed}| > 0.01$).
4. **Monotonic Generation Isolation**:
   - Monotonic `generationId` in `YandexVotPlaybackAdapter` discarding late callbacks on video switch.
5. **Bounded Route Fallback**:
   - 1-step direct S3 $\longleftrightarrow$ proxy worker fallback (`VoxAudioProxyFallback` + `BoundedRouteTracker`) eliminating infinite retry loops.
6. **In-Place Source Refresh**:
   - `refreshSource()` updating signed audio URL while preserving current position and speed.

---

## 4. Remaining Scope Breakdown

1. **PHASE D: Audio Mixing, Adaptive Volume & Smart Ducking**:
   - Real-time RMS speech gating on translated audio stream (`tickMs=50`, `thresholdOn=0.012`, `thresholdOff=0.009`, `holdMs=520`, `attackTau=110ms`, `releaseTau=600ms`).
   - Dynamic baseline volume tracking and manual remote volume override preservation.
   - Delta-based Volume Sync (`applyVolumeLinkDelta`).
   - Audio Booster gain amplification via Android `LoudnessEnhancer`.
2. **PHASE E: Language Policy & Subtitle Layout**:
   - Persistent `VideoLangCache` store.
   - Auto-subtitles fetching and presentation.
   - Subtitle text line wrapping and layout constraints.
3. **PHASE F: Account Auth & Voice Catalog**:
   - Yandex OAuth account token storage and expiration checks.
   - Full voice actor selection catalog.
4. **PHASE UI: Visual Overlays**:
   - Redesign of right-side translation plaque and TV HUD overlay.
