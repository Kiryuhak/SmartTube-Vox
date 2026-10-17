# VOT userscript v1.11.17 vs SmartTube VOX: Gap Analysis

**Reference Version**: 1.11.17  
**Reference SHA-256**: `4922bf1098224f545fb96a8de1202ba7a35166c144df5d39a05f69b4499ede6c`  
**Reference Lines**: 44,525 lines

---

## 1. Executive Summary

The VOT userscript v1.11.17 represents a mature, browser-based voice-over translation client targeting Yandex's internal neural translation service (`api.browser.yandex.ru`). It features:
- A sophisticated **hierarchical cache-first flow** (L1 in-memory -> L2 `/video-translation/cache` -> existing job reuse -> normal `/translate` with `bypassCache=false`).
- An advanced **adaptive volume engine (Smart Ducking)** analyzing translated speech in real time with hysteresis, hold, and slew-rate limited transitions.
- Strict **in-flight deduplication** (`activeTranslation`), **stale-action protection** (`actionsGeneration`), and **single-flight session reuse**.
- Robust **audio URL refresh** for expired CDN links without triggering duplicate translation jobs.
- **Audio upload and fallback handling** (`fail-audio-js` + empty audio fallback).

SmartTube already possessed foundational Yandex VOT integration (Protobuf, HMAC signing, ExoPlayer dual-audio controller). However, key gaps existed in:
1. Full Protobuf field parity (`translationHelp`, `configVersion`, `bypassCache`, `wasStream`, `deviceId`).
2. Server-cache-first pipeline wiring: dedicated cache endpoint was partially introduced in commit `c28bd8374` but not yet fully integrated into error handling, retry policies, and session caching.
3. Native PCM-based Smart Ducking / Adaptive Volume engine (Android TV had fixed volume reduction or no ducking).
4. Audio URL refresh on 403/410/expiry.
5. In-flight deduplication between manual, auto-translate, and downloads.

---

## 2. Subsystem Gap Breakdown

### 2.1 Protocol & Wire Specification
- **Userscript**: Uses `VideoTranslationRequest` with `configVersion=2`, `bypassCache=false`, `unknown0=true`, `unknown1=false`, `unknown2=true`, and repeated `translationHelp` messages.
- **SmartTube Baseline**: `VotProtobuf` had partial field definitions; `YandexVotProtobuf` had some hardcoded ints and omitted `translationHelp` in `encodeTranslationRequest`.
- **Action**: Synchronize `YandexVotProtobuf` and `VotProtobuf` to 100% field parity with reference.

### 2.2 Server-Cache-First Pipeline
- **Userscript**: First checks L1 memory cache. If miss, calls `/video-translation/cache`. If status 1 (FINISHED) or 5 (PART_CONTENT), immediately reuses ready translation. If status 2 (WAITING) or 3 (LONG_WAITING), attaches to existing task and enters bounded countdown polling. Normal `/translate` is called ONLY after confirmed cache miss. Transport errors (network, timeout, 5xx) never count as MISS.
- **SmartTube Baseline**: Partial implementation in `VoxTranslationServerFirstPipeline.kt`, but fallback branches still directly triggered new translate calls or treated transport errors as miss.
- **Action**: Implement strict `L1 -> L2 (/cache) -> READY / PROCESSING REUSE -> /translate ONLY ON CONFIRMED MISS` state flow.

### 2.3 Audio Lifecycle & AV Sync
- **Userscript**: `chaimu/dist/player.js` synchronizes audio playback with video `timeupdate`, `playing`, `seeked`, `ratechange`, and `pause`.
- **SmartTube Baseline**: Handled by `TranslationAudioPlayer` / `YandexVotPlaybackAdapter` via ExoPlayer.
- **Action**: Enhance drift correction window and seek tolerance to match reference behavior without playback jitter.

### 2.4 Smart Ducking & Audio Mixing
- **Userscript**: Employs WebAudio `AnalyserNode` calculating RMS over 50ms ticks. Uses on-threshold 0.012, off-threshold 0.009, 520ms hold, 110ms attack tau, 600ms release tau, max down 3.5/s, max up 0.9/s, baseline volume tracking, and mute restoration.
- **SmartTube Baseline**: Had no native PCM speech gate for VOT.
- **Action**: Create native `VotSmartDuckingController` implementing the exact reference state machine, consuming translated PCM audio via an ExoPlayer `AudioProcessor` tap, with zero frame allocations and automatic fallback to fixed ducking if detector fails.

### 2.5 In-Flight Deduplication & Stale Action Protection
- **Userscript**: Maintains `activeTranslation = { key, promise }` where key is `${videoId}_${reqLang}_${resLang}_${useLively}_${helpHash}`. Concurrent requests wait on the same promise. Every navigation/video switch increments `actionsGeneration` and aborts previous operations.
- **SmartTube Baseline**: Had generation counter in `YandexVotOrchestrator`, but lacked unified coordinator spanning UI clicks, auto-translate, and downloads.
- **Action**: Implement unified `VotTranslationCoordinator` ensuring exactly one network flow per canonical key and graceful stale action cancellation.

### 2.6 Expired Audio Artifact Refresh
- **Userscript**: Detects expired/stale audio URLs (or on playback resume after long pause) and invokes `refreshTranslationAudio()`, refreshing signed URLs via existing translation lookup without creating new translations.
- **SmartTube Baseline**: Encountered errors led to complete retranslation flow.
- **Action**: Implement artifact refresh flow via `/video-translation/cache` and existing job lookup.

### 2.7 Browser DOM Adapters (Scope Classification)
- **Userscript**: Contains ~100 site adapters (Plyr, Video.js, Bilibili, Coursera, Vimeo, etc.).
- **SmartTube**: Android TV application targeting YouTube and native MediaItem playback.
- **Status**: Concrete browser DOM adapters are classified as `NOT_APPLICABLE_ANDROID`. Common ideas (canonical URL, translationHelp, audio download fallback) are extracted and ported.
