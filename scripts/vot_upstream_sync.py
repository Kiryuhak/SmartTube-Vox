#!/usr/bin/env python3
"""
VOT Upstream Synchronization & Structural Drift Detection Engine.

Tracks upstream releases of ilyhalight/voice-over-translation (VOT userscript),
detects structural diffs across Protobuf, API Endpoints, Settings, Constants,
and Region ASTs, and classifies Android impact for SmartTube VOX.

NOTE: JavaScript userscripts are NEVER executed in the Android APK.
They serve solely as a reference implementation. Production Kotlin/Java
code is NEVER automatically patched.
"""

import argparse
import hashlib
import json
import os
import re
import socket
import ssl
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

CANONICAL_UPSTREAM_URL = (
    "https://raw.githubusercontent.com/ilyhalight/voice-over-translation/master/dist/vot.user.js"
)
CANONICAL_REPOSITORY_URL = "https://github.com/ilyhalight/voice-over-translation.git"
DEFAULT_TARGET_REFERENCE = "docs/reference/vot.user.js"
DEFAULT_BASELINE_JSON = "docs/reference/vot-reference.json"
DEFAULT_DIFF_MD = "docs/vot-upstream-diff.md"
DEFAULT_REPORT_JSON = "build/reports/vot-upstream-diff.json"

MIN_FILE_SIZE_BYTES = 50_000
USER_AGENT = "SmartTube-VOX-Upstream-Sync/1.0 (+https://github.com/yuliskov/SmartTube)"


class UpstreamStatus:
    NO_CHANGE = "NO_CHANGE"
    VERSION_CHANGED = "VERSION_CHANGED"
    CONTENT_CHANGED_SAME_VERSION = "CONTENT_CHANGED_SAME_VERSION"
    ERROR = "ERROR"


class AndroidImpact:
    NO_ANDROID_IMPACT = "NO_ANDROID_IMPACT"
    BROWSER_ONLY = "BROWSER_ONLY"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    NATIVE_PORT_REQUIRED = "NATIVE_PORT_REQUIRED"
    UNKNOWN_REQUIRES_REVIEW = "UNKNOWN_REQUIRES_REVIEW"


class NetworkErrorType:
    DNS_ERROR = "DNS_ERROR"
    TLS_ERROR = "TLS_ERROR"
    TIMEOUT = "TIMEOUT"
    HTTP_ERROR = "HTTP_ERROR"
    NETWORK_ERROR = "NETWORK_ERROR"
    INVALID_CONTENT = "INVALID_CONTENT"
    PARSE_ERROR = "PARSE_ERROR"


class UpstreamSyncException(Exception):
    def __init__(self, error_type: str, message: str, status_code: Optional[int] = None):
        super().__init__(f"[{error_type}] {message}")
        self.error_type = error_type
        self.message = message
        self.status_code = status_code


# ----------------------------------------------------------------------
# Validation
# ----------------------------------------------------------------------

def validate_userscript_content(content: str, min_size: int = MIN_FILE_SIZE_BYTES) -> None:
    """Validates downloaded userscript content for size and header integrity."""
    if not content or len(content.strip()) == 0:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Received empty content from upstream."
        )

    if len(content.encode("utf-8")) < min_size:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            f"Content size ({len(content.encode('utf-8'))} bytes) is below minimum expected threshold ({min_size} bytes)."
        )

    # Check for HTML error pages
    first_chunk = content[:500].lower()
    if "<html" in first_chunk or "<!doctype html" in first_chunk or "<head" in first_chunk:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Content appears to be an HTML page rather than a JavaScript userscript."
        )

    # Check UserScript header blocks
    if "// ==UserScript==" not in content:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Missing '// ==UserScript==' header directive."
        )

    if "// ==/UserScript==" not in content:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Missing '// ==/UserScript==' header closing directive."
        )

    # Check @name and @version
    meta_section = content.split("// ==/UserScript==")[0]
    if not re.search(r"//\s*@name\b", meta_section):
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Missing '@name' metadata entry in UserScript header."
        )

    version_match = re.search(r"//\s*@version\s+([0-9]+\.[0-9]+(?:\.[0-9]+)?)", meta_section)
    if not version_match:
        raise UpstreamSyncException(
            NetworkErrorType.INVALID_CONTENT,
            "Missing or unparseable '@version' metadata entry in UserScript header."
        )


# ----------------------------------------------------------------------
# Structural Parsing & Inventory Extraction
# ----------------------------------------------------------------------

def extract_metadata(content: str) -> Dict[str, Any]:
    """Extracts userscript header metadata entries."""
    meta_match = re.search(r"// ==UserScript==\r?\n(.*?)\r?\n// ==/UserScript==", content, re.DOTALL)
    metadata: Dict[str, Any] = {}
    if not meta_match:
        return metadata

    for line in meta_match.group(1).splitlines():
        line = line.strip()
        if line.startswith("//"):
            m = re.match(r"^//\s*@([a-zA-Z0-9_:\-]+)(?:\s+(.*))?$", line)
            if m:
                k, v = m.group(1), (m.group(2) or "").strip()
                if k in metadata:
                    if isinstance(metadata[k], list):
                        metadata[k].append(v)
                    else:
                        metadata[k] = [metadata[k], v]
                else:
                    metadata[k] = v
    return metadata


def extract_protobuf_messages(content: str) -> Dict[str, List[Dict[str, Any]]]:
    """
    Extracts Protobuf message declarations and their fields (number, name, type)
    from JS bundle decode() routines.
    """
    pattern = r"var\s+([A-Za-z0-9_]+)\s*=\s*\{[\s\S]*?decode\s*\([^)]*\)\s*\{([\s\S]*?return message;[\s\S]*?\})"
    matches = re.findall(pattern, content)
    messages: Dict[str, List[Dict[str, Any]]] = {}

    for msg_name, decode_body in matches:
        # Pattern captures field number, field name, push array type, reader primitive, or nested message decode
        case_pattern = (
            r"case\s+(\d+):\s*(?:if\s*\([^)]*\)\s*break;)?\s*message\.([A-Za-z0-9_]+)"
            r"(?:\.push\((.*?)\.decode|\s*=\s*(?:reader\.([a-zA-Z0-9_]+)\(|([A-Za-z0-9_]+)\.decode\())"
        )
        cases = re.findall(case_pattern, decode_body)
        if cases:
            field_list = []
            seen_fields = set()
            for field_num_str, field_name, push_type, r_type, nested_type in cases:
                field_num = int(field_num_str)
                if (field_num, field_name) in seen_fields:
                    continue
                seen_fields.add((field_num, field_name))
                ftype = r_type if r_type else ((push_type or nested_type) + " (nested/repeated)")
                field_list.append({
                    "number": field_num,
                    "name": field_name,
                    "type": ftype
                })
            field_list.sort(key=lambda x: x["number"])
            messages[msg_name] = field_list

    return messages


def extract_endpoints(content: str) -> List[str]:
    """Extracts known and candidate API endpoint paths."""
    endpoints = set(re.findall(r"[\"'](/(?:video-translation|video-subtitles|stream-translation|session|audio-proxy)/[a-zA-Z0-9_\-/]+)[\"']", content))
    endpoints.update(re.findall(r"[\"'](/(?:translate|session)/[a-zA-Z0-9_\-/]+)[\"']", content))
    endpoints.update(re.findall(r"paths\s*=\s*\{([^}]+)\}", content))

    extracted = set()
    for item in endpoints:
        if "/" in item:
            for sub in re.findall(r"/(?:[a-zA-Z0-9_\-]+(?:/[a-zA-Z0-9_\-]+)*)", item):
                if any(prefix in sub for prefix in ["video-translation", "video-subtitles", "stream-translation", "session", "translate"]):
                    extracted.add(sub)
        else:
            for match in re.findall(r"[\"'](/[a-zA-Z0-9_\-/]+)[\"']", item):
                extracted.add(match)

    return sorted(list(extracted))


def extract_storage_keys(content: str) -> List[str]:
    """Extracts persistence and settings storage keys."""
    keys = set(re.findall(r"storageKey:\s*[\"']([A-Za-z0-9_]+)[\"']", content))
    keys.update(re.findall(r"votStorage\.(?:get|set|has|delete)\([\"']([A-Za-z0-9_]+)[\"']", content))
    return sorted(list(keys))


def extract_constants(content: str) -> Dict[str, Any]:
    """Extracts critical constants such as smart ducking configuration thresholds."""
    constants: Dict[str, Any] = {}

    ducking_match = re.search(r"SMART_DUCKING_DEFAULT_CONFIG\s*=\s*Object\.freeze\(\{([^}]+)\}\)", content)
    if ducking_match:
        ducking_consts = {}
        for line in ducking_match.group(1).splitlines():
            line = line.strip().rstrip(",")
            if ":" in line:
                k, v = [x.strip() for x in line.split(":", 1)]
                ducking_consts[k] = v
        constants["smartDucking"] = ducking_consts

    # Default duration
    duration_match = re.search(r"defaultDuration:\s*([0-9]+)", content)
    if duration_match:
        constants["defaultDuration"] = int(duration_match.group(1))

    # Version in bundle
    config_ver_match = re.search(r"config_default\s*=\s*\{\s*version:\s*[\"']([0-9.]+)[\"']", content)
    if config_ver_match:
        constants["chaimuVersion"] = config_ver_match.group(1)

    return constants


def extract_regions(content: str) -> Dict[str, str]:
    """
    Extracts //#region <name> sections and computes SHA-256 for each block.
    """
    parts = re.split(r"//#region\s+", content)
    regions: Dict[str, str] = {}
    for part in parts[1:]:
        lines = part.split("\n", 1)
        region_name = lines[0].strip()
        region_content = lines[1] if len(lines) > 1 else ""
        end_idx = region_content.find("//#endregion")
        if end_idx != -1:
            region_content = region_content[:end_idx]
        region_hash = hashlib.sha256(region_content.strip().encode("utf-8")).hexdigest()
        regions[region_name] = region_hash

    return regions


def extract_structural_info(content: str) -> Dict[str, Any]:
    """Extracts a comprehensive structural inventory from userscript content."""
    validate_userscript_content(content)
    raw_bytes = content.encode("utf-8")
    meta = extract_metadata(content)

    return {
        "version": meta.get("version", "unknown"),
        "sha256": hashlib.sha256(raw_bytes).hexdigest(),
        "fileSize": len(raw_bytes),
        "lineCount": len(content.splitlines()),
        "sourceUrl": meta.get("downloadURL") or CANONICAL_UPSTREAM_URL,
        "repositoryUrl": CANONICAL_REPOSITORY_URL,
        "metadata": meta,
        "endpoints": extract_endpoints(content),
        "protobufMessages": extract_protobuf_messages(content),
        "storageKeys": extract_storage_keys(content),
        "constants": extract_constants(content),
        "regions": extract_regions(content),
    }


# ----------------------------------------------------------------------
# Impact Classifier
# ----------------------------------------------------------------------

def classify_region_impact(region_name: str) -> str:
    """Classifies Android impact for a changed region name."""
    # Browser DOM / scrapers
    if any(p in region_name for p in [
        "@vot.js/ext/dist/helpers",
        "@vot.js/ext/dist/players",
        "src/ui/",
        "src/styles/",
        "src/locales/",
        "plyr.js",
        "videojs.js",
        "bilibili.js"
    ]):
        return AndroidImpact.BROWSER_ONLY

    # Core protocol / audio / ducking / volume / protobuf
    if any(p in region_name for p in [
        "src/videoHandler/modules/ducking.ts",
        "src/videoHandler/modules/smartDuckingRuntime.ts",
        "src/videoHandler/volumeLink.ts",
        "src/videoHandler/translationVolume.ts",
        "@vot.js/core/dist/protobuf.js",
        "@vot.js/core/dist/providers/yandex.js",
        "src/utils/account.ts",
        "src/videoHandler/modules/translationPlayback.ts",
        "src/videoHandler/modules/translationShared.ts"
    ]):
        return AndroidImpact.NATIVE_PORT_REQUIRED

    # Settings / Subtitles / Segmenter
    if any(p in region_name for p in [
        "src/subtitles/",
        "src/VideoHandler.ts",
        "src/videoHandler/modules/subtitles.ts"
    ]):
        return AndroidImpact.REVIEW_REQUIRED

    return AndroidImpact.REVIEW_REQUIRED


# ----------------------------------------------------------------------
# Structural Comparison Engine
# ----------------------------------------------------------------------

def compare_structures(local_info: Dict[str, Any], upstream_info: Dict[str, Any]) -> Dict[str, Any]:
    """
    Performs structural diff analysis between local reference and upstream reference.
    """
    old_version = local_info.get("version", "unknown")
    new_version = upstream_info.get("version", "unknown")
    old_sha256 = local_info.get("sha256", "")
    new_sha256 = upstream_info.get("sha256", "")

    is_identical = (old_sha256 == new_sha256)
    version_differs = (old_version != new_version)

    if is_identical:
        status = UpstreamStatus.NO_CHANGE
        change_type = "NO_CHANGE"
    elif version_differs:
        status = UpstreamStatus.VERSION_CHANGED
        change_type = "VERSION_CHANGED"
    else:
        status = UpstreamStatus.CONTENT_CHANGED_SAME_VERSION
        change_type = "CONTENT_CHANGED_SAME_VERSION"

    changed_groups: List[str] = []

    # 1. Metadata diff
    meta_diff: Dict[str, Any] = {}
    old_meta = local_info.get("metadata", {})
    new_meta = upstream_info.get("metadata", {})
    for key in set(old_meta.keys()).union(new_meta.keys()):
        if old_meta.get(key) != new_meta.get(key):
            meta_diff[key] = {"old": old_meta.get(key), "new": new_meta.get(key)}
    if meta_diff:
        changed_groups.append("METADATA")

    # 2. Protobuf diff
    proto_diff: Dict[str, Any] = {
        "addedMessages": [],
        "removedMessages": [],
        "modifiedMessages": {}
    }
    old_proto = local_info.get("protobufMessages", {})
    new_proto = upstream_info.get("protobufMessages", {})

    old_msg_names = set(old_proto.keys())
    new_msg_names = set(new_proto.keys())

    proto_diff["addedMessages"] = sorted(list(new_msg_names - old_msg_names))
    proto_diff["removedMessages"] = sorted(list(old_msg_names - new_msg_names))

    for msg in sorted(old_msg_names.intersection(new_msg_names)):
        old_fields = {f["number"]: f for f in old_proto[msg]}
        new_fields = {f["number"]: f for f in new_proto[msg]}

        added_f = [new_fields[n] for n in sorted(set(new_fields.keys()) - set(old_fields.keys()))]
        removed_f = [old_fields[n] for n in sorted(set(old_fields.keys()) - set(new_fields.keys()))]
        changed_f = []

        for n in sorted(set(old_fields.keys()).intersection(set(new_fields.keys()))):
            of = old_fields[n]
            nf = new_fields[n]
            if of["name"] != nf["name"] or of["type"] != nf["type"]:
                changed_f.append({"number": n, "old": of, "new": nf})

        if added_f or removed_f or changed_f:
            proto_diff["modifiedMessages"][msg] = {
                "addedFields": added_f,
                "removedFields": removed_f,
                "changedFields": changed_f
            }

    has_proto_changes = bool(
        proto_diff["addedMessages"]
        or proto_diff["removedMessages"]
        or proto_diff["modifiedMessages"]
    )
    if has_proto_changes:
        changed_groups.append("PROTOBUF")
        max_impact = AndroidImpact.NATIVE_PORT_REQUIRED

    # 3. Endpoints diff
    old_endpoints = set(local_info.get("endpoints", []))
    new_endpoints = set(upstream_info.get("endpoints", []))
    endpoint_diff = {
        "added": sorted(list(new_endpoints - old_endpoints)),
        "removed": sorted(list(old_endpoints - new_endpoints))
    }
    has_endpoint_changes = bool(endpoint_diff["added"] or endpoint_diff["removed"])
    if has_endpoint_changes:
        changed_groups.append("ENDPOINTS")
        max_impact = AndroidImpact.NATIVE_PORT_REQUIRED

    # 4. Settings diff
    old_keys = set(local_info.get("storageKeys", []))
    new_keys = set(upstream_info.get("storageKeys", []))
    settings_diff = {
        "added": sorted(list(new_keys - old_keys)),
        "removed": sorted(list(old_keys - new_keys))
    }
    has_settings_changes = bool(settings_diff["added"] or settings_diff["removed"])
    if has_settings_changes:
        changed_groups.append("SETTINGS")

    # 5. Constants diff
    old_consts = local_info.get("constants", {})
    new_consts = upstream_info.get("constants", {})
    constant_diff: Dict[str, Any] = {}
    for ck in set(old_consts.keys()).union(new_consts.keys()):
        if old_consts.get(ck) != new_consts.get(ck):
            constant_diff[ck] = {"old": old_consts.get(ck), "new": new_consts.get(ck)}
    has_constant_changes = bool(constant_diff)
    if has_constant_changes:
        changed_groups.append("CONSTANTS")
        max_impact = AndroidImpact.NATIVE_PORT_REQUIRED

    # 6. Region hash diff
    old_regions = local_info.get("regions", {})
    new_regions = upstream_info.get("regions", {})
    region_diff: Dict[str, Any] = {
        "added": sorted(list(set(new_regions.keys()) - set(old_regions.keys()))),
        "removed": sorted(list(set(old_regions.keys()) - set(new_regions.keys()))),
        "changed": []
    }
    for r in sorted(set(old_regions.keys()).intersection(set(new_regions.keys()))):
        if old_regions[r] != new_regions[r]:
            imp = classify_region_impact(r)
            region_diff["changed"].append({
                "region": r,
                "oldHash": old_regions[r],
                "newHash": new_regions[r],
                "impact": imp
            })
            if imp == AndroidImpact.NATIVE_PORT_REQUIRED:
                max_impact = AndroidImpact.NATIVE_PORT_REQUIRED

    has_region_changes = bool(region_diff["added"] or region_diff["removed"] or region_diff["changed"])
    if has_region_changes:
        changed_groups.append("REGIONS")

    # Determine aggregated Android impact
    if is_identical:
        max_impact = AndroidImpact.NO_ANDROID_IMPACT
    else:
        impacts = set()
        if version_differs:
            impacts.add(AndroidImpact.REVIEW_REQUIRED)
        if has_proto_changes or has_endpoint_changes or has_constant_changes:
            impacts.add(AndroidImpact.NATIVE_PORT_REQUIRED)
        if has_settings_changes:
            impacts.add(AndroidImpact.REVIEW_REQUIRED)
        for rc in region_diff["changed"]:
            impacts.add(rc["impact"])
        for ra in region_diff["added"] + region_diff["removed"]:
            impacts.add(classify_region_impact(ra))

        if AndroidImpact.NATIVE_PORT_REQUIRED in impacts:
            max_impact = AndroidImpact.NATIVE_PORT_REQUIRED
        elif AndroidImpact.REVIEW_REQUIRED in impacts or AndroidImpact.UNKNOWN_REQUIRES_REVIEW in impacts:
            max_impact = AndroidImpact.REVIEW_REQUIRED
        elif AndroidImpact.BROWSER_ONLY in impacts:
            max_impact = AndroidImpact.BROWSER_ONLY
        else:
            max_impact = AndroidImpact.REVIEW_REQUIRED

    # Summary sentence
    if is_identical:
        summary = f"Upstream is 100% identical to local reference ({old_version})."
        recommended_action = "No action required. Reference is completely up-to-date."
    else:
        summary = (
            f"Upstream change detected ({change_type}): "
            f"{old_version} -> {new_version}. "
            f"Changed groups: {', '.join(changed_groups) if changed_groups else 'None'}."
        )
        if max_impact == AndroidImpact.NATIVE_PORT_REQUIRED:
            recommended_action = (
                "CRITICAL: Protobuf definitions, endpoints, or core audio algorithms have changed. "
                "Schedule a dedicated native adaptation phase before updating production code. "
                "DO NOT auto-patch Kotlin/Java files."
            )
        elif max_impact == AndroidImpact.REVIEW_REQUIRED:
            recommended_action = (
                "REVIEW REQUIRED: Settings or non-core regions changed. "
                "Review differences in docs/vot-upstream-diff.md and determine if TV UX adaptation is warranted."
            )
        else:
            recommended_action = (
                "BROWSER ONLY: Changes are limited to browser website scrapers or UI overlay. "
                "No Android TV code changes required."
            )

    return {
        "oldVersion": old_version,
        "newVersion": new_version,
        "oldSha256": old_sha256,
        "newSha256": new_sha256,
        "oldLines": local_info.get("lineCount", 0),
        "newLines": upstream_info.get("lineCount", 0),
        "oldSize": local_info.get("fileSize", 0),
        "newSize": upstream_info.get("fileSize", 0),
        "status": status,
        "changeType": change_type,
        "hasChanges": not is_identical,
        "reviewRequired": (status in [UpstreamStatus.VERSION_CHANGED, UpstreamStatus.CONTENT_CHANGED_SAME_VERSION]),
        "changedGroups": changed_groups,
        "metadataChanges": meta_diff,
        "protobufChanges": proto_diff,
        "endpointChanges": endpoint_diff,
        "settingsChanges": settings_diff,
        "constantChanges": constant_diff,
        "regionChanges": region_diff,
        "impact": max_impact,
        "summary": summary,
        "recommendedAction": recommended_action,
        "timestamp": datetime.now(timezone.utc).isoformat()
    }


# ----------------------------------------------------------------------
# Report Generators
# ----------------------------------------------------------------------

def generate_markdown_report(diff_result: Dict[str, Any], output_path: Optional[str] = None) -> str:
    """Generates human-readable Markdown diff report."""
    lines = [
        "# VOT Upstream Synchronization & Structural Diff Report",
        "",
        f"- **OLD_VERSION**: `{diff_result['oldVersion']}`",
        f"- **NEW_VERSION**: `{diff_result['newVersion']}`",
        f"- **OLD_SHA256**: `{diff_result['oldSha256']}`",
        f"- **NEW_SHA256**: `{diff_result['newSha256']}`",
        f"- **OLD_LINES**: `{diff_result['oldLines']}` lines ({diff_result['oldSize']} bytes)",
        f"- **NEW_LINES**: `{diff_result['newLines']}` lines ({diff_result['newSize']} bytes)",
        f"- **CHANGE_TYPE**: `{diff_result['changeType']}`",
        f"- **STATUS**: `{diff_result['status']}`",
        f"- **ANDROID_IMPACT**: `{diff_result['impact']}`",
        f"- **TIMESTAMP**: `{diff_result['timestamp']}`",
        "",
        "## SUMMARY",
        "",
        diff_result["summary"],
        "",
        "## CHANGED_GROUPS",
        "",
    ]

    if diff_result["changedGroups"]:
        for grp in diff_result["changedGroups"]:
            lines.append(f"- **{grp}**")
    else:
        lines.append("- *(No changed groups detected)*")
    lines.append("")

    # Protobuf changes
    lines.extend([
        "## PROTOBUF_CHANGES",
        ""
    ])
    pb = diff_result["protobufChanges"]
    if pb["addedMessages"]:
        lines.append(f"- **Added Messages**: {', '.join(pb['addedMessages'])}")
    if pb["removedMessages"]:
        lines.append(f"- **Removed Messages**: {', '.join(pb['removedMessages'])}")
    if pb["modifiedMessages"]:
        lines.append("- **Modified Messages**:")
        for msg, ch in pb["modifiedMessages"].items():
            lines.append(f"  - `{msg}`:")
            for f in ch.get("addedFields", []):
                lines.append(f"    - Added Field {f['number']}: `{f['name']}` ({f['type']})")
            for f in ch.get("removedFields", []):
                lines.append(f"    - Removed Field {f['number']}: `{f['name']}` ({f['type']})")
            for f in ch.get("changedFields", []):
                lines.append(f"    - Changed Field {f['number']}: `{f['old']['name']}` -> `{f['new']['name']}`")
    if not (pb["addedMessages"] or pb["removedMessages"] or pb["modifiedMessages"]):
        lines.append("- *(No protobuf wire changes)*")
    lines.append("")

    # Endpoint changes
    lines.extend([
        "## ENDPOINT_CHANGES",
        ""
    ])
    ep = diff_result["endpointChanges"]
    if ep["added"]:
        lines.append(f"- **Added Endpoints**: {', '.join(ep['added'])}")
    if ep["removed"]:
        lines.append(f"- **Removed Endpoints**: {', '.join(ep['removed'])}")
    if not (ep["added"] or ep["removed"]):
        lines.append("- *(No endpoint changes)*")
    lines.append("")

    # Settings changes
    lines.extend([
        "## SETTINGS_CHANGES",
        ""
    ])
    st = diff_result["settingsChanges"]
    if st["added"]:
        lines.append(f"- **Added Settings**: {', '.join(st['added'])}")
    if st["removed"]:
        lines.append(f"- **Removed Settings**: {', '.join(st['removed'])}")
    if not (st["added"] or st["removed"]):
        lines.append("- *(No settings changes)*")
    lines.append("")

    # Constant changes
    lines.extend([
        "## CONSTANT_CHANGES",
        ""
    ])
    ct = diff_result["constantChanges"]
    if ct:
        for k, v in ct.items():
            lines.append(f"- `{k}`: `{v['old']}` -> `{v['new']}`")
    else:
        lines.append("- *(No critical constant changes)*")
    lines.append("")

    # Region changes
    lines.extend([
        "## FUNCTION_REGION_CHANGES",
        ""
    ])
    rg = diff_result["regionChanges"]
    if rg["added"]:
        lines.append(f"- **Added Regions** ({len(rg['added'])}): {', '.join(rg['added'][:10])}")
    if rg["removed"]:
        lines.append(f"- **Removed Regions** ({len(rg['removed'])}): {', '.join(rg['removed'][:10])}")
    if rg["changed"]:
        lines.append(f"- **Changed Regions** ({len(rg['changed'])}):")
        for rc in rg["changed"][:25]:
            lines.append(f"  - `{rc['region']}` [{rc['impact']}]")
        if len(rg["changed"]) > 25:
            lines.append(f"  - ... and {len(rg['changed']) - 25} more")
    if not (rg["added"] or rg["removed"] or rg["changed"]):
        lines.append("- *(No region hash changes)*")
    lines.append("")

    # Recommended action
    lines.extend([
        "## RECOMMENDED_ACTION",
        "",
        diff_result["recommendedAction"],
        ""
    ])

    report_text = "\n".join(lines)
    if output_path:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(report_text)
    return report_text


def generate_json_report(diff_result: Dict[str, Any], output_path: Optional[str] = None) -> str:
    """Generates machine-readable JSON diff report."""
    json_text = json.dumps(diff_result, indent=2, ensure_ascii=False)
    if output_path:
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(json_text)
    return json_text


# ----------------------------------------------------------------------
# Downloader & Upstream Fetcher
# ----------------------------------------------------------------------

def fetch_upstream_content(url: str, timeout: int = 30) -> str:
    """
    Safely downloads upstream userscript content into memory with network error taxonomy.
    """
    req = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "text/javascript, application/javascript, text/plain"}
    )

    try:
        context = ssl.create_default_context()
        with urllib.request.urlopen(req, timeout=timeout, context=context) as response:
            status_code = response.getcode()
            if status_code != 200:
                raise UpstreamSyncException(
                    NetworkErrorType.HTTP_ERROR,
                    f"HTTP response status code {status_code}",
                    status_code=status_code
                )
            raw_bytes = response.read()
            return raw_bytes.decode("utf-8", errors="replace")

    except urllib.error.HTTPError as e:
        raise UpstreamSyncException(
            NetworkErrorType.HTTP_ERROR,
            f"HTTP {e.code}: {e.reason}",
            status_code=e.code
        ) from e
    except urllib.error.URLError as e:
        reason = str(e.reason).lower()
        if isinstance(e.reason, socket.gaierror) or "name resolution" in reason or "getaddrinfo" in reason:
            raise UpstreamSyncException(NetworkErrorType.DNS_ERROR, f"DNS resolution failed: {e.reason}") from e
        elif isinstance(e.reason, ssl.SSLError) or "ssl" in reason:
            raise UpstreamSyncException(NetworkErrorType.TLS_ERROR, f"TLS/SSL handshake failed: {e.reason}") from e
        elif isinstance(e.reason, socket.timeout) or "timed out" in reason:
            raise UpstreamSyncException(NetworkErrorType.TIMEOUT, f"Connection timed out: {e.reason}") from e
        else:
            raise UpstreamSyncException(NetworkErrorType.NETWORK_ERROR, f"Network error: {e.reason}") from e
    except socket.timeout as e:
        raise UpstreamSyncException(NetworkErrorType.TIMEOUT, f"Socket timed out: {e}") from e
    except Exception as e:
        raise UpstreamSyncException(NetworkErrorType.NETWORK_ERROR, f"Unexpected fetch error: {e}") from e


# ----------------------------------------------------------------------
# Baseline Snapshot
# ----------------------------------------------------------------------

def save_baseline_snapshot(info: Dict[str, Any], filepath: str) -> None:
    """Saves structural inventory baseline JSON snapshot."""
    os.makedirs(os.path.dirname(os.path.abspath(filepath)), exist_ok=True)
    snapshot = dict(info)
    snapshot["savedAt"] = datetime.now(timezone.utc).isoformat()
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(snapshot, f, indent=2, ensure_ascii=False)


# ----------------------------------------------------------------------
# Atomic Reference Update
# ----------------------------------------------------------------------

def atomic_update_reference(
    target_path: str,
    new_content: str,
    baseline_path: str,
    diff_md_path: str,
    report_json_path: str,
    diff_result: Dict[str, Any]
) -> None:
    """
    Atomically updates reference userscript, baseline snapshot, and reports.
    Never alters Kotlin/Java files.
    """
    target_abs = os.path.abspath(target_path)
    target_dir = os.path.dirname(target_abs)
    os.makedirs(target_dir, exist_ok=True)

    # 1. Write content to temp file in same filesystem for atomic rename
    with tempfile.NamedTemporaryFile("w", dir=target_dir, delete=False, encoding="utf-8") as tmp:
        tmp.write(new_content)
        tmp_path = tmp.name

    try:
        # Atomic replace
        os.replace(tmp_path, target_abs)
    except Exception as e:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        raise UpstreamSyncException(
            NetworkErrorType.PARSE_ERROR,
            f"Atomic file replacement failed: {e}"
        ) from e

    # 2. Update baseline snapshot
    new_info = extract_structural_info(new_content)
    save_baseline_snapshot(new_info, baseline_path)

    # 3. Update reports
    generate_markdown_report(diff_result, diff_md_path)
    generate_json_report(diff_result, report_json_path)


# ----------------------------------------------------------------------
# CLI Interface
# ----------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="SmartTube VOX Upstream Synchronization & Structural Drift Detector."
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument(
        "--check",
        action="store_true",
        default=True,
        help="Check upstream for version or content updates without modifying files (default)."
    )
    group.add_argument(
        "--update",
        action="store_true",
        help="Download and atomically update reference if valid changes detected."
    )
    group.add_argument(
        "--report",
        action="store_true",
        help="Generate structural diff report."
    )
    parser.add_argument(
        "--target-file",
        default=DEFAULT_TARGET_REFERENCE,
        help=f"Path to local reference userscript (default: {DEFAULT_TARGET_REFERENCE})."
    )
    parser.add_argument(
        "--baseline-file",
        default=DEFAULT_BASELINE_JSON,
        help=f"Path to baseline reference JSON (default: {DEFAULT_BASELINE_JSON})."
    )
    parser.add_argument(
        "--upstream-url",
        default=CANONICAL_UPSTREAM_URL,
        help=f"Upstream URL to fetch (default: {CANONICAL_UPSTREAM_URL})."
    )
    parser.add_argument(
        "--upstream-file",
        default=None,
        help="Optional local file to simulate upstream content (for offline tests)."
    )
    parser.add_argument(
        "--markdown-report",
        default=DEFAULT_DIFF_MD,
        help=f"Path to output Markdown diff report (default: {DEFAULT_DIFF_MD})."
    )
    parser.add_argument(
        "--json-report",
        default=DEFAULT_REPORT_JSON,
        help=f"Path to output JSON machine report (default: {DEFAULT_REPORT_JSON})."
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        default=False,
        help="Exit with non-zero code (1) if upstream differs (useful for CI review detection)."
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Suppress informational stdout output."
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    is_update_mode = args.update
    is_report_mode = args.report

    # 1. Read local reference
    if not os.path.exists(args.target_file):
        print(f"ERROR: Target reference file not found at {args.target_file}", file=sys.stderr)
        return 2

    try:
        with open(args.target_file, "r", encoding="utf-8") as f:
            local_content = f.read()
        local_info = extract_structural_info(local_content)
    except Exception as e:
        print(f"ERROR: Failed parsing local reference {args.target_file}: {e}", file=sys.stderr)
        return 2

    # If baseline json doesn't exist yet, create it from local reference
    if not os.path.exists(args.baseline_file):
        save_baseline_snapshot(local_info, args.baseline_file)

    # 2. Acquire upstream content
    try:
        if args.upstream_file:
            if not os.path.exists(args.upstream_file):
                print(f"ERROR: Upstream test file not found at {args.upstream_file}", file=sys.stderr)
                return 2
            with open(args.upstream_file, "r", encoding="utf-8") as f:
                upstream_content = f.read()
        else:
            if not args.quiet:
                print(f"Fetching upstream from: {args.upstream_url} ...")
            upstream_content = fetch_upstream_content(args.upstream_url)

        validate_userscript_content(upstream_content)
        upstream_info = extract_structural_info(upstream_content)
    except UpstreamSyncException as e:
        print(f"UPSTREAM_FETCH_FAILED: {e}", file=sys.stderr)
        return 2
    except Exception as e:
        print(f"UNEXPECTED_ERROR: {e}", file=sys.stderr)
        return 2

    # 3. Compare structures
    diff_result = compare_structures(local_info, upstream_info)

    # 4. Handle modes
    if is_update_mode:
        if diff_result["status"] == UpstreamStatus.NO_CHANGE:
            if not args.quiet:
                print("STATUS: NO_CHANGE")
                print(f"Current reference ({local_info['version']}) is already up-to-date with upstream.")
            generate_markdown_report(diff_result, args.markdown_report)
            generate_json_report(diff_result, args.json_report)
            return 0
        else:
            if not args.quiet:
                print(f"STATUS: {diff_result['status']}")
                print(f"Updating local reference: {local_info['version']} -> {upstream_info['version']} ...")
            atomic_update_reference(
                args.target_file,
                upstream_content,
                args.baseline_file,
                args.markdown_report,
                args.json_report,
                diff_result
            )
            if not args.quiet:
                print("UPDATE_COMPLETE: Successfully updated reference and generated reports.")
                print(f"New SHA256: {upstream_info['sha256']}")
            return 0

    # Default Check / Report mode
    generate_markdown_report(diff_result, args.markdown_report)
    generate_json_report(diff_result, args.json_report)

    if not args.quiet:
        print("==================================================")
        print("VOT UPSTREAM SYNCHRONIZATION STATUS")
        print("==================================================")
        print(f"CURRENT_VERSION : {diff_result['oldVersion']}")
        print(f"CURRENT_SHA256  : {diff_result['oldSha256']}")
        print(f"UPSTREAM_VERSION: {diff_result['newVersion']}")
        print(f"UPSTREAM_SHA256 : {diff_result['newSha256']}")
        print(f"STATUS          : {diff_result['status']}")
        print(f"ANDROID_IMPACT  : {diff_result['impact']}")
        print(f"SUMMARY         : {diff_result['summary']}")
        if diff_result["changedGroups"]:
            print(f"CHANGED_GROUPS  : {', '.join(diff_result['changedGroups'])}")
        print("==================================================")

    if diff_result["status"] != UpstreamStatus.NO_CHANGE and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
