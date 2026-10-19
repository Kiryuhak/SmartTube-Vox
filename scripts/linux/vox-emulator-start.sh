#!/usr/bin/env bash
# scripts/linux/vox-emulator-start.sh
# Robust Android TV emulator launcher with health-check and bounded timeout

set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
EMULATOR_BIN="${ANDROID_HOME}/emulator/emulator"
ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
LOG_FILE="/tmp/vox_emulator.log"
TIMEOUT_SECS="${BOOT_TIMEOUT:-60}"
GPU_MODE="${GPU_MODE:-host}"

# Auto-detect default AVD
if [[ -z "${AVD_NAME:-}" ]]; then
    if "${EMULATOR_BIN}" -list-avds 2>/dev/null | grep -qx "VOX_TV_API34"; then
        AVD_NAME="VOX_TV_API34"
    elif "${EMULATOR_BIN}" -list-avds 2>/dev/null | grep -qx "DIAG_TV_API34"; then
        AVD_NAME="DIAG_TV_API34"
    else
        AVD_NAME="$("${EMULATOR_BIN}" -list-avds 2>/dev/null | head -n 1 || echo "VOX_TV_API34")"
    fi
fi

# Flag handling: support --gui / --headless
WINDOW_FLAG="-no-window"
for arg in "$@"; do
    case "$arg" in
        --gui|--window) WINDOW_FLAG="" ;;
        --headless|--no-window) WINDOW_FLAG="-no-window" ;;
    esac
done

echo "========================================="
echo "  Starting SmartTube VOX TV Emulator     "
echo "  AVD:      ${AVD_NAME}"
echo "  GPU Mode: ${GPU_MODE}"
echo "  Display:  ${WINDOW_FLAG:-gui}"
echo "========================================="

# 1. Prevent duplicate instances & check existing emulator health
EXISTING_QEMU=$(pgrep -f "qemu-system.*${AVD_NAME}" || true)
if [[ -n "${EXISTING_QEMU}" ]]; then
    echo "Found existing emulator process (PID: ${EXISTING_QEMU}). Checking health..."
    ADB_STATE=$("${ADB_BIN}" devices | grep "emulator-5554" | awk '{print $2}' || true)
    if [[ "${ADB_STATE}" == "device" ]]; then
        BOOT_DONE=$("${ADB_BIN}" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
        if [[ "${BOOT_DONE}" == "1" ]]; then
            echo "Emulator ${AVD_NAME} is already running, healthy and ready! (sys.boot_completed=1)"
            exit 0
        fi
    fi
    echo "Existing emulator is unhealthy or unresponsive (ADB state: ${ADB_STATE:-none}). Cleaning up before restart..."
    "${ADB_BIN}" -s emulator-5554 emu kill 2>/dev/null || true
    sleep 2
    pkill -f "qemu-system.*${AVD_NAME}|emulator.*${AVD_NAME}" 2>/dev/null || true
    sleep 1
fi

# Clean up any orphan QEMU processes to prevent multiple instances
ORPHANS=$(pgrep -f "qemu-system" || true)
if [[ -n "${ORPHANS}" ]]; then
    echo "Notice: Cleaning up unmanaged QEMU processes (${ORPHANS}) to avoid runaway memory..."
    pkill -f "qemu-system" 2>/dev/null || true
    sleep 1
fi

if [[ ! -x "${EMULATOR_BIN}" ]]; then
    echo "Error: Emulator binary not found at ${EMULATOR_BIN}" >&2
    exit 1
fi

echo "Launching emulator in background (logging to ${LOG_FILE})..."
CMD=("${EMULATOR_BIN}" -avd "${AVD_NAME}" -gpu "${GPU_MODE}" -no-snapshot -no-boot-anim)
if [[ -n "${WINDOW_FLAG}" ]]; then
    CMD+=("${WINDOW_FLAG}")
fi

setsid "${CMD[@]}" > "${LOG_FILE}" 2>&1 &
EMU_PID=$!
disown "${EMU_PID}" 2>/dev/null || true

echo "Supervisor started (PID: ${EMU_PID}). Waiting for boot completion (timeout: ${TIMEOUT_SECS}s)..."

BOOTED=0
START_TIME=$(date +%s)

while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$(( CURRENT_TIME - START_TIME ))

    if ! kill -0 "${EMU_PID}" 2>/dev/null; then
        echo "Error: Emulator process terminated unexpectedly! Check ${LOG_FILE} for details." >&2
        tail -n 30 "${LOG_FILE}" >&2 || true
        exit 1
    fi

    if [[ ${ELAPSED} -ge ${TIMEOUT_SECS} ]]; then
        echo "Error: Boot timeout (${TIMEOUT_SECS}s) reached!" >&2
        break
    fi

    ADB_STATE=$("${ADB_BIN}" devices | grep "emulator-5554" | awk '{print $2}' || true)
    if [[ "${ADB_STATE}" == "device" ]]; then
        RES=$("${ADB_BIN}" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n' || true)
        if [[ "${RES}" == "1" ]]; then
            BOOTED=1
            break
        fi
    fi

    sleep 2
done

if [[ "${BOOTED}" -eq 1 ]]; then
    echo "========================================="
    echo "Emulator ${AVD_NAME} booted successfully in ${ELAPSED}s!"
    echo "ADB: device | sys.boot_completed = 1"
    echo "========================================="
    exit 0
else
    echo "Boot failed! Terminating emulator..." >&2
    "${ADB_BIN}" -s emulator-5554 emu kill 2>/dev/null || true
    sleep 2
    kill -TERM "${EMU_PID}" 2>/dev/null || true
    pkill -f "qemu-system-x86_64.*${AVD_NAME}" 2>/dev/null || true
    echo "Diagnostic log excerpt (${LOG_FILE}):" >&2
    tail -n 25 "${LOG_FILE}" >&2 || true
    exit 1
fi
