#!/usr/bin/env bash
set -euo pipefail

EMULATOR_BIN="${ANDROID_HOME:-$HOME/Android/Sdk}/emulator/emulator"
AVD_NAME="OTA_Lab_ATD_API_34"
SERIAL="emulator-5554"

echo "=== [OTA LAB] Starting / Verifying ATD Emulator ($AVD_NAME) ==="

is_booted() {
    local booted
    booted=$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null || echo "0")
    [ "$booted" = "1" ]
}

if adb devices | grep -q "$SERIAL"; then
    echo "Device $SERIAL detected."
    if is_booted; then
        echo "Emulator $SERIAL is already running and boot_completed=1."
    else
        echo "Waiting for $SERIAL to finish booting..."
        adb -s "$SERIAL" wait-for-device
        for _ in $(seq 1 30); do
            if is_booted; then break; fi
            sleep 1
        done
    fi
else
    echo "Launching $AVD_NAME in headless mode..."
    "$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > /dev/null 2>&1 &
    EMU_PID=$!
    echo "Emulator launched (PID: $EMU_PID). Waiting for device online..."
    adb wait-for-device
    for _ in $(seq 1 45); do
        if is_booted; then break; fi
        sleep 1
    done
fi

if is_booted; then
    echo "✅ ATD Emulator $SERIAL is ONLINE and ready (API $(adb -s "$SERIAL" shell getprop ro.build.version.sdk))."
    adb -s "$SERIAL" root 2>/dev/null || true
    exit 0
else
    echo "❌ Failed to verify emulator boot within timeout."
    exit 1
fi
