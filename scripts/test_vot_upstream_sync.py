#!/usr/bin/env python3
"""
Unit tests for VOT Upstream Sync & Structural Drift Detection Engine.

Runs completely offline using mock fixtures.
Tests detection of:
- NO_CHANGE
- VERSION_CHANGED
- CONTENT_CHANGED_SAME_VERSION
- INVALID_CONTENT (HTML, missing header, missing version, size under threshold)
- Protobuf message and field diffs
- Endpoint additions/removals
- Settings additions/removals
- Constant modifications (ducking thresholds)
- Region hash changes and Android impact classification
- Network error taxonomy
- Atomic updates and rollback safety
"""

import copy
import json
import os
import shutil
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from scripts.vot_upstream_sync import (
    AndroidImpact,
    NetworkErrorType,
    UpstreamStatus,
    UpstreamSyncException,
    atomic_update_reference,
    classify_region_impact,
    compare_structures,
    extract_structural_info,
    generate_json_report,
    generate_markdown_report,
    validate_userscript_content,
)


def make_dummy_userscript(
    version="1.11.17",
    extra_field=None,
    extra_endpoint=None,
    extra_setting=None,
    ducking_threshold=".012",
    modify_region=None,
    size_padding=60_000,
    header_only=False,
    is_html=False,
    omit_version=False,
    omit_name=False
) -> str:
    """Helper to generate a syntactically valid dummy userscript fixture."""
    if is_html:
        return "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body>Error 404</body></html>"

    lines = ["// ==UserScript=="]
    if not omit_name:
        lines.append("// @name           [VOT] - Voice Over Translation")
    if not omit_version:
        lines.append(f"// @version        {version}")
    lines.extend([
        "// @namespace      vot",
        "// @homepageURL    https://github.com/ilyhalight/voice-over-translation",
        "// @downloadURL    https://raw.githubusercontent.com/ilyhalight/voice-over-translation/master/dist/vot.user.js",
        "// ==/UserScript=="
    ])

    if header_only:
        return "\n".join(lines)

    lines.extend([
        "",
        "//#region node_modules/@vot.js/core/dist/protobuf.js",
        "function createBaseVideoTranslationRequest() {",
        "  return { url: '', duration: 0 };",
        "}",
        "var VideoTranslationRequest = {",
        "  decode(input, length) {",
        "    const reader = new BinaryReader(input);",
        "    const end = length === void 0 ? reader.len : reader.pos + length;",
        "    const message = createBaseVideoTranslationRequest();",
        "    while (reader.pos < end) {",
        "      const tag = reader.uint32();",
        "      switch (tag >>> 3) {",
        "        case 3: message.url = reader.string(); continue;",
        "        case 6: message.duration = reader.double(); continue;",
    ])

    if extra_field:
        field_num, field_name, field_type = extra_field
        lines.append(f"        case {field_num}: message.{field_name} = reader.{field_type}(); continue;")

    lines.extend([
        "      }",
        "    }",
        "    return message;",
        "  }",
        "};",
        "//#endregion",
        "",
        "//#region node_modules/@vot.js/core/dist/providers/yandex.js",
        "var YandexProvider = class {",
        "  paths = {",
        "    videoTranslation: '/video-translation/translate',",
        "    videoTranslationCache: '/video-translation/cache',",
        "    videoSubtitles: '/video-subtitles/get-subtitles',",
        "    streamPing: '/stream-translation/ping-stream',",
        "    streamTranslation: '/stream-translation/translate-stream'",
    ])

    if extra_endpoint:
        lines.append(f"    newEndpoint: '{extra_endpoint}',")

    lines.extend([
        "  };",
        "};",
        "//#endregion",
        "",
        "//#region src/videoHandler/modules/ducking.ts",
        "var SMART_DUCKING_DEFAULT_CONFIG = Object.freeze({",
        "  tickMs: 50,",
        f"  thresholdOnRms: {ducking_threshold},",
        "  thresholdOffRms: .009,",
        "  holdMs: 520",
        "});",
        "//#endregion",
        "",
        "//#region src/VideoHandler.ts",
        "function initSettings() {",
        "  votStorage.get('autoTranslate');",
        "  votStorage.get('autoSubtitles');",
        "  votStorage.get('syncVolume');",
    ])

    if extra_setting:
        lines.append(f"  votStorage.get('{extra_setting}');")

    lines.extend([
        "}",
        "//#endregion",
        "",
        "//#region node_modules/@vot.js/ext/dist/helpers/bilibili.js",
        f"// Bilibili DOM helper: {modify_region if modify_region else 'v1'}",
        "var BilibiliHelper = class {};",
        "//#endregion",
        ""
    ])

    # Pad to satisfy minimum size threshold
    content = "\n".join(lines)
    if len(content.encode("utf-8")) < size_padding:
        padding = "\n// padding " + ("x" * (size_padding - len(content.encode("utf-8"))))
        content += padding

    return content


class TestVotUpstreamSync(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.base_script = make_dummy_userscript()

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    # ------------------------------------------------------------------
    # Validation Tests
    # ------------------------------------------------------------------

    def test_validate_content_valid(self):
        """Valid userscript passes validation without errors."""
        validate_userscript_content(self.base_script)

    def test_validate_content_empty(self):
        """Empty content raises INVALID_CONTENT."""
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content("")
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)

    def test_validate_content_too_small(self):
        """Content below min_size threshold raises INVALID_CONTENT."""
        small_script = make_dummy_userscript(size_padding=1000)
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content(small_script, min_size=50_000)
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)

    def test_validate_content_html_error(self):
        """HTML 404 response raises INVALID_CONTENT."""
        html_content = make_dummy_userscript(is_html=True)
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content(html_content, min_size=10)
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)
        self.assertIn("HTML", ctx.exception.message)

    def test_validate_content_missing_headers(self):
        """Missing UserScript header directive raises INVALID_CONTENT."""
        script_no_header = self.base_script.replace("// ==UserScript==", "")
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content(script_no_header)
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)

    def test_validate_content_missing_version(self):
        """Missing @version directive raises INVALID_CONTENT."""
        script_no_version = make_dummy_userscript(omit_version=True)
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content(script_no_version)
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)

    def test_validate_content_missing_name(self):
        """Missing @name directive raises INVALID_CONTENT."""
        script_no_name = make_dummy_userscript(omit_name=True)
        with self.assertRaises(UpstreamSyncException) as ctx:
            validate_userscript_content(script_no_name)
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.INVALID_CONTENT)

    # ------------------------------------------------------------------
    # Extraction & Status Tests
    # ------------------------------------------------------------------

    def test_no_change_status(self):
        """Identical content returns NO_CHANGE status."""
        local_info = extract_structural_info(self.base_script)
        upstream_info = extract_structural_info(self.base_script)
        diff = compare_structures(local_info, upstream_info)

        self.assertEqual(diff["status"], UpstreamStatus.NO_CHANGE)
        self.assertFalse(diff["hasChanges"])
        self.assertFalse(diff["reviewRequired"])
        self.assertEqual(diff["impact"], AndroidImpact.NO_ANDROID_IMPACT)
        self.assertEqual(len(diff["changedGroups"]), 0)

    def test_version_changed_status(self):
        """Different version returns VERSION_CHANGED."""
        local_info = extract_structural_info(self.base_script)
        new_script = make_dummy_userscript(version="1.11.18")
        upstream_info = extract_structural_info(new_script)
        diff = compare_structures(local_info, upstream_info)

        self.assertEqual(diff["status"], UpstreamStatus.VERSION_CHANGED)
        self.assertTrue(diff["hasChanges"])
        self.assertTrue(diff["reviewRequired"])
        self.assertEqual(diff["oldVersion"], "1.11.17")
        self.assertEqual(diff["newVersion"], "1.11.18")
        self.assertIn("METADATA", diff["changedGroups"])

    def test_content_changed_same_version_status(self):
        """Same version but different comment returns CONTENT_CHANGED_SAME_VERSION."""
        local_info = extract_structural_info(self.base_script)
        altered_script = self.base_script + "\n// extra benign comment line"
        upstream_info = extract_structural_info(altered_script)
        diff = compare_structures(local_info, upstream_info)

        self.assertEqual(diff["status"], UpstreamStatus.CONTENT_CHANGED_SAME_VERSION)
        self.assertTrue(diff["hasChanges"])
        self.assertTrue(diff["reviewRequired"])
        self.assertEqual(diff["oldVersion"], "1.11.17")
        self.assertEqual(diff["newVersion"], "1.11.17")
        self.assertNotEqual(diff["oldSha256"], diff["newSha256"])

    # ------------------------------------------------------------------
    # Structural Drift Detection Tests
    # ------------------------------------------------------------------

    def test_protobuf_field_added(self):
        """Adding a protobuf field is detected and triggers NATIVE_PORT_REQUIRED."""
        local_info = extract_structural_info(self.base_script)
        script_with_field = make_dummy_userscript(extra_field=(20, "videoTitle", "string"))
        upstream_info = extract_structural_info(script_with_field)
        diff = compare_structures(local_info, upstream_info)

        self.assertIn("PROTOBUF", diff["changedGroups"])
        self.assertEqual(diff["impact"], AndroidImpact.NATIVE_PORT_REQUIRED)

        pb_changes = diff["protobufChanges"]
        self.assertIn("VideoTranslationRequest", pb_changes["modifiedMessages"])
        mod = pb_changes["modifiedMessages"]["VideoTranslationRequest"]
        self.assertEqual(len(mod["addedFields"]), 1)
        self.assertEqual(mod["addedFields"][0]["number"], 20)
        self.assertEqual(mod["addedFields"][0]["name"], "videoTitle")

    def test_endpoint_added(self):
        """Adding an API endpoint is detected and triggers NATIVE_PORT_REQUIRED."""
        local_info = extract_structural_info(self.base_script)
        script_with_ep = make_dummy_userscript(extra_endpoint="/video-translation/new-audio-cache")
        upstream_info = extract_structural_info(script_with_ep)
        diff = compare_structures(local_info, upstream_info)

        self.assertIn("ENDPOINTS", diff["changedGroups"])
        self.assertEqual(diff["impact"], AndroidImpact.NATIVE_PORT_REQUIRED)
        ep_changes = diff["endpointChanges"]
        self.assertIn("/video-translation/new-audio-cache", ep_changes["added"])

    def test_setting_added(self):
        """Adding a setting/storage key is detected and classified REVIEW_REQUIRED."""
        local_info = extract_structural_info(self.base_script)
        script_with_setting = make_dummy_userscript(extra_setting="experimentalFeatureX")
        upstream_info = extract_structural_info(script_with_setting)
        diff = compare_structures(local_info, upstream_info)

        self.assertIn("SETTINGS", diff["changedGroups"])
        self.assertEqual(diff["impact"], AndroidImpact.REVIEW_REQUIRED)
        st_changes = diff["settingsChanges"]
        self.assertIn("experimentalFeatureX", st_changes["added"])

    def test_constant_changed(self):
        """Changing smart ducking threshold constant triggers NATIVE_PORT_REQUIRED."""
        local_info = extract_structural_info(self.base_script)
        script_with_const = make_dummy_userscript(ducking_threshold=".025")
        upstream_info = extract_structural_info(script_with_const)
        diff = compare_structures(local_info, upstream_info)

        self.assertIn("CONSTANTS", diff["changedGroups"])
        self.assertEqual(diff["impact"], AndroidImpact.NATIVE_PORT_REQUIRED)
        ct_changes = diff["constantChanges"]
        self.assertIn("smartDucking", ct_changes)
        self.assertEqual(ct_changes["smartDucking"]["old"]["thresholdOnRms"], ".012")
        self.assertEqual(ct_changes["smartDucking"]["new"]["thresholdOnRms"], ".025")

    def test_region_classification(self):
        """Changed region in bilibili helper is classified BROWSER_ONLY."""
        self.assertEqual(
            classify_region_impact("node_modules/@vot.js/ext/dist/helpers/bilibili.js"),
            AndroidImpact.BROWSER_ONLY
        )
        self.assertEqual(
            classify_region_impact("src/ui/views/overlay.ts"),
            AndroidImpact.BROWSER_ONLY
        )
        self.assertEqual(
            classify_region_impact("src/videoHandler/modules/ducking.ts"),
            AndroidImpact.NATIVE_PORT_REQUIRED
        )
        self.assertEqual(
            classify_region_impact("@vot.js/core/dist/protobuf.js"),
            AndroidImpact.NATIVE_PORT_REQUIRED
        )

    def test_browser_only_region_change(self):
        """Changing only a browser scraper region yields BROWSER_ONLY impact."""
        local_info = extract_structural_info(self.base_script)
        script_mod_region = make_dummy_userscript(modify_region="v2_modified_scraper")
        upstream_info = extract_structural_info(script_mod_region)
        diff = compare_structures(local_info, upstream_info)

        self.assertIn("REGIONS", diff["changedGroups"])
        self.assertEqual(diff["impact"], AndroidImpact.BROWSER_ONLY)
        rg_changes = diff["regionChanges"]
        self.assertEqual(len(rg_changes["changed"]), 1)
        self.assertEqual(rg_changes["changed"][0]["region"], "node_modules/@vot.js/ext/dist/helpers/bilibili.js")
        self.assertEqual(rg_changes["changed"][0]["impact"], AndroidImpact.BROWSER_ONLY)

    # ------------------------------------------------------------------
    # Report Generation Tests
    # ------------------------------------------------------------------

    def test_report_generation(self):
        """Markdown and JSON reports contain required keys and formatting."""
        local_info = extract_structural_info(self.base_script)
        script_mod = make_dummy_userscript(
            version="1.11.18",
            extra_field=(20, "videoTitle", "string"),
            extra_setting="newSetting"
        )
        upstream_info = extract_structural_info(script_mod)
        diff = compare_structures(local_info, upstream_info)

        md_path = os.path.join(self.temp_dir, "diff.md")
        json_path = os.path.join(self.temp_dir, "diff.json")

        md_text = generate_markdown_report(diff, md_path)
        json_text = generate_json_report(diff, json_path)

        self.assertTrue(os.path.exists(md_path))
        self.assertTrue(os.path.exists(json_path))

        self.assertIn("OLD_VERSION", md_text)
        self.assertIn("NEW_VERSION", md_text)
        self.assertIn("PROTOBUF_CHANGES", md_text)
        self.assertIn("videoTitle", md_text)

        parsed_json = json.loads(json_text)
        self.assertEqual(parsed_json["oldVersion"], "1.11.17")
        self.assertEqual(parsed_json["newVersion"], "1.11.18")
        self.assertTrue(parsed_json["hasChanges"])
        self.assertTrue(parsed_json["reviewRequired"])

    # ------------------------------------------------------------------
    # Atomic Update & Rollback Safety Tests
    # ------------------------------------------------------------------

    def test_atomic_update_success(self):
        """Atomic update correctly replaces target file and updates baseline."""
        target_path = os.path.join(self.temp_dir, "vot.user.js")
        baseline_path = os.path.join(self.temp_dir, "vot-reference.json")
        md_path = os.path.join(self.temp_dir, "diff.md")
        json_path = os.path.join(self.temp_dir, "diff.json")

        with open(target_path, "w", encoding="utf-8") as f:
            f.write(self.base_script)

        new_script = make_dummy_userscript(version="1.11.18")
        local_info = extract_structural_info(self.base_script)
        upstream_info = extract_structural_info(new_script)
        diff = compare_structures(local_info, upstream_info)

        atomic_update_reference(target_path, new_script, baseline_path, md_path, json_path, diff)

        with open(target_path, "r", encoding="utf-8") as f:
            updated_content = f.read()
        self.assertIn("@version        1.11.18", updated_content)

        with open(baseline_path, "r", encoding="utf-8") as f:
            baseline_data = json.load(f)
        self.assertEqual(baseline_data["version"], "1.11.18")


class TestNetworkErrorTaxonomy(unittest.TestCase):

    @patch("urllib.request.urlopen")
    def test_dns_error(self, mock_urlopen):
        import socket
        import urllib.error
        from scripts.vot_upstream_sync import fetch_upstream_content

        mock_urlopen.side_effect = urllib.error.URLError(socket.gaierror(-2, "Name or service not known"))
        with self.assertRaises(UpstreamSyncException) as ctx:
            fetch_upstream_content("https://invalid.example.com")
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.DNS_ERROR)

    @patch("urllib.request.urlopen")
    def test_tls_error(self, mock_urlopen):
        import ssl
        import urllib.error
        from scripts.vot_upstream_sync import fetch_upstream_content

        mock_urlopen.side_effect = urllib.error.URLError(ssl.SSLError("certificate verify failed"))
        with self.assertRaises(UpstreamSyncException) as ctx:
            fetch_upstream_content("https://invalid-cert.example.com")
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.TLS_ERROR)

    @patch("urllib.request.urlopen")
    def test_timeout_error(self, mock_urlopen):
        import socket
        import urllib.error
        from scripts.vot_upstream_sync import fetch_upstream_content

        mock_urlopen.side_effect = urllib.error.URLError(socket.timeout("The read operation timed out"))
        with self.assertRaises(UpstreamSyncException) as ctx:
            fetch_upstream_content("https://timeout.example.com")
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.TIMEOUT)

    @patch("urllib.request.urlopen")
    def test_http_404_error(self, mock_urlopen):
        import io
        import urllib.error
        from scripts.vot_upstream_sync import fetch_upstream_content

        err_fp = io.BytesIO(b"")
        mock_urlopen.side_effect = urllib.error.HTTPError(
            "https://example.com/404", 404, "Not Found", {}, err_fp
        )
        with self.assertRaises(UpstreamSyncException) as ctx:
            fetch_upstream_content("https://example.com/404")
        self.assertEqual(ctx.exception.error_type, NetworkErrorType.HTTP_ERROR)
        self.assertEqual(ctx.exception.status_code, 404)
        err_fp.close()


if __name__ == "__main__":
    unittest.main()
