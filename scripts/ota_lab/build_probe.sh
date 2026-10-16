#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIXTURES_DIR="$SCRIPT_DIR/fixtures"
BUILD_TOOLS="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/30.0.3"
PLATFORM_JAR="${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-34/android.jar"

mkdir -p "$FIXTURES_DIR"
cd "$PROJECT_ROOT"

echo "=================================================="
echo "OTA LAB: BUILDING & PREPARING TEST FIXTURES"
echo "=================================================="

# 1. Baseline 9.1 release APK
echo "[1/6] Verifying Baseline 9.1 APK..."
if [ ! -f "$FIXTURES_DIR/baseline_9.1.apk" ]; then
    if [ -f "smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.1_universal.apk" ]; then
        cp "smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.1_universal.apk" "$FIXTURES_DIR/baseline_9.1.apk"
    else
        echo "Building baseline 9.1 release APK..."
        ./gradlew :smarttubetv:assembleStvotRelease -PvotVersionName=32.56-vox.9.1 -PvotVersionCode=2446016 --console=plain
        cp "smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.1_universal.apk" "$FIXTURES_DIR/baseline_9.1.apk"
    fi
fi
echo "Baseline 9.1: $(ls -lh "$FIXTURES_DIR/baseline_9.1.apk" | awk '{print $5}')"

# 2. Probe 9.5 skip release APK
echo "[2/6] Verifying Probe 9.5 Skip APK..."
if [ ! -f "$FIXTURES_DIR/probe_skip_9.5.apk" ]; then
    echo "Building probe 9.5 release APK..."
    ./gradlew :smarttubetv:assembleStvotRelease -PvotVersionName=32.56-vox.9.5 -PvotVersionCode=2446050 --console=plain
    cp "smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.5_universal.apk" "$FIXTURES_DIR/probe_skip_9.5.apk"
fi
echo "Probe 9.5: $(ls -lh "$FIXTURES_DIR/probe_skip_9.5.apk" | awk '{print $5}')"

# 3. Downgrade 9.0 release APK
echo "[3/6] Verifying Downgrade 9.0 APK..."
if [ ! -f "$FIXTURES_DIR/downgrade_9.0.apk" ]; then
    echo "Building downgrade 9.0 release APK..."
    ./gradlew :smarttubetv:assembleStvotRelease -PvotVersionName=32.56-vox.9.0 -PvotVersionCode=2446000 --console=plain
    cp "smarttubetv/build/outputs/apk/stvot/release/SmartTube_vot_32.56-vox.9.0_universal.apk" "$FIXTURES_DIR/downgrade_9.0.apk"
fi
echo "Downgrade 9.0: $(ls -lh "$FIXTURES_DIR/downgrade_9.0.apk" | awk '{print $5}')"

# 4. Wrong Signature APK
echo "[4/6] Creating Wrong Signature APK..."
WRONG_KEY="/tmp/ota_wrong_key.jks"
if [ ! -f "$WRONG_KEY" ]; then
    keytool -genkey -v -keystore "$WRONG_KEY" -alias wrong -keyalg RSA -keysize 2048 -validity 1000 -storepass password -keypass password -dname "CN=Wrong, OU=OTA, O=Test, L=City, ST=State, C=US" > /dev/null 2>&1
fi
cp "$FIXTURES_DIR/probe_skip_9.5.apk" "$FIXTURES_DIR/wrong_signature.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$WRONG_KEY" --ks-pass pass:password "$FIXTURES_DIR/wrong_signature.apk" > /dev/null 2>&1
echo "Wrong Signature APK: $(ls -lh "$FIXTURES_DIR/wrong_signature.apk" | awk '{print $5}')"

# 5. Wrong Package APK
echo "[5/6] Creating Wrong Package APK..."
cat << 'EOF' > /tmp/TestManifestWrong.xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.fakeapp"
    android:versionCode="3000000"
    android:versionName="3.0">
    <application android:label="FakeApp" />
</manifest>
EOF
"$BUILD_TOOLS/aapt2" link -I "$PLATFORM_JAR" --manifest /tmp/TestManifestWrong.xml -o /tmp/fake_raw_wrong.apk > /dev/null 2>&1 || true
python3 - << 'EOF'
import zipfile, os

with zipfile.ZipFile('/tmp/fake_raw_wrong.apk', 'r') as zin:
    with zipfile.ZipFile('/tmp/fake_padded_wrong.apk', 'w') as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.writestr('classes.dex', b'\x00' * 100)
        zout.writestr('assets/padding.bin', b'A' * (6 * 1024 * 1024))
EOF
"$BUILD_TOOLS/apksigner" sign --ks "$WRONG_KEY" --ks-pass pass:password --out "$FIXTURES_DIR/wrong_package.apk" /tmp/fake_padded_wrong.apk > /dev/null 2>&1
echo "Wrong Package APK: $(ls -lh "$FIXTURES_DIR/wrong_package.apk" | awk '{print $5}')"

# 6. Corrupt APKs (truncated and invalid zip)
echo "[6/6] Generating Corrupted APK Fixtures..."
head -c 200000 "$FIXTURES_DIR/baseline_9.1.apk" > "$FIXTURES_DIR/corrupt_truncated.apk"
head -c 6000000 "$FIXTURES_DIR/baseline_9.1.apk" > "$FIXTURES_DIR/corrupt_invalid_zip.apk"
printf "INVALID_ZIP_SIGNATURE_CORRUPTED" >> "$FIXTURES_DIR/corrupt_invalid_zip.apk"

echo "=================================================="
echo "OTA LAB FIXTURES READY in $FIXTURES_DIR:"
ls -lh "$FIXTURES_DIR"
echo "=================================================="
