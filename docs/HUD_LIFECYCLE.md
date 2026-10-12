# SMARTTUBE VOX — PLAYER HUD LIFECYCLE & SELF-HEALING ARCHITECTURE

## 1. Defect Description
Intermittent issue signature: `PLAYER_HUD_MISSING_AFTER_VIDEO_SWITCH`.
- **Symptom**: User plays Video A, exits or advances to Video B. Video B plays normally (audio, video, play/pause respond to remote), but HUD (playback controls, seekbar, settings menu) cannot be shown via D-Pad/OK.
- **Classification**: UI lifecycle synchronization defect, not a playback failure.

---

## 2. Root Cause Analysis

Three distinct race conditions were identified in the Leanback playback pipeline:
1. **Stale Visibility Cache**: In Leanback `PlaybackFragment`, `mIsControlsShownPreviously` remained `true` across consecutive video changes. A subsequent call to `showControlsOverlay(true)` was treated as a no-op because the fragment believed controls were already visible.
2. **Stale Auto-Hide Runnable**: Video A scheduled an auto-hide runnable to execute after a delay (e.g. 3000 ms). When Video B loaded after 2000 ms, the expired timer from Video A executed inside Video B's session, hiding controls prematurely.
3. **Focus Loss**: On video transitions, `VerticalGridView` or controls row lost focus to the window decor view. Key events (DPAD_CENTER, DPAD_DOWN) were consumed as no-ops by the window decor rather than dispatching to `showOverlay()`.

---

## 3. Invariants & Solution

### 3.1 Core Invariant
```
PLAYER_READY / PLAYING + PlaybackFragment RESUMED + DPAD_OK / UP / DOWN = HUD MUST BE SHOWABLE
```

### 3.2 Session-Scoped Runnables
- Monotonically increasing `mHudSessionId` in `PlayerUIController`.
- All auto-hide runnables capture the session ID at scheduling time.
- If `callbackSessionId != currentSessionId`, the callback is dropped (`HUD_STALE_CALLBACK_DROPPED`).

### 3.3 State Reset on Switch
- On fragment destruction or video switch, `mIsControlsShownPreviously` is reset to `null`, ensuring clean glue re-synchronization.

### 3.4 Bounded Self-Healing
- If HUD fails to show when requested by the user, `showOverlayWithSelfHeal()` executes:
  - Cancels stale runnables.
  - Ensures control glue is attached.
  - Restores focus to fragment view hierarchy.
  - Re-triggers `showOverlay(true)`.
  - **Bounded to exactly 1 attempt per session**, preventing infinite loops or recursion.

### 3.5 Safe Observability
Events logged under category `UI`:
`HUD_SESSION_STARTED`, `HUD_SESSION_ENDED`, `HUD_CONTROLS_ATTACH_STARTED`, `HUD_CONTROLS_ATTACHED`, `HUD_CONTROLS_DETACHED`, `HUD_SHOW_REQUESTED`, `HUD_SHOWN`, `HUD_HIDE_REQUESTED`, `HUD_HIDDEN`, `HUD_FOCUS_RESTORED`, `HUD_STALE_CALLBACK_DROPPED`, `HUD_STATE_INCONSISTENT`, `HUD_SELF_HEAL_STARTED`, `HUD_SELF_HEAL_SUCCESS`, `HUD_SELF_HEAL_FAILED`.
Zero PII is logged (no titles, URLs, or account tokens).
