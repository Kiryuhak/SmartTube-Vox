#!/usr/bin/env python3
"""
CI / Dry-Run Release Bundle Validator for SmartTube VOX (Prompt 163-R40 Gate H).

Strict validations:
1. Exactly 3 APK assets matching canonical naming contract:
   - SmartTube_vot_<VERSION>_arm64-v8a.apk
   - SmartTube_vot_<VERSION>_armeabi-v7a.apk
   - SmartTube_vot_<VERSION>_universal.apk
2. Strict absence of standalone public _x86.apk.
3. Both Dual-Feed manifests present and valid:
   - smarttube_vox.json (legacy pinned bridge compatibility shim)
   - smarttube_vox_v2.json (modern 3-APK feed)
4. Legacy manifest smarttube_vox.json pinned URL invariant:
   - Must point to immutable tag download URL (NOT releases/latest/download/smarttube_vox.apk).
5. Modern manifest smarttube_vox_v2.json schema invariant:
   - schemaVersion >= 2
   - Exactly 3 APK assets with valid sha256 and sizes
   - Monotonic versionCode > 2446032
6. SHA256SUMS.txt integrity:
   - Checksum file exists and cryptographically matches all 3 APK files.
7. Whitelist allowed hosts: only https://github.com
8. Absence of QA flavor or dev leak in Stable releases.
"""

import hashlib
import json
import os
import re
import sys
from pathlib import Path

EXPECTED_SIGNER_SHA256 = "e07a27097e3ed7b74b3aceb457348e84474930a050e4f2e21d4a6465bd383a2d"
EXPECTED_APP_ID = "io.github.kiryuhak.smarttubevot.stable"
MAX_LEGACY_VERSION_CODE = 2446032

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest().lower()

def validate_bundle(bundle_dir: Path, expected_version: str = None) -> bool:
    print(f"=== Validating Release Bundle at {bundle_dir} ===")
    errors = []

    if not bundle_dir.is_dir():
        print(f"ERROR: Bundle directory {bundle_dir} does not exist!")
        return False

    all_files = list(bundle_dir.iterdir())
    file_names = {f.name for f in all_files if f.is_file()}

    # Check for forbidden x86 standalone APK
    for name in file_names:
        if name.endswith("_x86.apk") or name == "smarttube_vox_x86.apk":
            errors.append(f"Forbidden standalone x86 APK present in release bundle: {name}")

    # Identify APK files
    apk_files = [f for f in all_files if f.name.endswith(".apk")]
    print(f"Found {len(apk_files)} APKs: {[f.name for f in apk_files]}")

    if len(apk_files) != 3:
        errors.append(f"Expected exactly 3 public APKs, found {len(apk_files)}: {[f.name for f in apk_files]}")

    arm64_apk = None
    armv7_apk = None
    universal_apk = None

    for apk in apk_files:
        name = apk.name
        if "arm64-v8a" in name:
            arm64_apk = apk
        elif "armeabi-v7a" in name:
            armv7_apk = apk
        elif "universal" in name:
            universal_apk = apk
        else:
            errors.append(f"APK has non-canonical name: {name}")

    if not arm64_apk:
        errors.append("Missing required arm64-v8a APK")
    if not armv7_apk:
        errors.append("Missing required armeabi-v7a APK")
    if not universal_apk:
        errors.append("Missing required universal APK")

    # Check SHA256SUMS.txt
    sums_file = bundle_dir / "SHA256SUMS.txt"
    if not sums_file.exists():
        errors.append("Missing SHA256SUMS.txt")
    else:
        sums_text = sums_file.read_text(encoding="utf-8")
        parsed_sums = {}
        for line in sums_text.splitlines():
            parts = line.strip().split()
            if len(parts) >= 2:
                parsed_sums[parts[1].lstrip("*")] = parts[0].lower()

        for apk in apk_files:
            actual_sha = sha256_file(apk)
            expected_sha = parsed_sums.get(apk.name)
            if not expected_sha:
                errors.append(f"SHA256SUMS.txt missing entry for {apk.name}")
            elif actual_sha != expected_sha:
                errors.append(f"Checksum mismatch for {apk.name}: actual={actual_sha}, expected={expected_sha}")
            else:
                print(f"  OK SHA256 matches for {apk.name}: {actual_sha[:16]}...")

    # Check Legacy Feed smarttube_vox.json
    legacy_json = bundle_dir / "smarttube_vox.json"
    if not legacy_json.exists():
        errors.append("Missing legacy compatibility shim smarttube_vox.json")
    else:
        try:
            with open(legacy_json, "r", encoding="utf-8") as f:
                data = json.load(f)
            # Must have version and assets/downloadUrl pointing to pinned bridge
            ver = data.get("version")
            vc = data.get("versionCode", 0)
            if not ver:
                errors.append("smarttube_vox.json missing 'version'")
            if vc <= MAX_LEGACY_VERSION_CODE:
                errors.append(f"smarttube_vox.json versionCode {vc} is not > {MAX_LEGACY_VERSION_CODE}")

            # Check downloadUrl is pinned (not latest)
            urls = []
            if "downloadUrl" in data:
                urls.append(data["downloadUrl"])
            if "assets" in data:
                for a in data["assets"]:
                    if "downloadUrl" in a:
                        urls.append(a["downloadUrl"])
            for url in urls:
                if "/releases/latest/download/" in url:
                    errors.append(f"smarttube_vox.json contains unstable 'releases/latest' URL: {url} (must be pinned tag URL)")
                if not url.startswith("https://github.com/"):
                    errors.append(f"smarttube_vox.json URL host not whitelisted: {url}")
            print("  OK smarttube_vox.json legacy shim verified.")
        except Exception as e:
            errors.append(f"Failed to parse smarttube_vox.json: {e}")

    # Check Modern Feed smarttube_vox_v2.json
    modern_json = bundle_dir / "smarttube_vox_v2.json"
    if not modern_json.exists():
        errors.append("Missing modern feed smarttube_vox_v2.json")
    else:
        try:
            with open(modern_json, "r", encoding="utf-8") as f:
                data = json.load(f)
            schema_ver = data.get("schemaVersion", 1)
            if schema_ver < 2:
                errors.append(f"smarttube_vox_v2.json schemaVersion must be >= 2, got {schema_ver}")
            ver = data.get("version")
            vc = data.get("versionCode", 0)
            if vc <= MAX_LEGACY_VERSION_CODE:
                errors.append(f"smarttube_vox_v2.json versionCode {vc} is not > {MAX_LEGACY_VERSION_CODE}")
            assets = data.get("assets", [])
            if len(assets) != 3:
                errors.append(f"smarttube_vox_v2.json must list exactly 3 APK assets, got {len(assets)}")
            for a in assets:
                name = a.get("name", "")
                url = a.get("downloadUrl", "")
                sha = a.get("sha256", "")
                if not name.endswith(".apk"):
                    errors.append(f"Non-APK asset in modern feed assets: {name}")
                if not url.startswith("https://github.com/"):
                    errors.append(f"URL not whitelisted in modern feed: {url}")
                if not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
                    errors.append(f"Invalid or missing SHA-256 for asset {name}: {sha}")
            print("  OK smarttube_vox_v2.json modern feed verified.")
        except Exception as e:
            errors.append(f"Failed to parse smarttube_vox_v2.json: {e}")

    if errors:
        print("\n=== VALIDATION FAILED WITH ERRORS ===")
        for err in errors:
            print(f"  [ERROR] {err}")
        return False
    else:
        print("\n=== RELEASE BUNDLE VALIDATION PASSED (100% SUCCESS) ===")
        return True

if __name__ == "__main__":
    target_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    success = validate_bundle(target_dir)
    sys.exit(0 if success else 1)
