#!/usr/bin/env bash
# scripts/linux/vox-emulator-stop.sh
# Gracefully shuts down the Vox-TV-14 emulator

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB_BIN="${ANDROID_HOME}/platform-tools/adb"

echo "========================================="
echo "  Stopping SmartTube VOX TV Emulator     "
echo "========================================="

echo "Sending graceful kill signal via ADB..."
"${ADB_BIN}" -s emulator-5554 emu kill 2>/dev/null || true

sleep 3

# Check if process is still alive
if pgrep -f "qemu-system|emulator" >/dev/null 2>&1; then
    echo "Terminating remaining emulator processes..."
    pkill -f "qemu-system|emulator" || true
fi

echo "Emulator stopped."
