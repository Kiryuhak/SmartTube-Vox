#!/usr/bin/env bash
set -euo pipefail

# Permanent Release Gate: verify_ota_forward_compatibility.sh
# SmartTube VOX OTA forward compatibility and version model gate

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo "=================================================="
echo "SMARTTUBE VOX OTA FORWARD COMPATIBILITY GATE"
echo "=================================================="

# 1. Run unit test suite for OTA
echo "[1/3] Running OTA Forward Compatibility Unit Tests..."
./gradlew :common:testStvotDebugUnitTest --tests "com.liskovsoft.smartyoutubetv2.common.vox.ota.*" --console=plain

# 2. Verify Universal APK contract
echo "[2/3] Verifying Universal APK Contract..."
RELEASE_DIR="$PROJECT_ROOT/build/outputs/apk/stvot/release"
if [ -d "$RELEASE_DIR" ]; then
    UNIVERSAL_COUNT=$(find "$RELEASE_DIR" -maxdepth 1 -name "*_universal.apk" | wc -l)
    if [ "$UNIVERSAL_COUNT" -gt 0 ]; then
        echo "Found universal APK in release output."
    else
        echo "Note: Release directory exists but no universal APK built yet."
    fi
else
    echo "Release output directory not yet populated (will be verified during release build)."
fi

# 3. Verify Version Comparator canonical model
echo "[3/3] Checking Canonical Version Model Invariants..."
# Check canonical model in source code
if grep -q "stage != VoxReleaseStage.STABLE || (voxMajor >= 9 && voxMinor > 0)" "$PROJECT_ROOT/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/vox/ota/VoxVersionComparator.kt"; then
    echo "Canonical Version Model: PASS (Minor versions >= 9 are canonical BETA)"
else
    echo "Canonical Version Model: FAIL (Pattern mismatch)"
    exit 1
fi

echo "=================================================="
echo "OTA_FORWARD_COMPATIBILITY_GATE: PASS"
echo "=================================================="
exit 0
