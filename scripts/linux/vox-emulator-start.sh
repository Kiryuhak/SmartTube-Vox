#!/usr/bin/env bash
# scripts/linux/vox-emulator-start.sh
# Launches Vox-TV-14 Android TV emulator and waits for boot completion

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR_BIN="${ANDROID_HOME}/emulator/emulator"
ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
AVD_NAME="Vox-TV-14"

echo "========================================="
echo "  Starting SmartTube VOX TV Emulator     "
echo "========================================="

# Check if emulator is already running
if pgrep -f "qemu-system.*${AVD_NAME}|emulator.*${AVD_NAME}" >/dev/null 2>&1 || pgrep -f "emulator.*${AVD_NAME}" >/dev/null 2>&1; then
    echo "Emulator is already running."
else
    echo "Launching emulator AVD: ${AVD_NAME}..."
    if [[ ! -x "${EMULATOR_BIN}" ]]; then
        echo "Error: Emulator binary not found at ${EMULATOR_BIN}" >&2
        exit 1
    fi
    "${EMULATOR_BIN}" -avd "${AVD_NAME}" -gpu auto >/dev/null 2>&1 &
fi

echo "Waiting for device to connect to ADB..."
"${ADB_BIN}" -s emulator-5554 wait-for-device

echo "Waiting for Android boot completion..."
BOOTED=0
for i in $(seq 1 60); do
    RES=$("${ADB_BIN}" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
    if [[ "${RES}" == "1" ]]; then
        BOOTED=1
        break
    fi
    sleep 2
done

if [[ "${BOOTED}" -eq 1 ]]; then
    echo "Emulator ${AVD_NAME} booted successfully! (sys.boot_completed = 1)"
else
    echo "Warning: Boot timeout reached, device may still be initializing."
fi

echo ""
