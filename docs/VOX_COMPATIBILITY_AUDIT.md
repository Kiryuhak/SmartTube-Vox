# SMARTTUBE VOX — COMPATIBILITY & TECHNICAL AUDIT SPECIFICATION (PATCH #38)

## 1. Overview
This document provides a comprehensive technical audit of media playback, network transport, codec capabilities, and third-party application paradigms (e.g., ReVanced Extended) evaluated for SmartTube VOX on Android TV hardware.

---

## 2. Technical Audit Matrix

### 2.1 QUIC / HTTP/3
- **VOX Existing**: Chromium Cronet engine is used with `.enableQuic(true)`, `.enableHttp2(true)`, and `.enableBrotli(true)`. Automatic fallback to HTTP/2 over TLS/TCP occurs natively inside Chromium netstack when UDP is blocked or times out.
- **RVX Idea**: Exposing a user-facing toggle to force disable QUIC / force HTTP/2 when cellular or ISP routing throttles UDP.
- **Benefit**: Can bypass broken UDP throttling on certain regional ISPs.
- **Risk**: User confusion; Android TV devices are on fixed broadband / Wi-Fi where Chromium netstack already handles automatic fallback seamlessly without app intervention.
- **Decision**: **DEFER / NO USER TOGGLE**. The existing automatic Chromium fallback is robust and requires no redundant preference.

### 2.2 HDR (High Dynamic Range)
- **VOX Existing**: HDR streams are identified via `TrackSelectorUtil.isHdrFormat()`, with scoring boost (+3). `AndroidVoxCapabilityProvider` inspects display HDR capability through Android Display APIs (`Display.HdrCapabilities`).
- **RVX Idea**: Setting to force SDR / disable HDR streams on demand.
- **Benefit**: Prevents dark/washed-out picture or sluggish decoder performance on low-end TV chipsets with fake HDR panels.
- **Risk**: Creating duplicate settings outside the canonical track selector confuses users.
- **Decision**: **INTEGRATE INTO CANONICAL TRACK SELECTOR**. Force SDR is naturally accommodated by disabling the HDR preference boost in format evaluation, without creating a disconnected fake setting.

### 2.3 DRC (Dynamic Range Compression)
- **VOX Existing**: ExoPlayer 2.10.6 parses DASH manifest DRC tracks (`Format.isDrc`, `id` ending in `"drc"`).
- **RVX Idea**: Dynamic range compression setting to normalize volume across quiet dialogue and loud effects.
- **Benefit**: Improved evening watching without manual remote volume adjustments.
- **Risk**: Android TV OS provides no universal platform DSP API for DRC. Attempting software DSP on TV chipsets introduces CPU overhead and audio desync with voice translation tracks.
- **Decision**: **REJECT / NO FAKE SETTING**. Do not add a preference that cannot be reliably backed by hardware DSP across Android TV platforms.

### 2.4 Shorts VOX
- **VOX Existing**: In SmartTube VOX, Shorts are standard `Video` entities with `isShorts = true`. They are displayed via `ShortsCardPresenter` and played through canonical `PlaybackPresenter` / `PlayerUIController` / `VideoPlayerGlue`. Voice translation, Lively Voice, volume mixing (5% original / 100% translation), and session isolation (`mTranslationSessionId`) are natively active.
- **Missing**: None. Architecture is fully unified.
- **Decision**: **REUSE EXISTING CANONICAL CONTROLLER**. Creating any separate `ShortsTranslationController` or `ShortsPlayer` is strictly forbidden under the Anti-Duplication Rule.

### 2.5 YouTube Client Fallback
- **VOX Existing**: SmartTube utilizes `YouTubeMediaItemService` with multi-client Innertube requests (TV, Android, Web), automatic manifest retry, and 403 recovery mechanisms.
- **RVX Idea**: Client spoofing menu allowing users to pick simulated client types.
- **Benefit**: Can recover when specific client endpoints are temporarily throttled by YouTube.
- **Risk**: Exposing complex client menus confuses TV users and degrades playback consistency.
- **Decision**: **MAINTAIN BOUNDED INTERNAL FALLBACK**. Rely on canonical automatic retry and recovery paths without introducing mobile-centric spoofing UI.

### 2.6 Per-Network Quality Selection
- **Status**: **REJECTED (DO NOT IMPLEMENT)**.
- **Rationale**: Android TV devices are connected to stationary Wi-Fi or Ethernet; mobile cellular data throttling paradigms are irrelevant and add dead code.

---

## 3. Telemetry and Transport Safety
All network transport diagnostics maintain zero-PII guarantees:
- Protocol tracking (`H3`, `H2`, `HTTP_1_1`, `UNKNOWN`) is exposed via `VoxStartupProfiler`.
- Host queries, signed parameters, authentication tokens, and user watch identifiers are completely stripped prior to logging or report submission.
