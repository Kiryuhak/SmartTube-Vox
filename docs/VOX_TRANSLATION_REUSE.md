# VOX Translation Reuse 2.0 & Subtitle-Safe Overlay

## 1. Overview
Translation Reuse 2.0 implements a 4-tier lookup architecture and a monotonically non-increasing ETA countdown state machine, coupled with subtitle-safe overlay positioning.

## 2. 4-Tier Lookup Architecture
Before dispatching an expensive network translation request to the external Yandex VOT backend, the controller queries:
1. **Memory Session Cache**: Instantaneous in-memory lookup (`<1ms`). If present, audio track attaches immediately without spinner.
2. **Persistent SharedPreferences**: Persisted translated track metadata with a 7-day TTL across app restarts.
3. **Active Jobs Registry**: If an in-flight translation job is already processing the same canonical video URL and language pair, the second caller attaches to the existing job rather than spawning duplicate requests.
4. **Backend Ready Lookup**: Query provider backend for existing finished audio before requesting audio synthesis.

## 3. Monotonically Non-Increasing ETA Countdown
- **Problem Fixed**: Previously, backend polling could return varying queue estimates, causing the UI timer to count upwards (e.g. jumping from `00:08` to `+00:17` or `+01:09`), confusing the user.
- **State Machine Enforcement**:
  - `reconcileEtaSec(backendRemainingSec)`: The timer is strictly non-increasing: `effective = min(currentRemainingSec, backendRemainingSec)`.
  - Stages are profiled accurately: `lookupElapsedMs`, `requestElapsedMs`, `providerWaitElapsedMs`, `processingElapsedMs`, `audioAttachElapsedMs`, `totalTranslationStartupMs`.

## 4. Subtitle-Safe Overlay Layout Policy
- **Clearance Rule**: Guarantees zero subtitle overlap.
- **HUD Closed**: Bottom margin is locked at `192dp`, placing the overlay strictly above the subtitle zone (bottom ~140-160dp).
- **HUD Open**: Bottom margin dynamically shifts to `256dp`, clearing both subtitles and Leanback HUD transport bars.
- **Responsive Scaling**: On 4K / high-density displays, minimum vertical ratio ensures proportion remains >= 32% (or >= 42% when HUD open).
