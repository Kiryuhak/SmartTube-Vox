#!/usr/bin/env bash
# scripts/linux/vox-install-debug.sh
# Builds assembleStvotDebug and installs strictly onto a local Android emulator.
# Physical TVs or network ADB devices are strictly forbidden by default.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# 1. Detect Android SDK
if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME:-}" ]]; then
    if [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    elif [[ -d "/opt/android-sdk" ]]; then
        export ANDROID_HOME="/opt/android-sdk"
    else
        echo "Error: Android SDK not found! Please set ANDROID_HOME." >&2
        exit 1
    fi
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
if [[ ! -x "${ADB_BIN}" ]]; then
    echo "Error: adb binary not found at ${ADB_BIN}" >&2
    exit 1
fi

# 2. Detect Java 17
if [[ -z "${JAVA_HOME:-}" || ! -d "${JAVA_HOME:-}" ]]; then
    if [[ -d "$HOME/.local/share/jvm/java-17-openjdk" ]]; then
        export JAVA_HOME="$HOME/.local/share/jvm/java-17-openjdk"
    elif [[ -d "$HOME/.jdks/jdk-17" ]]; then
        export JAVA_HOME="$HOME/.jdks/jdk-17"
    elif [[ -d "/usr/lib/jvm/java-17-openjdk" ]]; then
        export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
    fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java binary not found! Please check JAVA_HOME." >&2
    exit 1
fi

cd "${PROJECT_ROOT}"

echo "========================================="
echo "  SmartTube VOX Debug Build & Install   "
echo "========================================="

# 3. Detect ADB devices and enforce safety rules
DEVICE_TARGET=""
RAW_DEVICES=$("${ADB_BIN}" devices | grep -v "List of devices attached" | grep -v "^$" || true)

EMULATOR_DEVICES=()
NON_EMULATOR_DEVICES=()

while IFS=$'\t ' read -r SERIAL STATUS REST; do
    [[ -z "${SERIAL}" ]] && continue
    if [[ "${SERIAL}" =~ ^emulator- ]]; then
        if [[ "${STATUS}" == "device" ]]; then
            EMULATOR_DEVICES+=("${SERIAL}")
        fi
    else
        NON_EMULATOR_DEVICES+=("${SERIAL}")
    fi
done <<< "${RAW_DEVICES}"

if [[ ${#NON_EMULATOR_DEVICES[@]} -gt 0 ]]; then
    echo "Notice: Non-emulator device(s) detected: ${NON_EMULATOR_DEVICES[*]}."
    echo "Safety policy: Installing to physical TVs or non-emulator targets is BLOCKED."
fi

if [[ ${#EMULATOR_DEVICES[@]} -eq 0 ]]; then
    echo "Error: No active Android emulator (emulator-*) detected." >&2
    echo "Refusing to install to physical TV or non-emulator target." >&2
    echo "Start the local emulator first using: ${SCRIPT_DIR}/vox-emulator-start.sh" >&2
    exit 1
elif [[ ${#EMULATOR_DEVICES[@]} -eq 1 ]]; then
    DEVICE_TARGET="${EMULATOR_DEVICES[0]}"
else
    # Multiple emulators active: choose the first or check if specified in argument
    if [[ -n "${1:-}" && "${1}" =~ ^emulator- ]]; then
        DEVICE_TARGET="${1}"
    else
        DEVICE_TARGET="${EMULATOR_DEVICES[0]}"
        echo "Multiple emulators detected (${EMULATOR_DEVICES[*]}). Selected: ${DEVICE_TARGET}"
    fi
fi

echo "Target device: ${DEVICE_TARGET} (VERIFIED LOCAL EMULATOR)"

# 4. Build APK
echo "Building assembleStvotDebug..."
./gradlew assembleStvotDebug

# 5. Locate APK
APK_DIR="${PROJECT_ROOT}/smarttubetv/build/outputs/apk/stvot/debug"
APK=$(find "${APK_DIR}" -type f -name "*x86.apk" 2>/dev/null | sort -r | head -n 1 || true)
if [[ -z "${APK}" ]]; then
    APK=$(find "${APK_DIR}" -type f -name "*universal*.apk" 2>/dev/null | sort -r | head -n 1 || true)
fi
if [[ -z "${APK}" ]]; then
    APK=$(find "${APK_DIR}" -type f -name "*.apk" 2>/dev/null | sort -r | head -n 1 || true)
fi

if [[ -z "${APK}" || ! -f "${APK}" ]]; then
    echo "Error: Could not find assembled stvotDebug APK in ${APK_DIR}!" >&2
    exit 1
fi

echo "Found APK: ${APK} ($(du -h "${APK}" | cut -f1))"

# 6. Install onto verified emulator
echo "Installing APK onto ${DEVICE_TARGET}..."
"${ADB_BIN}" -s "${DEVICE_TARGET}" install -r "${APK}"

# 7. Start application
echo "Launching SmartTube VOX (SplashActivity)..."
"${ADB_BIN}" -s "${DEVICE_TARGET}" shell "am start -n io.github.kiryuhak.smarttubevot.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"

echo "SmartTube VOX successfully installed and launched on ${DEVICE_TARGET}!"
