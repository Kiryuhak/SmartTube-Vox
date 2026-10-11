# VOX Buffering & Live DVR Lifecycle Policy

## 1. Overview
Hardened buffering control for VOD playback, Live DVR window management, and background audio transitions.

## 2. Live DVR Window Clamp & Offset Lifecycle

### 2.1 Offset Reset on Stream Initialization
- Whenever a new stream starts (`onPlaybackStart` in `VoxLivePlaybackModel`), the accumulated Live offset (`currentLiveOffsetMs`) is reset to `0L`.
- Prevents stale DVR seek offsets from previous streams bleeding into new Live streams and causing immediate underruns.

### 2.2 Safe Window Clamping
- When the user seeks back in a Live stream, the requested position is clamped strictly within `[windowStartMs + 5000ms, windowEndMs - 2000ms]`.
- If the live edge drifts past the buffered window, an automatic `LIVE_SEEK_CLAMPED` event triggers smooth reconciliation rather than player stalls.

### 2.3 Rebuffer Recovery
- Rebuffer count and duration are tracked per session.
- If rebuffer duration exceeds 4500ms, the buffering controller requests reduced read-ahead chunks to unblock the main video decoder rapidly.
