#!/usr/bin/env bash
# scripts/linux/vox-logcat.sh
# Clears logcat or monitors SmartTube VOX runtime errors and crashes

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB_BIN="${ANDROID_HOME}/platform-tools/adb"

CLEAR=0
FOLLOW=0

for arg in "$@"; do
    case "$arg" in
        --clear|-c) CLEAR=1 ;;
        --follow|-f) FOLLOW=1 ;;
    esac
done

if [[ "${CLEAR}" -eq 1 ]]; then
    echo "Clearing logcat buffer..."
    "${ADB_BIN}" -s emulator-5554 logcat -c
    echo "Logcat cleared."
    exit 0
fi

echo "========================================="
echo "  SmartTube VOX Logcat Error Monitor     "
echo "========================================="

if [[ "${FOLLOW}" -eq 1 ]]; then
    echo "Streaming logcat (filtered for errors and SmartTube)... (Ctrl+C to stop)"
    "${ADB_BIN}" -s emulator-5554 logcat -v time | grep -E "smarttubevot|SmartTubeVOT|FATAL|AndroidRuntime"
else
    echo "Checking recent logcat errors..."
    LOGS=$("${ADB_BIN}" -s emulator-5554 logcat -d -v time 2>/dev/null || true)
    FATAL_COUNT=$(echo "${LOGS}" | grep -c "FATAL EXCEPTION" || true)
    CRASH_COUNT=$(echo "${LOGS}" | grep -c "AndroidRuntime.*io\.github\.kiryuhak" || true)
    VOT_COUNT=$(echo "${LOGS}" | grep -cE "SmartTubeVOT-OAuth|VotData|VoiceTranslate" || true)

    echo "Fatal exceptions: ${FATAL_COUNT}"
    echo "App crashes:      ${CRASH_COUNT}"
    echo "VOT/OAuth logs:   ${VOT_COUNT}"

    if [[ "${FATAL_COUNT}" -gt 0 ]]; then
        echo -e "\n--- FATAL EXCEPTIONS ---"
        echo "${LOGS}" | grep -A 5 "FATAL EXCEPTION" | tail -n 10
    fi
    if [[ "${CRASH_COUNT}" -gt 0 ]]; then
        echo -e "\n--- APP CRASHES ---"
        echo "${LOGS}" | grep -A 5 "AndroidRuntime.*io\.github\.kiryuhak" | tail -n 10
    fi
    if [[ "${VOT_COUNT}" -gt 0 ]]; then
        echo -e "\n--- RECENT VOT / OAUTH LOGS ---"
        echo "${LOGS}" | grep -E "SmartTubeVOT-OAuth|VotData|VoiceTranslate" | tail -n 15
    fi
fi
