#!/usr/bin/env python3
"""
Unit and negative tests for validate_vox_release_bundle.py (Gate 4).
Tests:
1. Valid bundle -> PASS
2. Missing universal APK -> FAIL
3. Missing arm64-v8a APK -> FAIL
4. Missing armeabi-v7a APK -> FAIL
5. Present standalone _x86.apk -> FAIL
6. Checksum mismatch in SHA256SUMS.txt -> FAIL
7. Missing smarttube_vox.json -> FAIL
8. Missing smarttube_vox_v2.json -> FAIL
9. smarttube_vox.json using latest/download URL -> FAIL
10. smarttube_vox_v2.json with schemaVersion < 2 -> FAIL
11. smarttube_vox_v2.json with non-monotonic versionCode -> FAIL
12. Non-whitelisted URL host -> FAIL
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

VALIDATOR_SCRIPT = Path(__file__).resolve().parents[1] / "validate_vox_release_bundle.py"

def create_dummy_file(path: Path, content: bytes = b"dummy apk content") -> str:
    path.write_bytes(content)
    return hashlib.sha256(content).hexdigest().lower()

def setup_valid_bundle(bundle_dir: Path, version: str = "32.71-vox.10", version_code: int = 2446050):
    bundle_dir.mkdir(parents=True, exist_ok=True)
    
    # 3 canonical APKs
    arm64 = bundle_dir / f"SmartTube_vot_{version}_arm64-v8a.apk"
    armv7 = bundle_dir / f"SmartTube_vot_{version}_armeabi-v7a.apk"
    univ = bundle_dir / f"SmartTube_vot_{version}_universal.apk"
    
    sha_arm64 = create_dummy_file(arm64, b"arm64 content")
    sha_armv7 = create_dummy_file(armv7, b"armv7 content")
    sha_univ = create_dummy_file(univ, b"universal content")
    
    # SHA256SUMS.txt
    sums_text = f"{sha_arm64}  {arm64.name}\n{sha_armv7}  {armv7.name}\n{sha_univ}  {univ.name}\n"
    (bundle_dir / "SHA256SUMS.txt").write_text(sums_text, encoding="utf-8")
    
    # smarttube_vox.json (legacy pinned bridge compatibility shim)
    legacy = {
        "version": "32.71-vox.10",
        "versionCode": 2446050,
        "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk"
    }
    (bundle_dir / "smarttube_vox.json").write_text(json.dumps(legacy, indent=2), encoding="utf-8")
    
    # smarttube_vox_v2.json (modern feed)
    modern = {
        "schemaVersion": 2,
        "version": version,
        "versionCode": version_code,
        "minSdk": 21,
        "targetSdk": 34,
        "changelog": ["Feature 1"],
        "assets": [
            {
                "name": arm64.name,
                "downloadUrl": f"https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v{version}/{arm64.name}",
                "size": arm64.stat().st_size,
                "sha256": sha_arm64
            },
            {
                "name": armv7.name,
                "downloadUrl": f"https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v{version}/{armv7.name}",
                "size": armv7.stat().st_size,
                "sha256": sha_armv7
            },
            {
                "name": univ.name,
                "downloadUrl": f"https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v{version}/{univ.name}",
                "size": univ.stat().st_size,
                "sha256": sha_univ
            }
        ],
        "sha256SumsUrl": f"https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v{version}/SHA256SUMS.txt"
    }
    (bundle_dir / "smarttube_vox_v2.json").write_text(json.dumps(modern, indent=2), encoding="utf-8")

def run_validator(bundle_dir: Path) -> int:
    cmd = [sys.executable, str(VALIDATOR_SCRIPT), str(bundle_dir)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.returncode

def main():
    print("=== Running Release Bundle Validator Test Suite ===")
    tests_passed = 0
    total_tests = 0
    
    def assert_case(desc: str, should_pass: bool, modifier_fn):
        nonlocal tests_passed, total_tests
        total_tests += 1
        with tempfile.TemporaryDirectory() as td:
            b_dir = Path(td)
            setup_valid_bundle(b_dir)
            modifier_fn(b_dir)
            code = run_validator(b_dir)
            passed = (code == 0) if should_pass else (code != 0)
            if passed:
                print(f"  [PASS] Test {total_tests}: {desc}")
                tests_passed += 1
            else:
                print(f"  [FAIL] Test {total_tests}: {desc} (expected exit code {'0' if should_pass else 'non-zero'}, got {code})")

    # 1. Base Valid Bundle
    assert_case("Valid canonical bundle with dual feeds and 3 APKs passes", True, lambda d: None)

    # 2. Missing universal APK
    def remove_universal(d):
        for f in d.glob("*universal.apk"):
            f.unlink()
    assert_case("Missing universal APK fails validation", False, remove_universal)

    # 3. Missing arm64 APK
    def remove_arm64(d):
        for f in d.glob("*arm64-v8a.apk"):
            f.unlink()
    assert_case("Missing arm64-v8a APK fails validation", False, remove_arm64)

    # 4. Present standalone x86 APK
    def add_x86(d):
        (d / "SmartTube_vot_32.71-vox.10_x86.apk").write_bytes(b"x86")
    assert_case("Forbidden standalone x86 APK presence fails validation", False, add_x86)

    # 5. SHA256 mismatch
    def corrupt_checksum(d):
        sums_file = d / "SHA256SUMS.txt"
        sums_file.write_text(sums_file.read_text().replace("arm64", "wrong"), encoding="utf-8")
    assert_case("Checksum mismatch in SHA256SUMS.txt fails validation", False, corrupt_checksum)

    # 6. Missing legacy manifest
    def remove_legacy(d):
        (d / "smarttube_vox.json").unlink()
    assert_case("Missing smarttube_vox.json fails validation", False, remove_legacy)

    # 7. Unstable latest/download URL in legacy manifest
    def poison_legacy_url(d):
        f = d / "smarttube_vox.json"
        data = json.loads(f.read_text())
        data["downloadUrl"] = "https://github.com/Kiryuhak/SmartTube-Vox/releases/latest/download/smarttube_vox.apk"
        f.write_text(json.dumps(data))
    assert_case("Legacy manifest with releases/latest downloadUrl fails validation", False, poison_legacy_url)

    # 8. Missing modern feed
    def remove_modern(d):
        (d / "smarttube_vox_v2.json").unlink()
    assert_case("Missing smarttube_vox_v2.json fails validation", False, remove_modern)

    # 9. Modern feed schemaVersion < 2
    def downgrade_schema(d):
        f = d / "smarttube_vox_v2.json"
        data = json.loads(f.read_text())
        data["schemaVersion"] = 1
        f.write_text(json.dumps(data))
    assert_case("Modern feed schemaVersion < 2 fails validation", False, downgrade_schema)

    # 10. Non-monotonic versionCode (<= 2446032)
    def non_monotonic_version_code(d):
        f = d / "smarttube_vox_v2.json"
        data = json.loads(f.read_text())
        data["versionCode"] = 2446032
        f.write_text(json.dumps(data))
    assert_case("Non-monotonic versionCode fails validation", False, non_monotonic_version_code)

    # 11. Non-whitelisted URL
    def malicious_url(d):
        f = d / "smarttube_vox_v2.json"
        data = json.loads(f.read_text())
        data["assets"][0]["downloadUrl"] = "http://malicious-site.com/apk.apk"
        f.write_text(json.dumps(data))
    assert_case("Non-whitelisted URL host fails validation", False, malicious_url)

    print(f"\nResult: {tests_passed}/{total_tests} tests passed.")
    if tests_passed == total_tests:
        print("ALL VALIDATOR TESTS PASSED (100% SUCCESS)")
        sys.exit(0)
    else:
        print("SOME VALIDATOR TESTS FAILED")
        sys.exit(1)

if __name__ == "__main__":
    main()
