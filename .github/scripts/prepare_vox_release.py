#!/usr/bin/env python3
"""Prepare public release notes and the in-app update manifest."""

import json
import os
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(os.environ.get("RELEASE_OUTPUT_DIR", "."))
VERSION = os.environ["VERSION_NAME"]
CODE = int(os.environ["EXPECTED_CODE"])
REPO = os.environ["REPO"]

if not re.fullmatch(r"\d+\.\d+-vot\.\d+", VERSION):
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
manifest = {
    "package": {
        "downloadUrl": f"https://github.com/{REPO}/releases/latest/download/smarttube_vox.apk"
    },
    VERSION: {
        "versionCode": CODE,
        "changelog": en_items,
        "changelog_ru": ru_items,
    },
}
(OUTPUT / "smarttube_vox.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
if os.environ.get("GITHUB_ENV"):
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env_file:
        env_file.write(f"RELEASE_TITLE={title}\n")
print(f"Prepared {title}: {len(en_items)} English and {len(ru_items)} Russian update bullets.")
