#!/usr/bin/env python3
import hashlib
import http.server
import json
import os
import sys
import urllib.parse

PORT = int(os.environ.get("OTA_FEED_PORT", 8899))
HOST = "0.0.0.0"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
FIXTURES_DIR = os.path.join(BASE_DIR, "fixtures")

ACTIVE_SCENARIO = os.environ.get("OTA_SCENARIO", "qa_9_2")

def get_asset_info(filename):
    path = os.path.join(FIXTURES_DIR, filename)
    if os.path.exists(path):
        size = os.path.getsize(path)
        sha256 = hashlib.sha256(open(path, "rb").read()).hexdigest()
        return filename, size, sha256
    return filename, 0, ""

class OtaFeedHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        global ACTIVE_SCENARIO
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        scenario = query.get("scenario", [ACTIVE_SCENARIO])[0]
        host_header = self.headers.get("Host", f"127.0.0.1:{PORT}")
        base_url = f"http://{host_header}"

        if path in ("/set_scenario", "/api/scenario"):
            new_scen = query.get("name", [None])[0]
            if new_scen:
                ACTIVE_SCENARIO = new_scen
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"ok": True, "scenario": ACTIVE_SCENARIO}).encode())
                return

        if scenario == "status_404":
            self.send_response(404)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"message": "Not Found"}).encode())
            return

        if scenario == "rate_limit":
            self.send_response(403)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-RateLimit-Remaining", "0")
            self.send_header("X-RateLimit-Limit", "60")
            self.send_header("X-RateLimit-Reset", "1799999999")
            self.end_headers()
            self.wfile.write(json.dumps({
                "message": "API rate limit exceeded for test client",
                "documentation_url": "https://docs.github.com/rest/overview/resources-in-the-rest-api#rate-limiting"
            }).encode())
            return

        if path == "/qa_manifest.json":
            payload = self.build_manifest_payload(scenario, base_url)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if path in ("/releases", "/releases/latest"):
            is_latest = (path == "/releases/latest")
            payload = self.build_release_payload(scenario, is_latest, base_url)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if path.startswith("/assets/"):
            filename = os.path.basename(path)
            file_path = os.path.join(FIXTURES_DIR, filename)
            if os.path.exists(file_path):
                file_size = os.path.getsize(file_path)
                is_partial = (scenario == "partial" or "partial" in query)
                content_len = file_size + 1024000 if is_partial else file_size
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(content_len))
                self.end_headers()
                written = 0
                max_bytes = 1024 * 1024 if is_partial else None
                with open(file_path, "rb") as f:
                    while chunk := f.read(64 * 1024):
                        if max_bytes is not None and written + len(chunk) >= max_bytes:
                            self.wfile.write(chunk[:max_bytes - written])
                            break
                        self.wfile.write(chunk)
                        written += len(chunk)
                return
            else:
                self.send_response(404)
                self.end_headers()
                return

        if path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "scenario": ACTIVE_SCENARIO}).encode())
            return

        self.send_response(404)
        self.end_headers()

    def build_manifest_payload(self, scenario, base_url):
        release_obj = self.get_release_object(scenario, base_url)
        if scenario == "empty_release":
            return json.dumps({}).encode("utf-8")
        manifest = {
            "package": {
                "downloadUrl": release_obj["assets"][0]["browser_download_url"] if release_obj.get("assets") else ""
            },
            release_obj["tag_name"].lstrip("v"): {
                "versionCode": release_obj.get("versionCode", 2446092),
                "changelog": [release_obj["name"]],
                "changelog_ru": [release_obj["name"]]
            }
        }
        return json.dumps(manifest, indent=2).encode("utf-8")

    def build_release_payload(self, scenario, is_latest, base_url):
        if scenario == "empty_release":
            return json.dumps([] if not is_latest else {}).encode("utf-8")

        release = self.get_release_object(scenario, base_url)
        if is_latest:
            return json.dumps(release).encode("utf-8")
        else:
            return json.dumps([release]).encode("utf-8")

    def get_release_object(self, scenario, base_url):
        if scenario in ("qa_9_2", "default"):
            fn, size, sha = get_asset_info("qa_9.2.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Generation 2",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "VOX QA Generation 2 update.\nversionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "qa_9_3":
            fn, size, sha = get_asset_info("qa_9.3.apk")
            return {
                "tag_name": "v32.56-vox.9.3-otaqa.3",
                "name": "SmartTube VOX 9.3 QA Generation 3",
                "prerelease": True,
                "versionCode": 2446093,
                "body": "VOX QA Generation 3 update.\nversionCode: 2446093",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.3-otaqa.3_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario in ("downgrade_9_2", "downgrade_9_1"):
            fn, size, sha = get_asset_info("qa_9.1.apk")
            return {
                "tag_name": "v32.56-vox.9.1-otaqa.1",
                "name": "SmartTube VOX 9.1 QA Generation 1",
                "prerelease": True,
                "versionCode": 2446091,
                "body": "VOX QA Generation 1 update.\nversionCode: 2446091",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.1-otaqa.1_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "no_update_9_3":
            fn, size, sha = get_asset_info("qa_9.3.apk")
            return {
                "tag_name": "v32.56-vox.9.3-otaqa.3",
                "name": "SmartTube VOX 9.3 QA Generation 3",
                "prerelease": True,
                "versionCode": 2446093,
                "body": "VOX QA Generation 3 update.\nversionCode: 2446093",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.3-otaqa.3_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "wrong_checksum":
            fn, size, _ = get_asset_info("qa_9.2.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Wrong Checksum",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size + 99999,
                        "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                    }
                ]
            }
        elif scenario == "wrong_package":
            fn, size, sha = get_asset_info("wrong_package.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Wrong Package",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "wrong_signature":
            fn, size, sha = get_asset_info("wrong_signature.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Wrong Signature",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "corrupt_truncated":
            fn, size, sha = get_asset_info("corrupt_truncated.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Corrupt Truncated",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "corrupt_invalid_zip":
            fn, size, sha = get_asset_info("corrupt_invalid_zip.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Corrupt Invalid Zip",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size,
                        "sha256": sha
                    }
                ]
            }
        elif scenario == "partial":
            fn, size, sha = get_asset_info("qa_9.2.apk")
            return {
                "tag_name": "v32.56-vox.9.2-otaqa.2",
                "name": "SmartTube VOX 9.2 QA Partial Stream",
                "prerelease": True,
                "versionCode": 2446092,
                "body": "versionCode: 2446092",
                "assets": [
                    {
                        "name": "SmartTube_otaqa_32.56-vox.9.2-otaqa.2_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}?partial=1",
                        "size": size + 1024000,
                        "sha256": sha
                    }
                ]
            }
        else:
            return {
                "tag_name": "v32.56-vox.9.1-otaqa.1",
                "name": "SmartTube VOX 9.1",
                "prerelease": False,
                "versionCode": 2446091,
                "assets": []
            }

def run():
    server = http.server.ThreadingHTTPServer((HOST, PORT), OtaFeedHandler)
    print(f"OTA Feed Server running on http://{HOST}:{PORT} (scenario={ACTIVE_SCENARIO})")
    server.serve_forever()

if __name__ == "__main__":
    run()
