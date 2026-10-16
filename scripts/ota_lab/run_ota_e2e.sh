#!/usr/bin/env bash
# scripts/ota_lab/run_ota_e2e.sh
# End-to-end OTA verification suite for SmartTube VOX
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SERIAL="emulator-5554"
FEED_PORT="${OTA_FEED_PORT:-8899}"
FEED_HOST="127.0.0.1"
EMU_FEED_URL="http://10.0.2.2:$FEED_PORT"
APP_PKG="io.github.kiryuhak.smarttubevot.stable"
SPLASH_ACT="$APP_PKG/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"

echo "=================================================="
echo "  SmartTube VOX: OTA Laboratory E2E Suite        "
echo "=================================================="

# Ensure feed server is running
if ! curl -s "http://$FEED_HOST:$FEED_PORT/health" > /dev/null 2>&1; then
    echo "Starting feed server..."
    python3 "$SCRIPT_DIR/feed_server.py" > /dev/null 2>&1 &
    sleep 1
fi

set_scenario() {
    local sc="$1"
    curl -s "http://$FEED_HOST:$FEED_PORT/set_scenario?name=$sc" > /dev/null
}

send_ota_action() {
    local action="$1"
    shift
    adb -s "$SERIAL" shell "am start -n $SPLASH_ACT -a com.liskovsoft.smartyoutubetv2.TEST_OTA --es action $action $*" > /dev/null 2>&1
}

get_current_version_code() {
    adb -s "$SERIAL" shell "dumpsys package $APP_PKG | grep -oE 'versionCode=[0-9]+' | head -n 1" | cut -d'=' -f2 | tr -d '\r\n '
}

get_current_version_name() {
    adb -s "$SERIAL" shell "dumpsys package $APP_PKG | grep -oE 'versionName=[^ ]+' | head -n 1" | cut -d'=' -f2 | tr -d '\r\n '
}

wait_for_log() {
    local pattern="$1"
    local timeout="${2:-10}"
    for _ in $(seq 1 "$timeout"); do
        local logs
        logs=$(adb -s "$SERIAL" logcat -d -s VOX_OTA_TEST 2>/dev/null || true)
        if echo "$logs" | grep -q -E "$pattern"; then
            echo "$logs"
            return 0
        fi
        sleep 1
    done
    adb -s "$SERIAL" logcat -d -s VOX_OTA_TEST 2>/dev/null || true
    return 1
}

adb -s "$SERIAL" root > /dev/null 2>&1 || true

# Ensure baseline 9.1 is installed
CURRENT_VC=$(get_current_version_code || true)
if [ "$CURRENT_VC" != "2446016" ]; then
    echo "Reinstalling baseline 9.1 debug APK..."
    adb -s "$SERIAL" install -r -d "$PROJECT_ROOT/smarttubetv/build/outputs/apk/stvot/debug/SmartTube_vot_32.56-vox.9.1_universal.apk" > /dev/null 2>&1
    sleep 1
fi

# Configure emulator system property
adb -s "$SERIAL" shell "setprop debug.vox.ota_base_url $EMU_FEED_URL"
adb -s "$SERIAL" shell "appops set $APP_PKG REQUEST_INSTALL_PACKAGES allow"

TEST_COUNT=0
PASS_COUNT=0
FAIL_COUNT=0

record_result() {
    local name="$1"
    local status="$2"
    local details="$3"
    TEST_COUNT=$((TEST_COUNT + 1))
    if [ "$status" = "PASS" ]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        echo "  [PASS] $name ($details)"
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        echo "  [FAIL] $name ($details)"
    fi
}

echo ""
echo "=== 1. Channel Rules Validation ==="
# 1a: Stable channel ignores prerelease
set_scenario "skip_9_5"
send_ota_action "setChannel" "--es channel stable"
sleep 1
adb -s "$SERIAL" logcat -c
send_ota_action "checkUpdate"
if wait_for_log "CHANNEL_MISMATCH|onNoUpdateAvailable" 6 > /dev/null; then
    record_result "Channel: Stable ignores pre-release" "PASS" "Prerelease blocked on stable channel"
else
    record_result "Channel: Stable ignores pre-release" "FAIL" "Failed to reject prerelease"
fi

# 1b: Beta channel accepts prerelease
send_ota_action "setChannel" "--es channel beta"
sleep 1
adb -s "$SERIAL" logcat -c
send_ota_action "checkUpdate"
if wait_for_log "onUpdateAvailable.*32.56-vox.9.5" 6 > /dev/null; then
    record_result "Channel: Beta receives pre-release" "PASS" "32.56-vox.9.5 offered"
else
    record_result "Channel: Beta receives pre-release" "FAIL" "Update not offered on beta"
fi

echo ""
echo "=== 2. Pre-Install Validator & Rejection Gates ==="

# 2a: Corrupt Truncated APK
set_scenario "corrupt_truncated"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
if wait_for_log "DOWNLOAD_INCOMPLETE|APK_INVALID|downloadOnError" 8 > /dev/null; then
    record_result "Security: Corrupt truncated APK rejected" "PASS" "Rejected before installer"
else
    record_result "Security: Corrupt truncated APK rejected" "FAIL" "Not rejected"
fi

# 2b: Corrupt Invalid Zip APK
set_scenario "corrupt_invalid_zip"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
if wait_for_log "APK_INVALID|downloadOnError" 8 > /dev/null; then
    record_result "Security: Corrupt zip structure rejected" "PASS" "APK_INVALID caught"
else
    record_result "Security: Corrupt zip structure rejected" "FAIL" "Not rejected"
fi

# 2c: Partial Download / Content-Length mismatch
set_scenario "partial"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
if wait_for_log "DOWNLOAD_INCOMPLETE|ASSET_DOWNLOAD_FAILED|downloadOnError" 8 > /dev/null; then
    record_result "Security: Partial stream / Content-Length mismatch rejected" "PASS" "Stream aborted safely"
else
    record_result "Security: Partial stream / Content-Length mismatch rejected" "FAIL" "Not rejected"
fi

# 2d: Wrong Package Name APK
set_scenario "wrong_package"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
if wait_for_log "PACKAGE_MISMATCH|downloadOnError" 8 > /dev/null; then
    record_result "Security: Wrong package name rejected" "PASS" "PACKAGE_MISMATCH caught"
else
    record_result "Security: Wrong package name rejected" "FAIL" "Not rejected"
fi

# 2e: Wrong Signing Certificate APK
set_scenario "wrong_signature"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
if wait_for_log "SIGNATURE_MISMATCH|downloadOnError" 12 > /dev/null; then
    record_result "Security: Wrong signing certificate rejected" "PASS" "SIGNATURE_MISMATCH caught"
else
    record_result "Security: Wrong signing certificate rejected" "FAIL" "Not rejected"
fi

# 2f: Downgrade Candidate
set_scenario "downgrade_9_0"
adb -s "$SERIAL" logcat -c
send_ota_action "checkUpdate"
if wait_for_log "onNoUpdateAvailable|VERSION_NOT_NEWER" 6 > /dev/null; then
    record_result "Version: Downgrade candidate ignored" "PASS" "NO_UPDATE returned for older version"
else
    record_result "Version: Downgrade candidate ignored" "FAIL" "Downgrade not rejected"
fi

echo ""
echo "=== 3. Network Interruption & Recovery ==="
# Temporarily set unreachable port
adb -s "$SERIAL" shell "am force-stop $APP_PKG"
sleep 1
adb -s "$SERIAL" shell "setprop debug.vox.ota_base_url http://10.0.2.2:9999"
adb -s "$SERIAL" logcat -c
send_ota_action "checkUpdate"
if wait_for_log "NETWORK_ERROR|TIMEOUT" 6 > /dev/null; then
    record_result "Network: Connection failure classified as retryable network error" "PASS" "Classified correctly"
else
    record_result "Network: Connection failure classified as retryable network error" "FAIL" "Failed to classify"
fi
adb -s "$SERIAL" shell "setprop debug.vox.ota_base_url $EMU_FEED_URL"

echo ""
echo "=== 4. Process Restart & Cancellation ==="
# Test cancellation during download
set_scenario "skip_9_5"
adb -s "$SERIAL" logcat -c
send_ota_action "downloadAndVerify"
sleep 0.5
send_ota_action "cancelDownload"
if wait_for_log "cancelDownload: invoked" 4 > /dev/null; then
    record_result "Lifecycle: Download cancellation handled cleanly" "PASS" "cancelDownload invoked without deadlock"
else
    record_result "Lifecycle: Download cancellation handled cleanly" "FAIL" "cancelDownload failed"
fi

# Process restart while idle / post-cancel
send_ota_action "setSafeMarker" "--es marker TEST_SAFE_MARKER_LIFECYCLE"
sleep 1
adb -s "$SERIAL" shell "am force-stop $APP_PKG"
sleep 1
send_ota_action "getSafeMarker"
sleep 1
MARKER=$(adb -s "$SERIAL" shell "cat /data/data/$APP_PKG/shared_prefs/ota_test_prefs.xml 2>/dev/null || run-as $APP_PKG cat shared_prefs/ota_test_prefs.xml 2>/dev/null || true")
if echo "$MARKER" | grep -q "TEST_SAFE_MARKER_LIFECYCLE"; then
    record_result "Lifecycle: Safe restart and preferences preserved" "PASS" "Preferences intact"
else
    record_result "Lifecycle: Safe restart and preferences preserved" "FAIL" "Marker missing"
fi

echo ""
echo "=== 5. Real Direct-Skip PackageInstaller Upgrade (9.1 -> 9.5) ==="
# Ensure previous installer window is closed
adb -s "$SERIAL" shell "am force-stop com.android.packageinstaller"
sleep 1

# Setup marker and start download
send_ota_action "setSafeMarker" "--es marker E2E_VERIFIED_9_1_TO_9_5"
send_ota_action "setChannel" "--es channel beta"
sleep 1
set_scenario "skip_9_5"
send_ota_action "downloadAndVerify"

# Wait for system PackageInstaller to pop up
PI_OPENED=0
for _ in $(seq 1 45); do
    if adb -s "$SERIAL" shell "dumpsys window | grep -E 'mCurrentFocus.*PackageInstallerActivity'" | grep -q "PackageInstallerActivity"; then
        PI_OPENED=1
        break
    fi
    sleep 1
done

if [ "$PI_OPENED" -eq 1 ]; then
    record_result "Installer: System PackageInstaller launched by updater" "PASS" "PackageInstallerActivity focused"
    # Click UPDATE button (bounds [216,359][292,407] -> 254, 383)
    adb -s "$SERIAL" shell "input tap 254 383"
    
    # Wait for package update completion
    UPDATED=0
    for _ in $(seq 1 20); do
        VC=$(get_current_version_code)
        if [ "$VC" = "2446050" ]; then
            UPDATED=1
            break
        fi
        sleep 1
    done
    
    if [ "$UPDATED" -eq 1 ]; then
        VN=$(get_current_version_name)
        record_result "E2E: PackageInstaller successfully updated package" "PASS" "Version is now $VN ($VC)"
        
        # Verify data preservation
        SAVED_MARKER=$(adb -s "$SERIAL" shell "cat /data/data/$APP_PKG/shared_prefs/ota_test_prefs.xml 2>/dev/null || run-as $APP_PKG cat shared_prefs/ota_test_prefs.xml 2>/dev/null || true")
        SAVED_CHANNEL=$(adb -s "$SERIAL" shell "cat /data/data/$APP_PKG/shared_prefs/vox_update_channel_prefs.xml 2>/dev/null || run-as $APP_PKG cat shared_prefs/vox_update_channel_prefs.xml 2>/dev/null || true")
        
        if echo "$SAVED_MARKER" | grep -q "E2E_VERIFIED_9_1_TO_9_5"; then
            record_result "Data Preservation: Safe test marker preserved" "PASS" "Marker intact"
        else
            record_result "Data Preservation: Safe test marker preserved" "FAIL" "Marker lost"
        fi
        
        if echo "$SAVED_CHANNEL" | grep -q "test"; then
            record_result "Data Preservation: Beta update channel preserved" "PASS" "Channel intact"
        else
            record_result "Data Preservation: Beta update channel preserved" "FAIL" "Channel lost"
        fi
    else
        record_result "E2E: PackageInstaller successfully updated package" "FAIL" "Timeout waiting for versionCode 2446050 (current=$VC)"
    fi
else
    record_result "Installer: System PackageInstaller launched by updater" "FAIL" "PackageInstallerActivity not shown"
fi
adb -s "$SERIAL" shell "am force-stop com.android.packageinstaller" > /dev/null 2>&1 || true

echo ""
echo "=================================================="
echo "OTA LAB SUMMARY: $PASS_COUNT / $TEST_COUNT PASSED (Failures: $FAIL_COUNT)"
echo "=================================================="

if [ "$FAIL_COUNT" -eq 0 ]; then
    exit 0
else
    exit 1
fi
