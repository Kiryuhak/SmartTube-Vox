#!/usr/bin/env python3
import http.server
import json
import os
import sys
import urllib.parse

PORT = int(os.environ.get("OTA_FEED_PORT", 8899))
HOST = "0.0.0.0"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
FIXTURES_DIR = os.path.join(BASE_DIR, "fixtures")

# Active scenario: skip_9_5, downgrade_9_0, corrupt_truncated, corrupt_invalid_zip, wrong_signature, wrong_package
ACTIVE_SCENARIO = os.environ.get("OTA_SCENARIO", "skip_9_5")

def get_asset_info(filename):
    path = os.path.join(FIXTURES_DIR, filename)
    size = os.path.getsize(path) if os.path.exists(path) else 0
    return filename, size

class OtaFeedHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        global ACTIVE_SCENARIO
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        scenario = query.get("scenario", [ACTIVE_SCENARIO])[0]
        # Resolve host address visible to requester
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

    def build_release_payload(self, scenario, is_latest, base_url):
        if scenario == "skip_9_5":
            if is_latest:
                # 9.5 is a beta prerelease; /releases/latest returns current stable 9.1
                fn, size = get_asset_info("baseline_9.1.apk")
                release = {
                    "tag_name": "v32.56-vox.9.1",
                    "name": "SmartTube VOX 9.1 Stable",
                    "prerelease": False,
                    "assets": [
                        {
                            "name": "SmartTube_vot_32.56-vox.9.1_universal.apk",
                            "browser_download_url": f"{base_url}/assets/{fn}",
                            "size": size
                        }
                    ]
                }
                return json.dumps(release).encode("utf-8")
            fn, size = get_asset_info("probe_skip_9.5.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Probe",
                "prerelease": True,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "downgrade_9_0":
            fn, size = get_asset_info("downgrade_9.0.apk")
            release = {
                "tag_name": "v32.56-vox.9.0",
                "name": "SmartTube VOX 9.0 Release",
                "prerelease": False,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.0_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "corrupt_truncated":
            fn, size = get_asset_info("corrupt_truncated.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Corrupt Truncated",
                "prerelease": not is_latest,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "corrupt_invalid_zip":
            fn, size = get_asset_info("corrupt_invalid_zip.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Corrupt Zip",
                "prerelease": not is_latest,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "wrong_signature":
            fn, size = get_asset_info("wrong_signature.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Wrong Sig",
                "prerelease": not is_latest,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "wrong_package":
            fn, size = get_asset_info("wrong_package.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Wrong Package",
                "prerelease": not is_latest,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}",
                        "size": size
                    }
                ]
            }
        elif scenario == "partial":
            fn, size = get_asset_info("probe_skip_9.5.apk")
            release = {
                "tag_name": "v32.56-vox.9.5",
                "name": "SmartTube VOX 9.5 Partial",
                "prerelease": not is_latest,
                "assets": [
                    {
                        "name": f"SmartTube_vot_32.56-vox.9.5_universal.apk",
                        "browser_download_url": f"{base_url}/assets/{fn}?partial=1",
                        "size": size + 1024000
                    }
                ]
            }
        else:
            release = {"tag_name": "v32.56-vox.9.1", "name": "SmartTube VOX 9.1", "prerelease": False, "assets": []}

        if is_latest:
            return json.dumps(release).encode("utf-8")
        else:
            return json.dumps([release]).encode("utf-8")

def run():
    server = http.server.ThreadingHTTPServer((HOST, PORT), OtaFeedHandler)
    print(f"OTA Feed Server running on http://{HOST}:{PORT} (scenario={ACTIVE_SCENARIO})")
    server.serve_forever()

if __name__ == "__main__":
    run()
