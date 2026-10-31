#!/usr/bin/env bash
# ==============================================================================
# SmartTube VOX — Linux Host Memory Preflight Guard
# Enforces safe memory thresholds and strict mutual exclusion between
# Gradle daemon and Android QEMU emulator on memory-constrained hosts (~15 GiB RAM).
#
# Exit codes:
#   0 - PASS: Memory thresholds met, no conflicting processes. Safe to proceed.
#   1 - STOP: Memory guard triggered (insufficient RAM, high PSI, or active conflict).
# ==============================================================================
set -euo pipefail

# Configurable defaults (overridable via environment variables)
DEFAULT_MIN_AVAIL_MIB="${VOX_MIN_AVAIL_MIB:-3072}"
DEFAULT_MAX_PSI_SOME10="${VOX_MAX_PSI_SOME10:-10.0}"
DEFAULT_MAX_SWAP_PCT="${VOX_MAX_SWAP_PCT:-85}"
SMARTTUBE_DIR="${VOX_PROJECT_DIR:-/home/kiryuha/Developer/SmartTube}"

PHASE="status"
MIN_AVAIL_MIB="$DEFAULT_MIN_AVAIL_MIB"
MAX_PSI_SOME10="$DEFAULT_MAX_PSI_SOME10"
MAX_SWAP_PCT="$DEFAULT_MAX_SWAP_PCT"
AUTO_STOP_GRADLE="${VOX_AUTO_STOP_GRADLE:-false}"
CMD_TO_RUN=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --phase=*)
            PHASE="${1#*=}"
            shift
            ;;
        --phase)
            PHASE="$2"
            shift 2
            ;;
        --min-avail=*)
            MIN_AVAIL_MIB="${1#*=}"
            shift
            ;;
        --max-psi=*)
            MAX_PSI_SOME10="${1#*=}"
            shift
            ;;
        --max-swap-pct=*)
            MAX_SWAP_PCT="${1#*=}"
            shift
            ;;
        --auto-stop-gradle)
            AUTO_STOP_GRADLE=true
            shift
            ;;
        --run)
            shift
            if [ "${1:-}" = "--" ]; then
                shift
            fi
            CMD_TO_RUN=("$@")
            break
            ;;
        --help|-h)
            echo "Usage: $0 [--phase=status|gradle|emulator] [--min-avail=MIB] [--max-psi=PSI] [--max-swap-pct=PCT] [--auto-stop-gradle] [--run -- <command>...]"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

echo "============================================================================="
echo "[vox-memory-preflight] System Memory Preflight Check"
echo "Phase: ${PHASE} | Target Min Available: ${MIN_AVAIL_MIB} MiB | Max PSI avg10: ${MAX_PSI_SOME10} | Max Swap: ${MAX_SWAP_PCT}%"
echo "-----------------------------------------------------------------------------"

# 1. Read /proc/meminfo
MEM_TOTAL_KB=$(grep -i '^MemTotal:' /proc/meminfo | awk '{print $2}')
MEM_AVAIL_KB=$(grep -i '^MemAvailable:' /proc/meminfo | awk '{print $2}')
SWAP_TOTAL_KB=$(grep -i '^SwapTotal:' /proc/meminfo | awk '{print $2}')
SWAP_FREE_KB=$(grep -i '^SwapFree:' /proc/meminfo | awk '{print $2}')

MEM_TOTAL_MIB=$(( MEM_TOTAL_KB / 1024 ))
MEM_AVAIL_MIB=$(( MEM_AVAIL_KB / 1024 ))
SWAP_TOTAL_MIB=$(( SWAP_TOTAL_KB / 1024 ))
SWAP_USED_MIB=$(( (SWAP_TOTAL_KB - SWAP_FREE_KB) / 1024 ))

# Synthetic mock override for MemAvailable if configured
if [ -n "${VOX_MOCK_MEM_AVAIL_MIB:-}" ]; then
    MEM_AVAIL_MIB="$VOX_MOCK_MEM_AVAIL_MIB"
fi

# 2. Read /proc/pressure/memory
PSI_SOME_AVG10="0.00"
if [ -r /proc/pressure/memory ]; then
    PSI_LINE=$(head -n 1 /proc/pressure/memory 2>/dev/null || true)
    if [[ "$PSI_LINE" =~ avg10=([0-9]+\.[0-9]+) ]]; then
        PSI_SOME_AVG10="${BASH_REMATCH[1]}"
    fi
fi
if [ -n "${VOX_MOCK_PSI_SOME10:-}" ]; then
    PSI_SOME_AVG10="$VOX_MOCK_PSI_SOME10"
fi

# 3. Check OS name
OS_PRETTY="Linux"
if [ -r /etc/os-release ]; then
    OS_PRETTY=$(grep -i '^PRETTY_NAME=' /etc/os-release | cut -d= -f2- | tr -d '"')
fi

# Print status summary
echo "Host OS:         ${OS_PRETTY}"
echo "MemTotal:        ${MEM_TOTAL_MIB} MiB"
echo "MemAvailable:    ${MEM_AVAIL_MIB} MiB"
SWAP_PCT=0
if [ "$SWAP_TOTAL_MIB" -gt 0 ]; then
    SWAP_PCT=$(( (SWAP_USED_MIB * 100) / SWAP_TOTAL_MIB ))
    if [ -n "${VOX_MOCK_SWAP_PCT:-}" ]; then
        SWAP_PCT="$VOX_MOCK_SWAP_PCT"
    fi
    echo "Swap:            ${SWAP_TOTAL_MIB} MiB total, ${SWAP_USED_MIB} MiB used (${SWAP_PCT}%)"
else
    echo "Swap:            None configured"
fi
echo "Memory PSI:      some avg10=${PSI_SOME_AVG10}"

# 4. Safe process discovery: inspect /proc/[pid]/comm and /proc/[pid]/cmdline
# This guarantees immunity against self-matching parent commands, subshells, or shell scripts.
find_qemu_pids() {
    if [ -n "${VOX_MOCK_QEMU_PIDS:-}" ]; then
        echo "$VOX_MOCK_QEMU_PIDS"
        return
    fi
    local pids=()
    for p in /proc/[0-9]*; do
        [ -d "$p" ] || continue
        local comm
        comm=$(cat "$p/comm" 2>/dev/null || true)
        if [[ "$comm" == qemu-system* ]]; then
            pids+=("${p##*/}")
        fi
    done
    echo "${pids[*]:-}"
}

find_gradle_pids() {
    if [ -n "${VOX_MOCK_GRADLE_PIDS:-}" ]; then
        echo "$VOX_MOCK_GRADLE_PIDS"
        return
    fi
    local pids=()
    for p in /proc/[0-9]*; do
        [ -d "$p" ] || continue
        local comm
        comm=$(cat "$p/comm" 2>/dev/null || true)
        if [ "$comm" = "java" ]; then
            local cmdline
            cmdline=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null || true)
            if [[ "$cmdline" =~ org\.gradle\.launcher\.daemon ]]; then
                pids+=("${p##*/}")
            fi
        fi
    done
    echo "${pids[*]:-}"
}

QEMU_PIDS=$(find_qemu_pids)
GRADLE_PIDS=$(find_gradle_pids)

if [ -n "$QEMU_PIDS" ]; then
    echo "Active QEMU:     PID(s) ${QEMU_PIDS}"
else
    echo "Active QEMU:     None"
fi

if [ -n "$GRADLE_PIDS" ]; then
    echo "Active Gradle:   PID(s) ${GRADLE_PIDS}"
else
    echo "Active Gradle:   None"
fi
echo "-----------------------------------------------------------------------------"

STOP_REASONS=()

# Guard 1: Minimum Available Memory
if [ "$MEM_AVAIL_MIB" -lt "$MIN_AVAIL_MIB" ]; then
    STOP_REASONS+=("MemAvailable (${MEM_AVAIL_MIB} MiB) is below required safety threshold (${MIN_AVAIL_MIB} MiB). Close memory-heavy browser tabs or applications.")
fi

# Guard 2: Memory PSI Pressure
PSI_OVER_LIMIT=$(awk -v psi="$PSI_SOME_AVG10" -v limit="$MAX_PSI_SOME10" 'BEGIN {print (psi > limit) ? 1 : 0}')
if [ "$PSI_OVER_LIMIT" -eq 1 ]; then
    STOP_REASONS+=("Memory pressure is high: PSI some avg10=${PSI_SOME_AVG10} exceeds threshold ${MAX_PSI_SOME10}. Wait for background tasks to settle.")
fi

# Guard 3: Swap Usage Threshold (protect against systemd-oomd 90% kill limit)
if [ "$SWAP_TOTAL_MIB" -gt 0 ] && [ "$SWAP_PCT" -ge "$MAX_SWAP_PCT" ]; then
    STOP_REASONS+=("Swap usage is critical: ${SWAP_PCT}% used (${SWAP_USED_MIB}/${SWAP_TOTAL_MIB} MiB). Approaching systemd-oomd swap limit (90%). Free memory before proceeding.")
fi

# Guard 4: Mutual Exclusion depending on phase
case "$PHASE" in
    gradle)
        if [ -n "$QEMU_PIDS" ]; then
            STOP_REASONS+=("CONFLICT: QEMU emulator is currently running (PID: ${QEMU_PIDS}). Run 'vox-emulator-stop.sh' before executing Gradle builds.")
        fi
        ;;
    emulator)
        if [ -n "$GRADLE_PIDS" ]; then
            if [ "$AUTO_STOP_GRADLE" = true ] && [ -x "${SMARTTUBE_DIR}/gradlew" ]; then
                echo "[vox-memory-preflight] Attempting automatic stop of Gradle daemons via ${SMARTTUBE_DIR}/gradlew --stop..."
                "${SMARTTUBE_DIR}/gradlew" --stop || true
                sleep 2
                GRADLE_PIDS=$(find_gradle_pids)
            fi
            if [ -n "$GRADLE_PIDS" ]; then
                STOP_REASONS+=("CONFLICT: Gradle Daemon is still running (PID: ${GRADLE_PIDS}). Run '${SMARTTUBE_DIR}/gradlew --stop' before launching emulator to prevent RAM exhaustion.")
            fi
        fi
        ;;
    status)
        # Informational check only, but report if both are active simultaneously
        if [ -n "$QEMU_PIDS" ] && [ -n "$GRADLE_PIDS" ]; then
            STOP_REASONS+=("WARNING: Both QEMU emulator and Gradle daemons are running concurrently! High risk of OOM.")
        fi
        ;;
    *)
        echo "WARNING: Unrecognized phase '${PHASE}'."
        ;;
esac

# Final decision
if [ ${#STOP_REASONS[@]} -gt 0 ]; then
    echo "RESULT: STOP — Memory preflight guard check failed:"
    for reason in "${STOP_REASONS[@]}"; do
        echo "  [!] ${reason}"
    done
    echo "============================================================================="
    exit 1
else
    echo "RESULT: PASS — Host memory guards satisfied for phase '${PHASE}'."
    echo "============================================================================="
    if [ ${#CMD_TO_RUN[@]} -gt 0 ]; then
        echo "[vox-memory-preflight] Executing wrapped command: ${CMD_TO_RUN[*]}"
        exec "${CMD_TO_RUN[@]}"
    fi
    exit 0
fi
