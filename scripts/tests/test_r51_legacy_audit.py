#!/usr/bin/env python3
"""
Test R51 legacy APK compatibility matrix across all 5 historical APK versions (8, 8.1, 9, 9.1, 9.2-rc.13)
and 6 channel scenarios against target 32.71-vox.10 (versionCode 2461010).
"""

import json
import re

class Asset:
    def __init__(self, name, url):
        self.name = name
        self.url = url

def is_universal(name):
    low = name.lower()
    return low.endswith("_universal.apk") or "universal" in low

def select_asset_vox92(assets, preferred_variant=None):
    apk_assets = [a for a in assets if a.name.lower().endswith(".apk")]
    if not apk_assets:
        return None
    universal = [a for a in apk_assets if is_universal(a.name)]
    univ_asset = universal[0] if universal else None
    if preferred_variant == "universal" or preferred_variant is None:
        return univ_asset
    return None

def parse_legacy_version(ver):
    m = re.match(r"^(\d+)\.(\d+)-(?:vox|vot)\.(\d+)(?:\.(\d+))?(?:-(dev|beta\.?(\d+)|rc\.?(\d+)))?", ver, re.IGNORECASE)
    if not m:
        # Check simple or base-engine
        m2 = re.match(r"^(\d+)\.(\d+)(?:\.(\d+))?-(?:vox|vot)\.(\d+)", ver, re.IGNORECASE)
        if m2:
            return {
                "major": int(m2.group(1)), "minor": int(m2.group(2)), "patch": int(m2.group(3)) if m2.group(3) else 0,
                "vox_major": int(m2.group(4)), "vox_minor": 0, "stage": 100, "is_pre": False
            }
        return None
    major = int(m.group(1))
    minor = int(m.group(2))
    vox_major = int(m.group(3))
    vox_minor = int(m.group(4)) if m.group(4) else 0
    tag = m.group(5)
    rc = int(m.group(7)) if m.group(7) else (int(m.group(6)) if m.group(6) else None)
    if tag and tag.lower() == "dev":
        stage = -1
    elif rc is not None:
        stage = rc
    else:
        stage = 100
    is_pre = stage < 100 or (vox_major >= 9 and vox_minor > 0)
    return {
        "major": major, "minor": minor, "patch": 0,
        "vox_major": vox_major, "vox_minor": vox_minor,
        "stage": stage, "is_pre": is_pre
    }

def legacy_is_update_available(installed_ver, candidate_ver, effective_channel, installed_vc, candidate_vc):
    if candidate_vc <= installed_vc:
        return False
    p_inst = parse_legacy_version(installed_ver)
    p_cand = parse_legacy_version(candidate_ver)
    if not p_inst or not p_cand:
        return False
        
    cand_is_canon_stable = p_cand["stage"] == 100 and (p_cand["vox_major"] < 9 or p_cand["vox_minor"] == 0)
    
    if effective_channel == "qa":
        return False # production candidate is not QA
    if effective_channel == "stable" and not cand_is_canon_stable:
        return False
    # If effective_channel == "beta" or "test": accepts both beta and canonical stable
    
    # Engine comparison
    if p_cand["major"] != p_inst["major"]:
        return p_cand["major"] > p_inst["major"]
    if p_cand["minor"] != p_inst["minor"]:
        return p_cand["minor"] > p_inst["minor"]
    if p_cand["vox_major"] != p_inst["vox_major"]:
        return p_cand["vox_major"] > p_inst["vox_major"]
    if p_cand["vox_minor"] != p_inst["vox_minor"]:
        return p_cand["vox_minor"] > p_inst["vox_minor"]
    return p_cand["stage"] > p_inst["stage"]

def resolve_legacy_effective_channel(version_name, pref_channel):
    # Historical logic:
    # 1. If pref_channel is set:
    if pref_channel is not None:
        c = pref_channel.strip().lower()
        if c in ("stable", "beta", "test", "qa"):
            return "beta" if c == "test" else c
        return "stable" # fallback
    # 2. If pref_channel is None, deduce from versionName
    p = parse_legacy_version(version_name)
    if p and p["is_pre"]:
        return "beta"
    return "stable"

def test_manifest_evaluation(manifest, installed_ver, installed_vc, pref_channel):
    eff_channel = resolve_legacy_effective_channel(installed_ver, pref_channel)
    
    # 1. AppVersionChecker logic
    pkg = manifest.get("package", {})
    download_urls = pkg.get("downloadUrlList", [pkg.get("downloadUrl")])
    versions = {}
    for k, v in manifest.items():
        if k == "package":
            continue
        if isinstance(v, dict) and "versionCode" in v:
            versions[v["versionCode"]] = (k, v)
    avc_ok = False
    if versions:
        latest_vc = max(versions.keys())
        avc_ok = latest_vc > installed_vc
        
    # 2. VoxOtaReleaseParser (Variant 2 custom VOX manifest)
    target_ver = manifest.get("version")
    target_vc = manifest.get("versionCode", 0)
    assets = []
    if "assets" in manifest:
        for a in manifest["assets"]:
            assets.append(Asset(a["name"], a["downloadUrl"]))
    elif "downloadUrl" in manifest:
        assets.append(Asset("SmartTube_vot_universal.apk", manifest["downloadUrl"]))
        
    selected = select_asset_vox92(assets)
    avail = legacy_is_update_available(installed_ver, target_ver, eff_channel, installed_vc, target_vc)
    
    return {
        "eff_channel": eff_channel,
        "avc_update_available": avc_ok,
        "vox_update_available": avail,
        "selected_asset": selected.name if selected else None,
        "both_pass": avc_ok and avail and (selected is not None)
    }

# Candidate manifest (smarttube_vox.json) pointing to 32.71-vox.10
manifest_3271_vox10 = {
    "version": "32.71-vox.10",
    "versionCode": 2461010,
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk",
    "package": {
        "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk",
        "downloadUrlList": [
            "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk"
        ]
    },
    "32.71-vox.10": {
        "versionCode": 2461010,
        "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk"
    },
    "32.71-vox.10-universal": {
        "versionCode": 2461010,
        "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.71-vox.10/SmartTube_vot_32.71-vox.10_universal.apk"
    }
}

historical_apks = [
    ("v8", "32.56-vox.8", 2446011),
    ("v8.1", "32.56-vox.8.1", 2446012),
    ("v9", "32.56-vox.9", 2446015),
    ("v9.1", "32.56-vox.9.1", 2446016),
    ("v9.2", "32.56-vox.9.2-rc.13", 2446032)
]

scenarios = [
    ("User Stable", "stable"),
    ("User Beta", "beta"),
    ("Legacy Test Alias", "test"),
    ("No Pref (Null)", None),
    ("Unknown/Invalid Pref", "unknown_custom"),
    ("Isolated QA Pref", "qa")
]

def main():
    print("==========================================================================")
    print("AUDIT MATRIX: 5 HISTORICAL APKS x 6 SCENARIOS -> 32.71-vox.10")
    print("==========================================================================")
    
    total_runs = 0
    passed_expected = 0
    
    for tag, ver, vc in historical_apks:
        print(f"\n--- Testing APK {tag} ({ver}, vc={vc}) ---")
        for sc_name, pref in scenarios:
            total_runs += 1
            res = test_manifest_evaluation(manifest_3271_vox10, ver, vc, pref)
            eff_ch = res["eff_channel"]
            both_pass = res["both_pass"]
            
            # Expected result:
            # QA scenario MUST NOT accept production release (channel isolation)
            if pref == "qa":
                expected = False
                status = "PASS (CORRECTLY ISOLATED)" if not both_pass else "FAIL (LEAKED TO QA)"
            else:
                expected = True
                status = "PASS (ACCEPTED)" if both_pass else "FAIL (REJECTED)"
                
            if both_pass == expected:
                passed_expected += 1
                
            print(f"  Scenario [{sc_name:20s}] pref={str(pref):14s} -> eff_ch={eff_ch:6s} | AVC={res['avc_update_available']} VOX={res['vox_update_available']} Asset={res['selected_asset']} -> {status}")

    print("\n==========================================================================")
    print(f"AUDIT SUMMARY: {passed_expected}/{total_runs} tests passed as expected.")
    if passed_expected == total_runs:
        print("ALL SCENARIOS VERIFIED 100% SUCCESSFUL!")
    else:
        print("FAILURES DETECTED!")
        exit(1)

if __name__ == "__main__":
    main()
