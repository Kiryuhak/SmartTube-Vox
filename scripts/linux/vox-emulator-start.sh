#!/usr/bin/env bash
# ==============================================================================
# SmartTube VOX — Linux Emulator Canonical Start Script
# Enforces:
#  - Audio ENABLED by default (host PulseAudio / PipeWire server integration)
#  - VOX_EMU_AUDIO=0 or --no-audio override for explicit headless/audio-disabled runs
#  - Memory preflight guard before launching emulator
#  - KVM virtualization readiness
#  - Host GPU direct rendering (-gpu host)
# ==============================================================================
set -euo pipefail

AVD_NAME="${AVD_NAME:-VOX_TV_API34}"
ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR_BIN="${EMULATOR_BIN:-$ANDROID_SDK/emulator/emulator}"
ADB_BIN="${ADB_BIN:-adb}"
export PATH="$ANDROID_SDK/platform-tools:$ANDROID_SDK/emulator:$PATH"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-120}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve memory preflight guard script
PREFLIGHT_SCRIPT="${SCRIPT_DIR}/vox-memory-preflight.sh"
if [ ! -x "$PREFLIGHT_SCRIPT" ]; then
    if [ -x "${HOME}/.smarttube-vox/avd-config/vox-memory-preflight.sh" ]; then
        PREFLIGHT_SCRIPT="${HOME}/.smarttube-vox/avd-config/vox-memory-preflight.sh"
    elif [ -x "${SCRIPT_DIR}/../../SmartTube-VOX-Private/scripts/linux/vox-memory-preflight.sh" ]; then
        PREFLIGHT_SCRIPT="${SCRIPT_DIR}/../../SmartTube-VOX-Private/scripts/linux/vox-memory-preflight.sh"
    fi
fi

echo "[vox-emu] Executing memory preflight guard..."
if [ -x "$PREFLIGHT_SCRIPT" ]; then
    if ! "$PREFLIGHT_SCRIPT" --phase=emulator --auto-stop-gradle; then
        echo "[vox-emu] PREFLIGHT FAILED: Insufficient memory or active conflicts. Aborting start." >&2
        exit 1
    fi
else
    echo "[vox-emu] Note: Preflight script not found, proceeding with caution."
fi

echo "[vox-emu] Checking KVM virtualization..."
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "[vox-emu] WARNING: /dev/kvm is not accessible. Hardware acceleration may fail."
fi

echo "[vox-emu] Checking if an emulator is already running..."
if "$ADB_BIN" devices | grep -E "emulator-[0-9]+" | grep -q "device"; then
    echo "[vox-emu] Emulator is already running and online."
    exit 0
fi

echo "[vox-emu] Cleaning up stale locks in AVD directory..."
AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
if [ -d "$AVD_DIR" ]; then
    rm -f "$AVD_DIR"/*.lock
fi

# Audio server check function
check_host_audio() {
    if command -v pactl >/dev/null 2>&1 && pactl info >/dev/null 2>&1; then
        local server_name
        server_name=$(pactl info 2>/dev/null | grep "Server Name:" | cut -d: -f2- | xargs || echo "PulseAudio/PipeWire")
        echo "[vox-emu] Host audio server active: ${server_name}"
        return 0
    elif [ -S "/run/user/$(id -u)/pulse/native" ] || [ -n "${PULSE_SERVER:-}" ]; then
        echo "[vox-emu] Host audio server active: PulseAudio native socket found."
        return 0
    elif [ -S "/run/user/$(id -u)/pipewire-0" ]; then
        echo "[vox-emu] Host audio server active: PipeWire socket found."
        return 0
    fi
    return 1
}

# Determine audio status: DEFAULT is enabled (1).
# Explicit disable via VOX_EMU_AUDIO=0
AUDIO_ENABLED=1
if [ "${VOX_EMU_AUDIO:-1}" = "0" ]; then
    AUDIO_ENABLED=0
fi

# Command-line arguments take precedence over environment variable
for arg in "${@:-}"; do
    case "$arg" in
        --no-audio|--without-audio) AUDIO_ENABLED=0 ;;
        --with-audio|--audio) AUDIO_ENABLED=1 ;;
    esac
done

EMU_ARGS=(-avd "$AVD_NAME" -gpu host -accel on)

if [ "$AUDIO_ENABLED" -eq 1 ]; then
    if check_host_audio; then
        echo "[vox-emu] Audio enabled by default (host audio server verified)."
    else
        echo "[vox-emu] WARNING: Host audio server (PipeWire/PulseAudio) not detected. Audio will be routed but may be inaudible on host." >&2
    fi
    # No -no-audio flag: standard Android emulator audio is active
else
    EMU_ARGS+=("-no-audio")
    echo "[vox-emu] Audio explicitly disabled (VOX_EMU_AUDIO=0 or --no-audio)."
fi

echo "[vox-emu] Launching emulator with -gpu host (Mesa direct rendering)..."
setsid "$EMULATOR_BIN" "${EMU_ARGS[@]}" </dev/null > /tmp/vox_emulator.log 2>&1 &

echo "[vox-emu] Waiting for adb device (timeout: ${BOOT_TIMEOUT_SEC}s)..."
"$ADB_BIN" -s emulator-5554 wait-for-device

echo "[vox-emu] Waiting for boot completion..."
START_TIME=$(date +%s)
while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    if [ "$ELAPSED" -ge "$BOOT_TIMEOUT_SEC" ]; then
        echo "[vox-emu] ERROR: Boot timeout reached (${BOOT_TIMEOUT_SEC}s)."
        exit 1
    fi

    BOOT_VAL=$("$ADB_BIN" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null || echo "0")
    if [ "${BOOT_VAL//$'\r'/}" = "1" ]; then
        echo "[vox-emu] Emulator successfully booted and online in ${ELAPSED}s!"
        HOST_NOW_MS=$(($(date +%s) * 1000))
        "$ADB_BIN" -s emulator-5554 shell cmd alarm set-time "$HOST_NOW_MS" 2>/dev/null || true
        echo "[vox-emu] Synced emulator clock to host time: $("$ADB_BIN" -s emulator-5554 shell date -u 2>/dev/null || echo 'unknown')"
        break
    fi
    sleep 2
done
