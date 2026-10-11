# VOX Network Engine 2.0: Adaptive Architecture & Reliability Policy

## 1. Overview
VOX Network Engine 2.0 provides adaptive runtime transport selection (Cronet / OkHttp) with strict hysteresis, anti-ping-pong guards, and on-demand network health diagnostics.

## 2. Key Architecture Principles

### 2.1 Anti-Ping-Pong & Hysteresis Guards
- **Minimum Hold Time**: Once switched to an engine, the system enforces a strict 60-second hold period (`MIN_HOLD_TIME_MS = 60_000L`) before any alternative engine switch can be considered.
- **Engine Cooldown**: Switching back to the immediately previous engine is locked out for 60 seconds (`ENGINE_COOLDOWN_MS = 60_000L`).
- **Session Switch Budget**: A sliding window of 5 minutes allows a maximum of 2 engine switches per session (`MAX_SWITCHES_PER_SESSION = 2`). Once exhausted, the engine remains fixed to prevent connection churn.
- **Consecutive Failure Threshold**: Engine fallback requires at least 2 consecutive failure signals (`CONSECUTIVE_FAILURES_BEFORE_SWITCH = 2`) after an in-place reconnect attempt. Non-network errors (e.g., decoder crashes, missing DRM keys) strictly block engine switching.

### 2.2 Network Health & Latency Probe
- Lightweight on-demand probe executed in parallel when user enters Network Engine settings dialog.
- Measures DNS lookup latency, TCP connect time, and HTTP handshake speed without heavy background ping loops or battery drain.
- Live UI feedback: Displays active latency in milliseconds and health indicator (`Optimal`, `Degraded`, `Cooldown active`).

### 2.3 Traffic Profiles
- **VOD**: Large read-ahead buffers with Cronet QUIC/HTTP3 priority.
- **LIVE**: Low-latency chunk fetching with OkHttp fallback on socket resets.
- **DOWNLOAD**: High-throughput multi-stream TCP chunking.
- **TRANSLATION_API**: Immediate JSON RPC transport with timeout bounded to VOT backend limits.
