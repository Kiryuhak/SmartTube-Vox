# VOT userscript v1.11.17 Remaining Work Specification

**Reference Version**: 1.11.17  
**Reference SHA-256**: `4922bf1098224f545fb96a8de1202ba7a35166c144df5d39a05f69b4499ede6c`  
**Reference Lines**: 44,525 lines  
**Current HEAD**: `bc9a75878` (Phase B & C complete, tests passing)

---

## 1. Summary of Remaining Scope

Following the completion of **Phase B** (Protobuf / Server-First / Dedup / Session), **Phase C** (Audio Lifecycle / AV Sync / Route Fallback / Source Refresh), and **Phase D** (Audio Engine / Smart Ducking / Volume Sync / Audio Booster), the remaining applicable features are organized into targeted subsequent phases:

- **PHASE E**: Language Policy, Language Cache & Subtitles Presentation
- **PHASE F**: Account Authentication & Voice Selection Catalog
- **PHASE LIVE**: Live Stream Translation Verification & Hardening
- **PHASE UI**: Right-Side Translation Plaque & Leanback HUD Overlay

---

## 2. Itemized Remaining Work Table

| PRIORITY | FEATURE | REFERENCE SYMBOLS | CURRENT STATE | WHAT IS MISSING | ANDROID NATIVE PLAN | NEXT PHASE | ESTIMATED COMPLEXITY |
|---|---|---|---|---|---|---|---|
| **P0** | Smart Ducking (Adaptive Volume) | `SMART_DUCKING_DEFAULT_CONFIG`, `updateSpeechGate`, `computeSmartDuckingStep` in `src/videoHandler/modules/ducking.ts` | COMPLETED | None (1:1 reference math fully implemented) | ExoPlayer `VoxAudioLevelProcessor` PCM tap -> pure Kotlin `VoxSmartDuckingPolicy` -> `VoxAdaptiveVolumeController` smooth volume attenuation | COMPLETED (PHASE D) | Done |
| **P0** | Baseline Volume Tracking & Remote Override | `resolveBaseline`, `resolveDesiredVolume` in `ducking.ts` | COMPLETED | None (preserves user baseline across remote adjustments `abs(delta) > 0.02`) | Track manual remote volume adjustments in `VoxSmartDuckingPolicy` & `VoxAdaptiveVolumeController`; un-ducking returns to newly chosen volume | COMPLETED (PHASE D) | Done |
| **P1** | Mute State Preservation | `restoreAutoVolumeMute` in `ducking.ts` | COMPLETED | None (preserves mute state across duck/stop/switch) | Store `autoVolumeMutedOnStart`; restore player mute state on finish/un-duck in `VoxAdaptiveVolumeController` | COMPLETED (PHASE D) | Done |
| **P1** | Volume Sync (Delta Linking) | `syncVolume`, `applyVolumeLinkDelta` in `src/videoHandler/volumeLink.ts` | COMPLETED | None (bi-directional delta linking with programmatic guard) | `VoxVolumeLinkCoordinator` with reference arithmetic; auto-switches smart ducking to classic mode when active | COMPLETED (PHASE D) | Done |
| **P1** | Audio Booster (> 100% Volume) | `AudioPlayer.initAudioBooster`, WebAudio `gainNode` in `src/videoHandler/translationVolume.ts` (max up to 900%) | COMPLETED | None (Android DSP boost up to 300% safe TV limit) | `VoxAudioBooster` wrapping Android `LoudnessEnhancer` on translation audioSessionId (up to +12 dB) with safe error isolation | COMPLETED (PHASE D) | Done |
| **P2** | Video Language Cache | `VideoLangCacheRequest`, `VideoLangCacheResponse` in `@vot.js/core/dist/protobuf.js` | PARTIAL | Dedicated protobuf request/response to `/video-translation/lang-cache` and persistent store | Add `VideoLangCacheRequest` to `YandexVotProtobuf` and persist detected video language in Room DB / SharedPreferences | PHASE E | Low |
| **P2** | Auto Subtitles on Video Start | `autoSubtitles` in `src/VideoHandler.ts` | PARTIAL | Automatic background fetch of AI subtitles on eligible foreign videos | Add autoSubtitles trigger in `VoiceTranslateController.onNewVideo()` obeying language skip policies | PHASE E | Low |
| **P3** | Subtitles Layout & Word Highlighting | `subtitlesSmartLayout`, `highlightWords`, `src/subtitles/*` | PARTIAL | Word-level highlighting synchronized with speech audio and smart text wrapping | Parse word timing cues from `SubtitlesObject` into ExoPlayer `SpannableString` with dynamic background spans | PHASE E | Medium |
| **P2** | Yandex OAuth / VOT Account Authentication | `account`, `VOTLoginViaToken`, `deleteExpiredAccount` in `src/utils/account.ts` | PARTIAL | Storage of account tokens, expiration checks, and authenticated session header | Add optional OAuth token input in Settings -> Translation with automatic expiry clearing | PHASE F | Medium |
| **P3** | Voice Selection Catalog | `VOTVoiceSelection`, `translationHelp` in `src/VideoHandler.ts` | PARTIAL | Dynamic voice actor catalog parsing and selection beyond binary standard/lively | Parse available voices array from translation response and expose Leanback selection dialog | PHASE F | Medium |
| **P2** | Live Stream Keep-Alive Reliability | `StreamPingRequest`, `StreamTranslationRequest` in `chaimu` | ALREADY_EQUIVALENT | Periodic ping keep-alive verification across long-running streams | Verify scheduled executor pinging `/stream-translation/ping-stream` under network drops | PHASE LIVE | Low |
| **P3** | Right-Side Translation Plaque & HUD | `src/ui/views/overlay.ts`, lit-html templates | UI_PHASE_LATER | Modern Leanback TV right-side plaque with progress bar, volume sliders, and language selectors | Create dedicated Leanback overlay fragment matching Android TV D-pad UX guidelines | PHASE UI | High |

---

## 3. Phase Transition Roadmaps

### PHASE D: Audio Engine & Mixing Parity (COMPLETED)
- **Goal**: Full parity with userscript volume controls, smart ducking, and audio amplification.
- **Implemented Components**:
  - `VoxSmartDuckingPolicy.kt` (pure deterministic RMS speech gate state machine and math ported from `ducking.ts` & `smartDuckingRuntime.ts`).
  - `VoxAudioLevelProcessor.java` (ExoPlayer zero-allocation PCM tap in rendering thread calculating RMS energy).
  - `VoxVolumeLinkCoordinator.kt` (bi-directional delta linking between video and translation sliders with programmatic guard).
  - `VoxAudioBooster.java` (Android `LoudnessEnhancer` wrapper on translation audioSessionId for up to +12 dB amplification).
  - `VoxAdaptiveVolumeController.java` (orchestration, baseline tracking, mute preservation, tick scheduling).
  - Integration into `YandexVotPlaybackAdapter.java` and `VoiceTranslateController.java`.

### PHASE E: Language & Subtitle Parity
- **Goal**: Persistent language caching and subtitle formatting parity.
- **Components to Create/Modify**:
  - `VideoLangCache` protobuf encoder/decoder in `YandexVotProtobuf.java`.
  - Auto-subtitles coordination in `VoiceTranslateController.java`.
  - Cue word-timing parser in SmartTube subtitle manager.

### PHASE F: Accounts & Voice Selection Parity
- **Goal**: Account authentication support and multi-voice selection.
- **Components to Create/Modify**:
  - Account token preference storage with TTL.
  - Multi-voice metadata model and selection dialog.

### PHASE LIVE: Stream Translation
- **Goal**: Long-session stability and ping keep-alive reliability.

### PHASE UI: Right-Side Plaque & HUD
- **Goal**: Leanback visual polish for Android TV interface.
