#!/usr/bin/env bash
# ==============================================================================
# SmartTube VOX — Linux Emulator Canonical Start Script (Prompt #115 Hotfixed)
# Enforces:
#  - 180s maximum boot timeout with full diagnostics and auto-shutdown on failure
#  - Mutual exclusion: prevents launching a second emulator instance
#  - Cleans stale lock files safely when no emulator process is active
#  - Host GPU direct rendering (-gpu host) matching AVD config & snapshot cache
#  - Safe headless support (--no-window) without breaking snapshot compatibility
#  - Memory preflight guard before launching emulator
#  - KVM virtualization readiness
# ==============================================================================
set -euo pipefail

AVD_NAME="${AVD_NAME:-VOX_TV_API34}"
ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR_BIN="${EMULATOR_BIN:-$ANDROID_SDK/emulator/emulator}"
ADB_BIN="${ADB_BIN:-adb}"
export PATH="$ANDROID_SDK/platform-tools:$ANDROID_SDK/emulator:$PATH"
BOOT_TIMEOUT_SEC="${BOOT_TIMEOUT_SEC:-180}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMU_LOG="/tmp/vox_emulator.log"

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

echo "[vox-emu] Checking KVM virtualization readiness..."
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "[vox-emu] WARNING: /dev/kvm is not accessible. Hardware acceleration may fail." >&2
else
    echo "[vox-emu] KVM device /dev/kvm is accessible (read/write verified)."
fi

echo "[vox-emu] Checking for already running emulator instance..."
RUNNING_PIDS=$(pgrep -f "qemu-system.*${AVD_NAME}|emulator.*${AVD_NAME}" || true)
if [ -n "$RUNNING_PIDS" ]; then
    echo "[vox-emu] Found existing process for ${AVD_NAME} (PID: ${RUNNING_PIDS})."
    if "$ADB_BIN" devices | grep -E "emulator-[0-9]+" | grep -q "device"; then
        BOOT_VAL=$("$ADB_BIN" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null || echo "0")
        if [ "${BOOT_VAL//$'\r'/}" = "1" ]; then
            echo "[vox-emu] Emulator is already fully booted (sys.boot_completed=1) and responsive. Reusing existing instance."
            exit 0
        fi
    fi
    echo "[vox-emu] ERROR: Second instance launch prevented. Existing emulator process is unready or stuck." >&2
    echo "[vox-emu] Please stop the existing instance using ./scripts/linux/vox-emulator-stop.sh first." >&2
    exit 1
fi

echo "[vox-emu] Cleaning up stale lock files in AVD directory..."
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

# Determine flags:
AUDIO_ENABLED=1
if [ "${VOX_EMU_AUDIO:-1}" = "0" ]; then
    AUDIO_ENABLED=0
fi

NO_WINDOW=0
for arg in "${@:-}"; do
    case "$arg" in
        --no-window|--headless) NO_WINDOW=1 ;;
        --no-audio|--without-audio) AUDIO_ENABLED=0 ;;
        --with-audio|--audio) AUDIO_ENABLED=1 ;;
    esac
done

# Canonical GPU configuration: always host (Mesa direct hardware rendering)
EMU_ARGS=(-avd "$AVD_NAME" -gpu host -accel on)

if [ "$NO_WINDOW" -eq 1 ]; then
    echo "[vox-emu] Running in headless mode (--no-window -no-boot-anim)."
    EMU_ARGS+=("-no-window" "-no-boot-anim")
fi

if [ "$AUDIO_ENABLED" -eq 1 ]; then
    if check_host_audio; then
        echo "[vox-emu] Audio enabled by default (host audio server verified)."
    else
        echo "[vox-emu] WARNING: Host audio server not detected. Audio routed but may be inaudible on host." >&2
    fi
else
    EMU_ARGS+=("-no-audio")
    echo "[vox-emu] Audio explicitly disabled (VOX_EMU_AUDIO=0 or --no-audio)."
fi

echo "[vox-emu] Launching emulator (args: ${EMU_ARGS[*]})..."
: > "$EMU_LOG"
setsid "$EMULATOR_BIN" "${EMU_ARGS[@]}" </dev/null > "$EMU_LOG" 2>&1 &
EMU_PID=$!
echo "[vox-emu] Emulator process launched in background (PID: $EMU_PID, log: $EMU_LOG)."

echo "[vox-emu] Waiting for boot completion (timeout: ${BOOT_TIMEOUT_SEC}s)..."
START_TIME=$(date +%s)
while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))

    if [ "$ELAPSED" -ge "$BOOT_TIMEOUT_SEC" ]; then
        echo "========================================================================" >&2
        echo "[vox-emu] ERROR: Boot timeout reached (${BOOT_TIMEOUT_SEC}s)!" >&2
        echo "[vox-emu] Diagnostic information:" >&2
        echo "------------------------------------------------------------------------" >&2
        echo "Process state:" >&2
        ps aux | grep -E "qemu-system|emulator" | grep -v grep || echo "No emulator processes running." >&2
        echo "ADB device listing:" >&2
        "$ADB_BIN" devices -l || true
        echo "Last 40 lines of ${EMU_LOG}:" >&2
        echo "------------------------------------------------------------------------" >&2
        tail -n 40 "$EMU_LOG" || true
        echo "========================================================================" >&2
        echo "[vox-emu] Gracefully shutting down unbooted emulator instance..." >&2
        "${SCRIPT_DIR}/vox-emulator-stop.sh" || true
        exit 1
    fi

    # Check if emulator process died prematurely
    if ! kill -0 "$EMU_PID" 2>/dev/null && ! pgrep -f "qemu-system.*${AVD_NAME}" >/dev/null; then
        echo "[vox-emu] ERROR: Emulator process exited prematurely after ${ELAPSED}s!" >&2
        tail -n 40 "$EMU_LOG" >&2 || true
        exit 1
    fi

    BOOT_VAL=$("$ADB_BIN" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null || echo "0")
    if [ "${BOOT_VAL//$'\r'/}" = "1" ]; then
        echo "[vox-emu] SUCCESS: Emulator successfully booted and online in ${ELAPSED}s (sys.boot_completed=1)!"
        HOST_NOW_MS=$(($(date +%s) * 1000))
        "$ADB_BIN" -s emulator-5554 shell cmd alarm set-time "$HOST_NOW_MS" 2>/dev/null || true
        echo "[vox-emu] Synced emulator clock to host time: $("$ADB_BIN" -s emulator-5554 shell date -u 2>/dev/null || echo 'unknown')"
        break
    fi

    # Print periodic heartbeat every 15s
    if [ $((ELAPSED % 15)) -eq 0 ] && [ "$ELAPSED" -gt 0 ]; then
        echo "[vox-emu] Still waiting for boot... (${ELAPSED}s / ${BOOT_TIMEOUT_SEC}s elapsed)"
    fi

    sleep 2
done
