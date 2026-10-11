# VOX Deep Diagnostics Schema V3 & Operations Dashboard 3.0

## 1. Schema V3 (`vox-diagnostic-report-v3`)

### 1.1 Additions
- **Startup Profile**: Structured cold-start timings (`totalStartupMs`, `stages` breakdown) without PII.
- **Enhanced Safe Context**: Whitelist includes stage durations, network engine metadata, translation reuse state, and download performance metrics.
- **Backward Compatibility**: Fully accepts `vox-diagnostic-report-v1` and `vox-diagnostic-report-v2` payloads.

### 1.2 Deterministic Triage Lifecycle Prioritization
- Events are classified into three lifecycle tiers:
  1. `CURRENT_SESSION`: Events matching the active `playbackSessionId`.
  2. `RECENT`: Events within the last 15 minutes of report creation.
  3. `HISTORICAL`: Older background events.
- Guarantees that historical/stale background errors never become the report headline or issue title.

## 2. Operations Dashboard 3.0

### 2.1 KPI & Attention Architecture
- **Primary KPIs**: New Incidents, Active Issues, Live / DVR, Translation, Downloads, Network, Attention, Resolved.
- **Attention Cards**:
  - Live seek recovery
  - Translation startup >60s
  - Download packaging >120s
  - Network engine fallback
  - Thumbnail failures
- **Category Filter Chips**: All, Live, Translation, Download, Network, Player, OTA, Background, Image.

### 2.2 Deep Inspection Side Drawer
- **Tabs**:
  - **Обзор**: Incident headline, classified failure, quick subsystems.
  - **Timeline**: Startup Profiler timeline + visual session events with lifecycle tags.
  - **Сеть**: Engine health score, cooldown state, switch count, hold time, latency probe.
  - **Плеер**: Codec, resolution, live DVR offset, stalls and rebuffers.
  - **Перевод**: Session cache HIT/MISS, state machine stage, monotonic ETA.
  - **Downloads**: Granular stage timings, I/O duration, average/peak Mbps.
  - **Устройство**: Device tier, display resolution & HDR, hardware codecs.
  - **Кодеки**: Video & audio decoder tables.
  - **Разработчик**: Workflow history, developer notes, audit log.
  - **Raw JSON**: Sanitized payload viewer with copy action.
- **Quick Actions**:
  - 💾 Download sanitized JSON
  - 📋 Copy Technical Summary
  - 📋 Copy Antigravity Summary
  - 📋 Copy GitHub Issue Markdown
