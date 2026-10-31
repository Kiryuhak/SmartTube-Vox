#!/usr/bin/env bash
# ==============================================================================
# Contract Test Suite: vox-emulator-start.sh Audio Parameter & Shell Contract
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TARGET_SCRIPT="${1:-${REPO_ROOT}/scripts/linux/vox-emulator-start.sh}"

echo "=== Running Launcher Audio Contract Tests ==="
echo "Target script: ${TARGET_SCRIPT}"

if [[ ! -f "${TARGET_SCRIPT}" ]]; then
    echo "ERROR: Target script not found: ${TARGET_SCRIPT}" >&2
    exit 1
fi

TMP_DIR="$(mktemp -d -t vox_emu_contract_XXXXXX)"
trap 'rm -rf "${TMP_DIR}"' EXIT

MOCK_ARGS_FILE="${TMP_DIR}/emulator_args.txt"
MOCK_EMU="${TMP_DIR}/mock_emulator"
MOCK_ADB="${TMP_DIR}/mock_adb"
MOCK_PREFLIGHT="${TMP_DIR}/vox-memory-preflight.sh"

cat << 'EOF' > "${MOCK_EMU}"
#!/usr/bin/env bash
echo "$@" > "${MOCK_ARGS_FILE}"
# Keep process alive briefly so setsid kill -0 check succeeds if called
sleep 0.5 &
echo $!
exit 0
EOF
chmod +x "${MOCK_EMU}"

cat << 'EOF' > "${MOCK_ADB}"
#!/usr/bin/env bash
case "$*" in
    *"devices"*)
        echo "List of devices attached"
        ;;
    *"wait-for-device"*)
        exit 0
        ;;
    *"sys.boot_completed"*)
        echo "1"
        ;;
    *)
        exit 0
        ;;
esac
EOF
chmod +x "${MOCK_ADB}"

cat << 'EOF' > "${MOCK_PREFLIGHT}"
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${MOCK_PREFLIGHT}"

run_launcher_test() {
    local test_name="$1"
    local shell_bin="$2"
    local env_vars="$3"
    local script_args="$4"
    local expected_audio_mode="$5" # "ENABLED" (no -no-audio) or "DISABLED" (has -no-audio)

    rm -f "${MOCK_ARGS_FILE}"
    echo -n "Test [${test_name}] (${shell_bin}): "

    local exit_code=0
    if [[ "${shell_bin}" == "bash" ]]; then
        eval "EMULATOR_BIN=\"${MOCK_EMU}\" ADB_BIN=\"${MOCK_ADB}\" BOOT_TIMEOUT_SEC=5 MOCK_ARGS_FILE=\"${MOCK_ARGS_FILE}\" ${env_vars} bash \"${TARGET_SCRIPT}\" ${script_args}" > "${TMP_DIR}/stdout.log" 2>&1 || exit_code=$?
    elif [[ "${shell_bin}" == "fish" ]]; then
        eval "fish -c 'set -x EMULATOR_BIN \"${MOCK_EMU}\"; set -x ADB_BIN \"${MOCK_ADB}\"; set -x BOOT_TIMEOUT_SEC 5; set -x MOCK_ARGS_FILE \"${MOCK_ARGS_FILE}\"; ${env_vars} bash \"${TARGET_SCRIPT}\" ${script_args}'" > "${TMP_DIR}/stdout.log" 2>&1 || exit_code=$?
    fi

    if [[ ${exit_code} -ne 0 ]]; then
        echo "FAIL (exit code ${exit_code})"
        cat "${TMP_DIR}/stdout.log"
        return 1
    fi

    if [[ ! -f "${MOCK_ARGS_FILE}" ]]; then
        echo "FAIL (mock emulator was never called)"
        cat "${TMP_DIR}/stdout.log"
        return 1
    fi

    local recorded_args
    recorded_args="$(cat "${MOCK_ARGS_FILE}")"

    if [[ "${expected_audio_mode}" == "ENABLED" ]]; then
        if [[ "${recorded_args}" =~ -no-audio ]]; then
            echo "FAIL (expected audio ENABLED, but found -no-audio in args: ${recorded_args})"
            return 1
        fi
        echo "PASS (audio enabled, args: ${recorded_args})"
    elif [[ "${expected_audio_mode}" == "DISABLED" ]]; then
        if [[ ! "${recorded_args}" =~ -no-audio ]]; then
            echo "FAIL (expected audio DISABLED, but -no-audio missing in args: ${recorded_args})"
            return 1
        fi
        echo "PASS (audio disabled with -no-audio, args: ${recorded_args})"
    else
        echo "FAIL (unknown expected mode ${expected_audio_mode})"
        return 1
    fi
    return 0
}

TOTAL_PASS=0
TOTAL_FAIL=0

check() {
    if "$@"; then
        TOTAL_PASS=$((TOTAL_PASS + 1))
    else
        TOTAL_FAIL=$((TOTAL_FAIL + 1))
    fi
}

echo "--- 1. Bash Suite ---"
check run_launcher_test "Default (unset VOX_EMU_AUDIO)" "bash" "" "" "ENABLED"
check run_launcher_test "Explicit VOX_EMU_AUDIO=1" "bash" "VOX_EMU_AUDIO=1" "" "ENABLED"
check run_launcher_test "Explicit VOX_EMU_AUDIO=0" "bash" "VOX_EMU_AUDIO=0" "" "DISABLED"
check run_launcher_test "CLI flag --no-audio" "bash" "" "--no-audio" "DISABLED"
check run_launcher_test "CLI flag --without-audio" "bash" "" "--without-audio" "DISABLED"
check run_launcher_test "CLI flag --with-audio" "bash" "VOX_EMU_AUDIO=0" "--with-audio" "ENABLED"

echo "--- 2. Fish Shell Compatibility Suite ---"
if command -v fish >/dev/null 2>&1; then
    check run_launcher_test "Fish Default (audio enabled)" "fish" "" "" "ENABLED"
    check run_launcher_test "Fish VOX_EMU_AUDIO=0 (audio disabled)" "fish" "set -x VOX_EMU_AUDIO 0;" "" "DISABLED"
    check run_launcher_test "Fish VOX_EMU_AUDIO=1 (audio enabled)" "fish" "set -x VOX_EMU_AUDIO 1;" "" "ENABLED"
else
    echo "Notice: fish not installed, skipping fish suite."
fi

echo "========================================="
echo "Contract Tests Summary: ${TOTAL_PASS} PASSED, ${TOTAL_FAIL} FAILED"
echo "========================================="

if [[ ${TOTAL_FAIL} -ne 0 ]]; then
    exit 1
fi
exit 0
