#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SUITE="${1:-auto}"

echo "=================================================="
echo "SMART TEST SELECTION ENGINE (VOX PYRAMID)"
echo "Target suite: $SUITE"
echo "=================================================="

run_ota_fast() {
  echo "--- [SUITE: ota-fast] Running OTA forward compatibility suite ---"
  bash "$REPO_ROOT/scripts/verify_ota_forward_compatibility.sh"
}

run_ota_integration() {
  echo "--- [SUITE: ota-integration] Running all OTA unit tests ---"
  ./gradlew :common:testStvotDebugUnitTest --tests "com.liskovsoft.smartyoutubetv2.common.vox.ota.*"
}

run_vot_fast() {
  echo "--- [SUITE: vot-fast] Running VOT translation unit tests ---"
  ./gradlew :common:testStvotDebugUnitTest --tests "com.liskovsoft.smartyoutubetv2.common.vox.translation.*"
}

run_vot_full() {
  echo "--- [SUITE: vot-full] Running VOT translation + muxing suites ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.translation.*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.mux.*"
}

run_player_fast() {
  echo "--- [SUITE: player-fast] Running core player & double-back tests ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.player.*"
  ./gradlew :smarttubetv:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.tv.ui.playback.DoubleBackExitTest"
}

run_player_full() {
  echo "--- [SUITE: player-full] Running player and TV UI suites ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.player.*"
  ./gradlew :smarttubetv:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.tv.ui.*"
}

run_downloads_fast() {
  echo "--- [SUITE: downloads-fast] Running download tests ---"
  ./gradlew :common:testStvotDebugUnitTest --tests "com.liskovsoft.smartyoutubetv2.common.vox.download.*"
}

run_downloads_full() {
  echo "--- [SUITE: downloads-full] Running download + muxer tests ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.download.*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.mux.*"
}

run_live_fast() {
  echo "--- [SUITE: live-fast] Running live stream & live translation tests ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.Live*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLive*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxLive*"
}

run_live_full() {
  echo "--- [SUITE: live-full] Running full live stream suite ---"
  ./gradlew :common:testStvotDebugUnitTest \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.Live*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.playback.VoxLive*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxLive*" \
    --tests "com.liskovsoft.smartyoutubetv2.common.vox.translation.VoxPcmAudioConverterTest"
}

run_diagnostics_fast() {
  echo "--- [SUITE: diagnostics-fast] Running vox-diagnostics test suite ---"
  (cd "$REPO_ROOT/services/vox-diagnostics" && npm test)
}

run_dashboard_fast() {
  echo "--- [SUITE: dashboard-fast] Running dashboard operational & interactive tests ---"
  (cd "$REPO_ROOT/services/vox-diagnostics" && node --test test/dashboard_operational_hardening.test.mjs test/admin_interactive.test.mjs)
}

run_telegram_fast() {
  echo "--- [SUITE: telegram-fast] Running Telegram notification suite ---"
  (cd "$REPO_ROOT/services/vox-diagnostics" && node --test test/telegram_notifications.test.mjs)
}

run_ota_e2e() {
  echo "--- [SUITE: ota-e2e] Running OTA lab end-to-end suite on emulator ---"
  bash "$REPO_ROOT/scripts/ota_lab/run_ota_e2e.sh"
}

run_golden_smoke() {
  echo "--- [SUITE: golden-smoke] Running fast smoke across core subsystems ---"
  run_ota_fast
  run_vot_fast
  run_player_fast
  run_diagnostics_fast
}

run_full_regression() {
  echo "--- [SUITE: full-regression] Running full regression gate ---"
  ./gradlew :common:testStvotDebugUnitTest :smarttubetv:testStvotDebugUnitTest
  (cd "$REPO_ROOT/services/vox-diagnostics" && npm test)
}

case "$SUITE" in
  ota-fast)
    run_ota_fast
    ;;
  ota-integration)
    run_ota_integration
    ;;
  ota-e2e)
    run_ota_fast
    run_ota_integration
    run_ota_e2e
    ;;
  vot-fast)
    run_vot_fast
    ;;
  vot-full)
    run_vot_full
    ;;
  player-fast)
    run_player_fast
    ;;
  player-full)
    run_player_full
    ;;
  downloads-fast)
    run_downloads_fast
    ;;
  downloads-full)
    run_downloads_full
    ;;
  live-fast)
    run_live_fast
    ;;
  live-full)
    run_live_full
    ;;
  diagnostics-fast|diagnostics-full)
    run_diagnostics_fast
    ;;
  dashboard-fast)
    run_dashboard_fast
    ;;
  telegram-fast)
    run_telegram_fast
    ;;
  golden-smoke)
    run_golden_smoke
    ;;
  full-regression)
    run_full_regression
    ;;
  auto)
    CHANGED_FILES=$(git diff --name-only HEAD 2>/dev/null || true)
    UNTRACKED_FILES=$(git status --porcelain=v1 2>/dev/null | grep '^\?\?' | awk '{print $2}' || true)
    ALL_CHANGED="$CHANGED_FILES $UNTRACKED_FILES"

    MATCHED=0
    if echo "$ALL_CHANGED" | grep -qE "vox-diagnostics"; then
      run_diagnostics_fast
      MATCHED=1
    fi
    if echo "$ALL_CHANGED" | grep -qE "(ota|Ota)"; then
      run_ota_fast
      MATCHED=1
    fi
    if echo "$ALL_CHANGED" | grep -qE "(translation|vot|Vot)"; then
      run_vot_fast
      MATCHED=1
    fi
    if echo "$ALL_CHANGED" | grep -qE "(playback|player|PlaybackFragment|DoubleBack)"; then
      run_player_fast
      MATCHED=1
    fi
    if echo "$ALL_CHANGED" | grep -qE "(download|Download|mux|Mux)"; then
      run_downloads_fast
      MATCHED=1
    fi

    if [ "$MATCHED" -eq 0 ]; then
      echo "No targeted changed files detected. Running golden-smoke..."
      run_golden_smoke
    fi
    ;;
  *)
    echo "Unknown suite: $SUITE"
    echo "Available suites: ota-fast, ota-integration, ota-e2e, vot-fast, vot-full, player-fast, player-full, downloads-fast, downloads-full, live-fast, live-full, diagnostics-fast, dashboard-fast, telegram-fast, golden-smoke, full-regression"
    exit 1
    ;;
esac

echo ""
echo "✅ Test suite '$SUITE' finished successfully!"
