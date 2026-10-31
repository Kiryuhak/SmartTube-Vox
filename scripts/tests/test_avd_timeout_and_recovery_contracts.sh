#!/usr/bin/env bash
# ==============================================================================
# Contract Test Suite: AVD Hang, Timeout, Liveness & Recovery Safeguards
# Tests all failure modes deterministically via Python harness mock scenarios
# Zero real memory consumption, zero long hangs.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
HARNESS="${REPO_ROOT}/scripts/verify_speech_vot_test_set.py"

echo "=================================================================="
echo " Running AVD Timeout & Recovery Contract Tests"
echo " Harness: ${HARNESS}"
echo "=================================================================="

TOTAL_PASS=0
TOTAL_FAIL=0

run_contract_test() {
    local name="$1"
    local mock_scenario="$2"
    local expected_exit="$3" # 0 or 1
    local expected_status_substr="$4"

    echo -n "Test [${name}]: "
    local out
    local code=0
    out=$(python3 "${HARNESS}" --mock-scenario="${mock_scenario}" --per-video-timeout=1 --global-timeout=3 2>&1) || code=$?

    if [[ ${code} -ne ${expected_exit} ]]; then
        echo "FAIL (expected exit code ${expected_exit}, got ${code})"
        echo "${out}"
        TOTAL_FAIL=$((TOTAL_FAIL + 1))
        return 1
    fi

    if [[ -n "${expected_status_substr}" ]] && [[ ! "${out}" =~ ${expected_status_substr} ]]; then
        echo "FAIL (output did not contain expected substring '${expected_status_substr}')"
        echo "${out}"
        TOTAL_FAIL=$((TOTAL_FAIL + 1))
        return 1
    fi

    echo "PASS (exit=${code}, matched '${expected_status_substr}')"
    TOTAL_PASS=$((TOTAL_PASS + 1))
    return 0
}

# 1. Normal Success
run_contract_test "Normal Success" "normal_success" 0 "OVERALL_TELEMETRY: PASS"

# 2. ADB Command Hang (simulated timeout)
run_contract_test "ADB Command Hang" "adb_hang" 1 "INTERRUPTED_AVD_NOT_ALIVE"

# 3. ADB Offline / Unauthorized
run_contract_test "ADB Offline" "adb_offline" 1 "INTERRUPTED_AVD_NOT_ALIVE"

# 4. QEMU Vanished / User Manual Stop
run_contract_test "QEMU Process Vanished" "qemu_vanished" 1 "INTERRUPTED_AVD_NOT_ALIVE"

# 5. Stale Logs (Reject old run logs)
run_contract_test "Stale Logs Rejection" "stale_logs" 1 "TIMEOUT_VOT_TELEMETRY"

# 6. VOT Backend Response Timeout
run_contract_test "VOT Telemetry Timeout" "vot_timeout" 1 "TIMEOUT_VOT_TELEMETRY"

# 7. Launcher Audio Missing Diagnostics Warning Check
echo -n "Test [Launcher Host Audio Missing Warning]: "
LAUNCHER="${REPO_ROOT}/scripts/linux/vox-emulator-start.sh"
TMP_DIR="$(mktemp -d -t test_launcher_no_audio_XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

MOCK_EMU="${TMP_DIR}/mock_emulator"
MOCK_ADB="${TMP_DIR}/mock_adb"
cat << 'EOF' > "${MOCK_EMU}"
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${MOCK_EMU}"

cat << 'EOF' > "${MOCK_ADB}"
#!/usr/bin/env bash
case "$*" in
    *"devices"*) echo "List of devices attached";;
    *"wait-for-device"*) exit 0;;
    *"sys.boot_completed"*) echo "1";;
    *) exit 0;;
esac
EOF
chmod +x "${MOCK_ADB}"

# Run launcher with --no-audio to test explicit override diagnostic
launcher_out=$(EMULATOR_BIN="${MOCK_EMU}" ADB_BIN="${MOCK_ADB}" BOOT_TIMEOUT_SEC=2 bash "${LAUNCHER}" --no-audio 2>&1 || true)

if [[ "${launcher_out}" =~ "Audio explicitly disabled" ]]; then
    echo "PASS (correct diagnostic emitted)"
    TOTAL_PASS=$((TOTAL_PASS + 1))
else
    echo "FAIL"
    echo "${launcher_out}"
    TOTAL_FAIL=$((TOTAL_FAIL + 1))
fi

echo "=================================================================="
echo " Contract Tests Summary: ${TOTAL_PASS} PASSED, ${TOTAL_FAIL} FAILED"
echo "=================================================================="

if [[ ${TOTAL_FAIL} -ne 0 ]]; then
    exit 1
fi
exit 0
