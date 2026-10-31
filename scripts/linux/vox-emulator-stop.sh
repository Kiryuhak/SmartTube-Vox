#!/usr/bin/env bash
# ==============================================================================
# SmartTube VOX — Linux Emulator Safe Stop Script
# Enforces: Graceful shutdown via adb emu kill, stale lock cleanup
# ==============================================================================
set -euo pipefail

AVD_NAME="VOX_TV_API34"
ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_SDK/platform-tools:$ANDROID_SDK/emulator:$PATH"

echo "[vox-emu] Initiating graceful shutdown..."
if adb devices | grep -q "emulator"; then
    adb -s emulator-5554 emu kill 2>/dev/null || true
    echo "[vox-emu] Sent kill command via adb -s emulator-5554."
fi

# Wait up to 15 seconds for QEMU process to exit
for i in {1..15}; do
    if ! pgrep -f "qemu-system" > /dev/null; then
        echo "[vox-emu] Emulator process cleanly terminated."
        break
    fi
    sleep 1
done

if pgrep -f "qemu-system" > /dev/null; then
    echo "[vox-emu] Process still running. Sending SIGTERM..."
    pkill -TERM -f "qemu-system" || true
    sleep 2
    if pgrep -f "qemu-system" > /dev/null; then
        echo "[vox-emu] Process still running. Sending SIGKILL..."
        pkill -9 -f "qemu-system" || true
    fi
fi

echo "[vox-emu] Cleaning up stale locks in AVD directory..."
AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
if [ -d "$AVD_DIR" ]; then
    rm -f "$AVD_DIR"/*.lock
fi

echo "[vox-emu] Emulator stopped successfully."
