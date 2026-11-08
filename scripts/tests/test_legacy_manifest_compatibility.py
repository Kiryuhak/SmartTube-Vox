#!/usr/bin/env python3
"""
Test legacy manifest compatibility across all 5 historical APK versions (8, 8.1, 9, 9.1, 9.2).
Tests both upstream AppVersionChecker logic and VOX VoxOtaReleaseParser + VoxReleaseAssetSelector.
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
    m = re.match(r"^(\d+)\.(\d+)-(?:vox|vot)\.(\d+)(?:\.(\d+))?(?:-(dev|rc\.?(\d+)))?", ver, re.IGNORECASE)
    if not m:
        return None
    major = int(m.group(1))
    minor = int(m.group(2))
    vox_major = int(m.group(3))
    vox_minor = int(m.group(4)) if m.group(4) else 0
    dev = m.group(5)
    rc = int(m.group(6)) if m.group(6) else None
    if dev and dev.lower() == "dev":
        stage = -1
    elif rc is not None:
        stage = rc
    else:
        stage = 100
    is_pre = stage < 100 or (vox_major >= 9 and vox_minor > 0)
    return {
        "major": major, "minor": minor, "vox_major": vox_major, "vox_minor": vox_minor,
        "stage": stage, "is_pre": is_pre
    }

def legacy_is_update_available_vox92(installed_ver, candidate_ver, is_beta, installed_vc, candidate_vc):
    if candidate_vc <= installed_vc:
        return False
    p_inst = parse_legacy_version(installed_ver)
    p_cand = parse_legacy_version(candidate_ver)
    
    cand_is_pre = p_cand["stage"] < 100 or (p_cand["vox_major"] >= 9 and p_cand["vox_minor"] > 0)
    cand_is_canon_stable = p_cand["stage"] == 100 and (p_cand["vox_major"] < 9 or p_cand["vox_minor"] == 0)
    
    if not is_beta and not cand_is_canon_stable:
        return False
        
    if p_inst["vox_major"] != p_cand["vox_major"]:
        return p_cand["vox_major"] > p_inst["vox_major"]
    if p_inst["vox_minor"] != p_cand["vox_minor"]:
        return p_cand["vox_minor"] > p_inst["vox_minor"]
    if p_inst["major"] != p_cand["major"]:
        return p_cand["major"] > p_inst["major"]
    if p_inst["minor"] != p_cand["minor"]:
        return p_cand["minor"] > p_inst["minor"]
    return p_cand["stage"] > p_inst["stage"]

def app_version_checker_parse(json_data, installed_vc):
    pkg = json_data.get("package", {})
    download_urls = pkg.get("downloadUrlList", [pkg.get("downloadUrl")])
    
    versions = {}
    for k, v in json_data.items():
        if k == "package":
            continue
        if isinstance(v, dict) and "versionCode" in v:
            versions[v["versionCode"]] = (k, v)
            
    if not versions:
        return None
        
    latest_vc = max(versions.keys())
    latest_ver_name, latest_info = versions[latest_vc]
    
    update_available = latest_vc > installed_vc
    return {
        "update_available": update_available,
        "target_version": latest_ver_name,
        "target_version_code": latest_vc,
        "download_url": download_urls[0] if download_urls else None
    }

def vox_release_parser_parse(json_data, channel, installed_ver, installed_vc):
    is_beta = channel == "beta"
    
    # Variant 2: Custom VOX manifest
    if "version" in json_data and ("assets" in json_data or "downloadUrl" in json_data):
        ver_name = json_data["version"]
        target_vc = json_data.get("versionCode", 0)
        assets = []
        if "assets" in json_data:
            for a in json_data["assets"]:
                assets.append(Asset(a["name"], a["downloadUrl"]))
        elif "downloadUrl" in json_data:
            assets.append(Asset("SmartTube_vot_universal.apk", json_data["downloadUrl"]))
        selected = select_asset_vox92(assets)
        avail = legacy_is_update_available_vox92(installed_ver, ver_name, is_beta, installed_vc, target_vc)
        return {
            "parser": "parseVoxManifest",
            "target_version": ver_name,
            "target_version_code": target_vc,
            "selected_asset": selected.name if selected else None,
            "download_url": selected.url if selected else None,
            "update_available": avail
        }
        
    # Variant 3: Classic manifest
    releases = []
    for k, v in json_data.items():
        if k == "package":
            continue
        if isinstance(v, dict):
            vc = v.get("versionCode", 0)
            durl = v.get("downloadUrl", json_data.get("package", {}).get("downloadUrl", ""))
            asset_name = f"SmartTube_vot_{k}.apk"
            assets = [Asset(asset_name, durl)] if durl else []
            selected = select_asset_vox92(assets)
            avail = legacy_is_update_available_vox92(installed_ver, k, is_beta, installed_vc, vc)
            releases.append({
                "ver": k, "vc": vc, "asset": selected.name if selected else None, "durl": durl, "avail": avail
            })
    return {"parser": "parseClassicManifest", "releases": releases}

manifest_unified = {
  "version": "32.56-vox.10",
  "versionCode": 2446050,
  "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk",
  "package": {
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk",
    "downloadUrlList": [
      "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk"
    ]
  },
  "32.56-vox.10": {
    "versionCode": 2446050,
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk"
  },
  "32.56-vox.10-universal": {
    "versionCode": 2446050,
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk"
  }
}

manifest_classic_no_universal = {
  "package": {
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk"
  },
  "32.56-vox.10": {
    "versionCode": 2446050,
    "downloadUrl": "https://github.com/Kiryuhak/SmartTube-Vox/releases/download/v32.56-vox.10/SmartTube_vot_32.56-vox.10_universal.apk"
  }
}

installed_apks = [
    ("v8", "32.56-vox.8", 2446011, "stable"),
    ("v8.1", "32.56-vox.8.1", 2446012, "stable"),
    ("v9", "32.56-vox.9", 2446015, "stable"),
    ("v9.1", "32.56-vox.9.1", 2446016, "beta"),
    ("v9.2", "32.56-vox.9.2-rc.13", 2446032, "beta")
]

def main():
    print("=== TRUTH TABLE FOR UNIFIED MANIFEST (Future Real smarttube_vox.json) ===")
    all_ok = True
    for tag, ver, vc, ch in installed_apks:
        avc_res = app_version_checker_parse(manifest_unified, vc)
        vox_res = vox_release_parser_parse(manifest_unified, ch, ver, vc)
        print(f"[{tag}] ver={ver}, vc={vc}, ch={ch}:")
        print(f"   AppVersionChecker: avail={avc_res['update_available']}, target_vc={avc_res['target_version_code']}, url={avc_res['download_url']}")
        print(f"   VoxReleaseParser:  avail={vox_res['update_available']}, parser={vox_res['parser']}, asset={vox_res['selected_asset']}")
        if not avc_res['update_available'] or not vox_res['update_available']:
            all_ok = False
        if vox_res['selected_asset'] != "SmartTube_vot_universal.apk":
            all_ok = False

    print("\n=== TRUTH TABLE FOR DEFECTIVE CLASSIC MANIFEST (Missing universal key) ===")
    for tag, ver, vc, ch in [("v9.1", "32.56-vox.9.1", 2446016, "beta"), ("v9.2", "32.56-vox.9.2-rc.13", 2446032, "beta")]:
        vox_res = vox_release_parser_parse(manifest_classic_no_universal, ch, ver, vc)
        print(f"[{tag}] Defective classic: parser={vox_res['parser']}, releases={vox_res['releases']}")
        for r in vox_res['releases']:
            if r['ver'] == "32.56-vox.10":
                print(f"   -> Release '32.56-vox.10' selected asset: {r['asset']} (Expected None/Rejected)")
                assert r['asset'] is None, "Defective classic should yield None asset!"

    if all_ok:
        print("\nALL 5 HISTORICAL APKS COMPATIBILITY VERIFIED SUCCESSFULLY (100% PASS)")
    else:
        print("\nCOMPATIBILITY FAILURE DETECTED!")
        exit(1)

if __name__ == "__main__":
    main()
