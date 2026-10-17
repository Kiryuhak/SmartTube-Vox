# SmartTube VOX 9.1 — Migration Snapshot (2026-10-17)

## 1. Project & Migration Metadata
- **Date**: 2026-10-17
- **Source OS**: CachyOS Linux (x86_64)
- **Target OS**: Ubuntu Linux
- **Repository Path**: `/home/kiryuha/Developer/SmartTube`
- **Current Branch**: `vox9.1/nightly-hardening-1`
- **Current HEAD**: `c28bd837474b511502401fafea1d0c1564fff89f`
- **Production Tag**: `v32.56-vox.9.1` -> `51792b150e925c87e02f0876fa4a1b8ff89d79cd` (INTACT, NOT MOVED)
- **Remote Origin**: `https://github.com/Kiryuhak/SmartTube-Vox.git`
- **Remote Upstream**: `https://github.com/yuliskov/SmartTube.git`

---

## 2. Recent Key Commits
- `c28bd8374` - VOX VOT: добавлен серверный поиск перевода через /video-translation/cache и канонический URL
- `d1772e9af` - VOX VOT: плашка перевода приведена к точному макету и отключен прогресс в HUD
- `a60d35504` - VOX VOT: прогресс круга начинается с 0%
- `8bfe73618` - VOX VOT: готовый перевод автоматически переключает русскую аудиодорожку
- `65f3f5013` - VOX VOT: плашка перевода зафиксирована справа и показывает прогресс 0–100
- `b659f2f8e` - VOX DATA: усилена совместимость миграций пользовательских данных
- `d8e183971` - VOX RELEASE: усилен production manifest и сетевой security contract
- `88966d138` - VOX SECURITY: тестовые диагностические действия изолированы от release-сборки
- `620016aa0` - VOX CHANNELS: добавлены диагностические действия для UI-валидации групп на эмуляторе
- `55321b17a` - VOX PLAYER: добавлены стресс-тесты жизненного цикла и инвариантов памяти
- `c76b2ceb5` - VOX DOWNLOADS: добавлен воспроизводимый E2E-стенд загрузки и воспроизведения переведённых видео
- `be7b2d7e3` - VOX Diagnostics: уточнена классификация и lifecycle ошибок загрузок

---

## 3. Subsystem Architecture State

### 3.1 VOT (Voice-Over-Translation) Server-First Architecture
- **Canonical YouTube URL Normalization**: all YouTube identifiers and URLs are canonicalized to `https://youtu.be/$videoId` matching Yandex indexer contract.
- **Cache Hierarchy**:
  1. `L1 Local Cache`: in-memory LRU + persistent session cache.
  2. `L2 Server Cache`: queries `/video-translation/cache` using Protobuf (`VideoTranslationCacheRequest`/`VideoTranslationCacheResponse`).
  3. `L3 Server Translate`: queries `/video-translation/translate` with `firstRequest=true` and `bypassCache=false` for immediate playback URL.
  4. `Processing Job Reuse`: if server reports `STATUS_WAITING`, attaches to existing job ID without duplicate requests.
- **Automatic Audio Switch**: automatically ducks original audio and sets active Russian voice track upon playback confirmation.

### 3.2 VOT Right-Side Overlay Contract
- **Single Source of Truth**: `RIGHT_OVERLAY_IS_SINGLE_SOURCE_OF_TRUTH = TRUE`.
- **HUD Secondary Progress**: `HUD_VOT_PROGRESS = DISABLED`. Secondary progress drawables and dynamic ETA text in HUD buttons are disabled.
- **Visual Design & Timing (Mockup Frame 1-4)**:
  - Initial state: `[ ✨ Подготовка перевода ]` (`ic_vot_sparkles.xml`, 196dp width, 28dp corner radius).
  - Processing: smooth collapse to 56dp circle anchored to the right (`( 0% )` ... `( 99% )`).
  - Completion: `( 100% )` reached ONLY after audio attach is confirmed active, held for 320ms (`SHOW_100_DURATION_MS in 250..400ms`).
  - Confirmation: smooth expansion to `[ ✔ Перевод готов ]`, held for 2000ms (`BUBBLE_READY_AUTO_HIDE_DELAY_MS`), followed by fade-out.
  - Geometry: fixed right anchor (`endMargin=48dp`, `bottomMargin=80dp`).

### 3.3 Channel Groups & Player Memory
- Side panel pinning, numerical naming, order persistence.
- Memory leak and OOM suppression hardening in ExoPlayer lifecycle.

### 3.4 Downloads & Translated Playback
- Atomic packaging, resume on retry, local metadata healing.
- E2E validation script: `scripts/verify_downloads_e2e.sh`.

---

## 4. Verification & Quality Gates
- `vot-fast`: PASS
- `player-fast`: PASS
- `smarttubetv` contract test: PASS (`VoiceTranslateActionContractTest`)
- `full-regression`: PASS
  - `:common:testStvotDebugUnitTest` (ALL PASSED)
  - `:smarttubetv:testStvotDebugUnitTest` (ALL PASSED)
  - `services/vox-diagnostics` test suite (101 passed, 0 failed)

---

## 5. Release Constraints & Invariants
- `NO_RELEASE`: No GitHub release, no tag movement, no version bump.
- `TAG_HASH`: `v32.56-vox.9.1` stays at `51792b150e925c87e02f0876fa4a1b8ff89d79cd`.
- `PHYSICAL_TV`: Not used during automated runs (`PHYSICAL_TV_ALLOWED_THIS_RUN: NO`).

---

## 6. Ubuntu Restore Verification Commands
```bash
# Verify cloned tree after migration
cd /path/to/SmartTube
git branch --show-current
git rev-parse HEAD
git rev-parse v32.56-vox.9.1

# Verify test pyramid on Ubuntu
./scripts/run_smart_tests.sh vot-fast
./scripts/run_smart_tests.sh player-fast
(cd services/vox-diagnostics && npm test)
```
