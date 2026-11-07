#!/usr/bin/env python3
"""Prepare public release notes and the dual-feed in-app update manifests."""

import hashlib
import json
import os
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(os.environ.get("RELEASE_OUTPUT_DIR", "."))
VERSION = os.environ["VERSION_NAME"]
CODE = int(os.environ["EXPECTED_CODE"])
REPO = os.environ["REPO"]

if not re.fullmatch(r"\d+\.\d+-(?:vox|vot)\.\d+", VERSION):
    raise ValueError(f"Invalid release version: {VERSION}")

content = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
pattern = rf"^## \[{re.escape(VERSION)}\][^\n]*\n(.*?)(?=^## \[|\Z)"
match = re.search(pattern, content, re.MULTILINE | re.DOTALL)
if not match or not match.group(1).strip():
    raise ValueError(f"Missing or empty CHANGELOG section for {VERSION}")

section = match.group(1).strip()
ru_items = [
    line.removeprefix("- ").strip()
    for line in section.splitlines()
    if line.startswith("- ") and line.removeprefix("- ").strip()
]
if not ru_items:
    raise ValueError(f"No user-facing CHANGELOG bullets for {VERSION}")

notes_path = ROOT / "docs" / f"SMARTTUBE_VOX_{VERSION}_RELEASE_NOTES.md"
notes = notes_path.read_text(encoding="utf-8").strip() if notes_path.is_file() else section
if not notes:
    raise ValueError(f"Empty release notes for {VERSION}")

english_path = ROOT / "docs" / f"SMARTTUBE_VOX_{VERSION}_CHANGELOG_EN.txt"
if english_path.is_file():
    en_items = [
        line.removeprefix("- ").strip()
        for line in english_path.read_text(encoding="utf-8").splitlines()
        if line.startswith("- ") and line.removeprefix("- ").strip()
    ]
    if not en_items:
        raise ValueError(f"Empty English update changelog for {VERSION}")
else:
    # Preserve accurate release-specific text if a translation is unavailable.
    en_items = ru_items
    print(f"::warning::No English update changelog for {VERSION}; using CHANGELOG bullets.")

# A version-specific notes file supplies the public title; keep the version fallback
# for older releases that only have a CHANGELOG section.
heading = re.search(r"^#\s+(.+?)\s*$", notes, re.MULTILINE)
title = heading.group(1).strip() if heading else f"SmartTube VOX {VERSION}"
OUTPUT.mkdir(parents=True, exist_ok=True)
(OUTPUT / "release_notes.md").write_text(notes + "\n", encoding="utf-8")

# 1. Resolve 3 Canonical APK files and calculate SHA-256 and sizes
canonical_apks = [
    f"SmartTube_vot_{VERSION}_arm64-v8a.apk",
    f"SmartTube_vot_{VERSION}_armeabi-v7a.apk",
    f"SmartTube_vot_{VERSION}_universal.apk",
]

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest().lower()

assets_meta = []
sha_lines = []
for apk_name in canonical_apks:
    apk_path = OUTPUT / apk_name
    if apk_path.is_file():
        size = apk_path.stat().st_size
        sha = sha256_file(apk_path)
    else:
        # Fallback for dry-run or when invoked before APK build
        size = 0
        sha = "0" * 64
    sha_lines.append(f"{sha}  {apk_name}")
    assets_meta.append({
        "name": apk_name,
        "downloadUrl": f"https://github.com/{REPO}/releases/download/v{VERSION}/{apk_name}",
        "size": size,
        "sha256": sha,
    })

# Write SHA256SUMS.txt
(OUTPUT / "SHA256SUMS.txt").write_text("\n".join(sha_lines) + "\n", encoding="utf-8")

# 2. Legacy compatibility feed: smarttube_vox.json (pinned immutable bridge URL)
# For older clients (VOX 8..9.2), always points to pinned bridge universal APK
pinned_bridge_tag = os.environ.get("PINNED_BRIDGE_TAG", "v32.71-vox.10")
pinned_bridge_apk = f"SmartTube_vot_32.71-vox.10_universal.apk"
pinned_bridge_url = f"https://github.com/{REPO}/releases/download/{pinned_bridge_tag}/{pinned_bridge_apk}"

legacy_manifest = {
    "version": "32.71-vox.10",
    "versionCode": 2446050,
    "downloadUrl": pinned_bridge_url,
    "package": {
        "downloadUrl": pinned_bridge_url,
        "downloadUrlList": [pinned_bridge_url]
    },
    "32.71-vox.10": {
        "versionCode": 2446050,
        "downloadUrl": pinned_bridge_url,
        "changelog": en_items,
        "changelog_ru": ru_items
    }
}
(OUTPUT / "smarttube_vox.json").write_text(
    json.dumps(legacy_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)

# 3. Modern feed: smarttube_vox_v2.json (schemaVersion 2 with 3 APK assets)
modern_manifest = {
    "schemaVersion": 2,
    "version": VERSION,
    "versionCode": CODE,
    "minSdk": 21,
    "targetSdk": 34,
    "changelog": en_items,
    "changelog_ru": ru_items,
    "assets": assets_meta,
    "sha256SumsUrl": f"https://github.com/{REPO}/releases/download/v{VERSION}/SHA256SUMS.txt"
}
(OUTPUT / "smarttube_vox_v2.json").write_text(
    json.dumps(modern_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)

if os.environ.get("GITHUB_ENV"):
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
        env_file.write(f"RELEASE_TITLE={title}\n")
print(f"Prepared {title}: Dual-Feed manifests (smarttube_vox.json + smarttube_vox_v2.json) and SHA256SUMS.txt.")
