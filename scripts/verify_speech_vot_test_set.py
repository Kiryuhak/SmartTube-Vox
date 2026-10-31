#!/usr/bin/env python3
"""
scripts/verify_speech_vot_test_set.py
Robust, bounded speech VOT verification harness for SmartTube VOX.
Enforces:
- Strict per-command ADB timeouts (default: 10s)
- Global wall-clock deadline (default: 180s)
- Bounded polling with interval (default: 1.5s, max 18 attempts)
- Heartbeat logging with timestamps and flush=True
- AVD / QEMU liveness detection (catches user manual stop / crash)
- Stale log rejection via unique session markers
- Mock mode for contract regression testing without AVD or memory usage
"""

import argparse
import os
import signal
import subprocess
import sys
import time
from datetime import datetime

DEFAULT_ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
SERIAL = "emulator-5554"
PACKAGE = "io.github.kiryuhak.smarttubevot.stable"

VOT_SPEECH_TEST_SET = [
    {
        "id": "UF8uR6Z6KLc",
        "title": "Steve Jobs Stanford Commencement Address",
        "duration_sec": 904,
        "language": "en",
        "category": "Keynote / Speech",
        "reason": "Pure coherent monologue keynote speech, crystal clear studio audio, no background music",
        "yandex_vot_supported": True
    },
    {
        "id": "aircAruvnKk",
        "title": "3Blue1Brown - But what is a neural network?",
        "duration_sec": 1153,
        "language": "en",
        "category": "Educational / Technical Lecture",
        "reason": "Technical educational monologue, clear conversational pace, subtle animations without music",
        "yandex_vot_supported": True
    },
    {
        "id": "kqtD5dpn9C8",
        "title": "Programming with Mosh - Python Tutorial for Beginners",
        "duration_sec": 3720,
        "language": "en",
        "category": "Technical Tutorial / Educational",
        "reason": "Conversational coding instruction, studio mic, zero background music or singing",
        "yandex_vot_supported": True
    }
]

OPTIONAL_PLAYBACK_SMOKE_SET = [
    {
        "id": "dQw4w9WgXcQ",
        "title": "Rick Astley - Never Gonna Give You Up",
        "note": "NOT_VOT_QUALITY_EVIDENCE (Music / Prolonged vocal singing)"
    },
    {
        "id": "k2qgadSvNyU",
        "title": "Dua Lipa - New Rules",
        "note": "NOT_VOT_QUALITY_EVIDENCE (Music / Beat / Singing)"
    }
]

T_GLOBAL_START = time.time()

def log(stage: str, msg: str):
    elapsed = time.time() - T_GLOBAL_START
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{ts}][+{elapsed:6.2f}s][{stage:22s}] {msg}", flush=True)

class SpeechVotRunner:
    def __init__(self, adb_path=DEFAULT_ADB, adb_timeout=10, global_timeout=180, per_video_timeout=35,
                 poll_interval=1.5, mock_scenario=None, auto_stop=False):
        self.adb = adb_path
        self.adb_timeout = adb_timeout
        self.global_timeout = global_timeout
        self.per_video_timeout = per_video_timeout
        self.poll_interval = poll_interval
        self.mock_scenario = mock_scenario
        self.auto_stop = auto_stop
        self.interrupted = False
        self.global_deadline = T_GLOBAL_START + global_timeout

    def adb_cmd(self, args, timeout_sec=None):
        if timeout_sec is None:
            timeout_sec = self.adb_timeout

        if self.mock_scenario:
            return self._mock_adb_cmd(args, timeout_sec)

        cmd = [self.adb, "-s", SERIAL] + args
        try:
            res = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                errors="replace",
                timeout=timeout_sec
            )
            return res.stdout.strip(), res.returncode
        except subprocess.TimeoutExpired:
            log("ADB_TIMEOUT", f"Subprocess timed out after {timeout_sec}s: {' '.join(args[:4])}")
            return "", -1
        except Exception as e:
            log("ADB_EXCEPTION", f"Failed executing {' '.join(args[:3])}: {e}")
            return "", -1

    def _mock_adb_cmd(self, args, timeout_sec):
        # Deterministic mock scenarios for unit/contract testing
        arg_str = " ".join(args)
        scenario = self.mock_scenario

        if scenario == "adb_hang":
            time.sleep(0.05)
            log("MOCK_TIMEOUT", f"Simulated ADB hang on command: {arg_str[:30]}")
            return "", -1

        if scenario == "adb_offline":
            if "devices" in args:
                return f"List of devices attached\n{SERIAL}\toffline", 0
            return "error: device offline", 1

        if scenario == "qemu_vanished":
            if "devices" in args:
                return "List of devices attached", 0
            return "error: device not found", 1

        if scenario == "stale_logs":
            if "devices" in args:
                return f"List of devices attached\n{SERIAL}\tdevice", 0
            if "logcat" in args:
                # Return old logs that lack the fresh marker
                return "10-31 01:29:49.781 D VoxSafeLogger: [UI][VOT_UI_READY] VOT UI showing switching to Russian pill\n10-31 01:29:50.424 I TranslationAudioPlayer: VOT_AUDIO session=1 initial_seek_start", 0
            return "", 0

        if scenario == "vot_timeout":
            if "devices" in args:
                return f"List of devices attached\n{SERIAL}\tdevice", 0
            if "logcat" in args:
                return f"VOX_MARKER: {self.current_session_marker}\nPlayback is: 3", 0
            return "", 0

        if scenario == "normal_success":
            if "devices" in args:
                return f"List of devices attached\n{SERIAL}\tdevice", 0
            if "sys.boot_completed" in arg_str:
                return "1", 0
            if "logcat" in args:
                return (
                    f"VOX_MARKER: {getattr(self, 'current_session_marker', 'TEST')}\n"
                    f"D VoxSafeLogger: [UI][VOT_UI_READY] VOT UI showing switching to Russian pill\n"
                    f"I YANDEX_VOT_PLAYBACK: generation=2 sync action=SEEK delta_ms=800\n"
                    f"I TranslationAudioPlayer: VOT_AUDIO session=2 initial_seek_complete\n"
                    f"D VoxSafeLogger: [TRANSLATION][VOT_DUCKING_RELEASED] released_to_baseline\n"
                ), 0
            return "", 0

        return "", 0

    def check_avd_liveness(self):
        """Verifies target AVD is online and responsive via ADB."""
        out, code = self.adb_cmd(["devices"], timeout_sec=5)
        if code != 0:
            return False, "ADB command failed"

        lines = out.splitlines()
        found = False
        state = "missing"
        for line in lines:
            parts = line.split()
            if len(parts) >= 2 and parts[0] == SERIAL:
                found = True
                state = parts[1]
                break

        if not found:
            return False, f"Target serial {SERIAL} is not attached"
        if state != "device":
            return False, f"Target serial {SERIAL} is in non-ready state: {state}"

        return True, "ONLINE"

    def test_single_video(self, video_meta):
        vid = video_meta["id"]
        title = video_meta["title"]
        t_video_start = time.time()
        video_deadline = t_video_start + self.per_video_timeout

        log("VIDEO_START", f"Testing ID: {vid} — {title} ({video_meta['duration_sec']}s)")

        # 1. Preflight Liveness Check
        alive, liveness_reason = self.check_avd_liveness()
        if not alive:
            log("LIVENESS_FAIL", f"AVD not responsive before video start: {liveness_reason}")
            return {
                "id": vid,
                "title": title,
                "status": "INTERRUPTED_AVD_NOT_ALIVE",
                "telemetry_ready": False,
                "telemetry_attached": False,
                "telemetry_ducked": False,
                "audible_confirmed": False,
                "elapsed_sec": round(time.time() - t_video_start, 2),
                "error": liveness_reason
            }

        # 2. Establish Unique Session Marker to Guarantee Log Freshness
        session_marker = f"VOX_RUN_{vid}_{int(time.time() * 1000)}"
        self.current_session_marker = session_marker
        self.adb_cmd(["shell", "wm", "dismiss-keyguard"])
        self.adb_cmd(["shell", "input", "keyevent", "82"]) # Unlock / Menu

        # 3. Launch Video via SplashActivity Intent
        video_url = f"https://www.youtube.com/watch?v={vid}"
        log("INTENT_LAUNCH", f"Sending VIEW intent for {video_url}")
        out, code = self.adb_cmd(["shell", "am", "start", "-a", "android.intent.action.VIEW",
                                 "-d", video_url,
                                 "-n", f"{PACKAGE}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
        if code != 0:
            log("INTENT_FAIL", f"am start failed with code {code}: {out}")
            return {
                "id": vid,
                "title": title,
                "status": "FAIL_LAUNCH",
                "telemetry_ready": False,
                "telemetry_attached": False,
                "telemetry_ducked": False,
                "audible_confirmed": False,
                "elapsed_sec": round(time.time() - t_video_start, 2),
                "error": f"am start failed: {out}"
            }

        # 4. Bounded Player Readiness Wait with Liveness Probe
        player_ready = False
        settle_deadline = min(video_deadline, time.time() + (0.2 if self.mock_scenario else 15.0))
        while time.time() < settle_deadline:
            alive, liveness_reason = self.check_avd_liveness()
            if not alive:
                log("AVD_VANISHED", f"AVD terminated while waiting for player readiness: {liveness_reason}")
                return self._build_result(vid, title, "INTERRUPTED_AVD_VANISHED", t_video_start, error=liveness_reason)
            if self.mock_scenario:
                player_ready = True
                break
            # Check if PlaybackActivity or playback state 3 (PLAYING) is active
            logs, code = self.adb_cmd(["logcat", "-d", "-t", "80"])
            if code == 0 and ("Playback is: 3" in logs or "PlaybackActivity" in logs or "STATE_READY" in logs):
                player_ready = True
                log("PLAYER_READY", f"PlaybackActivity/Player ready detected (+{round(time.time() - t_video_start, 2)}s)")
                break
            time.sleep(0.5)

        if not player_ready and not self.mock_scenario:
            log("PLAYER_WAIT_TIMEOUT", "Player ready marker not detected within window; attempting broadcast anyway")

        # 5. Inject Session Marker and Trigger Translation via Controller Action
        self.adb_cmd(["shell", "log", "-t", "VOX_MARKER", session_marker])
        log("TRIGGER_VOT", f"Triggering translation via startTranslation action (marker={session_marker})")
        self.adb_cmd(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
                      "-p", PACKAGE, "--es", "action", "startTranslation"])

        # 6. Bounded Telemetry Polling Loop
        telemetry_ready = False
        telemetry_attached = False
        telemetry_ducked = False
        poll_count = 0
        max_polls = max(1, int(self.per_video_timeout / self.poll_interval))

        while time.time() < video_deadline and poll_count < max_polls:
            poll_count += 1
            if time.time() > self.global_deadline:
                log("DEADLINE_BREACH", "Global test execution deadline reached!")
                return self._build_result(vid, title, "GLOBAL_TIMEOUT", t_video_start)

            # Check if AVD is still alive before polling logs
            alive, liveness_reason = self.check_avd_liveness()
            if not alive:
                log("AVD_VANISHED", f"AVD terminated mid-test during polling (poll #{poll_count}): {liveness_reason}")
                return self._build_result(vid, title, "INTERRUPTED_AVD_VANISHED", t_video_start, error=liveness_reason)

            # Query recent logcat bounded to last 300 lines
            logs, code = self.adb_cmd(["logcat", "-d", "-t", "300"])
            if code == 0 and logs:
                # Strictly require the fresh session marker
                marker_idx = logs.find(session_marker)
                if marker_idx != -1:
                    fresh_logs = logs[marker_idx:]
                    if ("VOT UI showing switching to Russian pill" in fresh_logs or
                            "VOT UI expanded to ready pill" in fresh_logs):
                        telemetry_ready = True
                    if "VOT_DUCKING" in fresh_logs or "ducking" in fresh_logs.lower():
                        telemetry_ducked = True
                    if "TranslationAudioPlayer: VOT_AUDIO session=" in fresh_logs and (
                            "initial_seek_complete" in fresh_logs or "initial_seek" in fresh_logs):
                        telemetry_attached = True

                    if telemetry_ready and telemetry_attached:
                        log("TELEMETRY_DONE", f"Telemetry verified in poll #{poll_count} (ready={telemetry_ready}, attached={telemetry_attached}, ducked={telemetry_ducked})")
                        break
                else:
                    log("LOG_MARKER_WAIT", f"Poll #{poll_count}: Fresh session marker not yet observed in logcat buffer")

            time.sleep(0.05 if self.mock_scenario else self.poll_interval)

        elapsed = round(time.time() - t_video_start, 2)
        if telemetry_ready and telemetry_attached:
            status = "TELEMETRY_PASS"
        else:
            status = "TIMEOUT_VOT_TELEMETRY"

        log("VIDEO_FINISH", f"Result for {vid}: {status} in {elapsed}s (ready={telemetry_ready}, attached={telemetry_attached}, ducked={telemetry_ducked})")
        return {
            "id": vid,
            "title": title,
            "status": status,
            "telemetry_ready": telemetry_ready,
            "telemetry_attached": telemetry_attached,
            "telemetry_ducked": telemetry_ducked,
            "audible_confirmed": False, # Always requires human ear
            "elapsed_sec": elapsed,
            "error": None if status == "TELEMETRY_PASS" else "Telemetry stage attach/ready did not appear before timeout"
        }

    def _build_result(self, vid, title, status, t_start, error=None):
        return {
            "id": vid,
            "title": title,
            "status": status,
            "telemetry_ready": False,
            "telemetry_attached": False,
            "telemetry_ducked": False,
            "audible_confirmed": False,
            "elapsed_sec": round(time.time() - t_start, 2),
            "error": error or status
        }

    def run_suite(self, videos=None):
        if videos is None:
            videos = VOT_SPEECH_TEST_SET

        log("SUITE_START", f"Starting speech VOT suite ({len(videos)} video(s), global timeout: {self.global_timeout}s, per-video timeout: {self.per_video_timeout}s)")
        results = []

        try:
            for item in videos:
                if time.time() > self.global_deadline:
                    log("SUITE_ABORT", "Global deadline breached before starting next video!")
                    results.append(self._build_result(item["id"], item["title"], "SKIPPED_GLOBAL_TIMEOUT", time.time()))
                    break

                res = self.test_single_video(item)
                results.append(res)

                # If AVD disappeared or global timeout occurred, do not attempt further videos
                if "INTERRUPTED" in res["status"] or "GLOBAL_TIMEOUT" in res["status"]:
                    log("SUITE_HALT", f"Aborting remaining videos due to terminal condition: {res['status']}")
                    break

        finally:
            if self.auto_stop and not self.mock_scenario:
                log("CLEANUP", "Auto-stopping emulator as requested...")
                subprocess.run(["bash", os.path.expanduser("~/Developer/SmartTube-VOX-Private/scripts/linux/vox-emulator-stop.sh")],
                               timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        log("SUITE_COMPLETE", f"Suite finished with {len(results)} processed video(s)")
        return results


def main():
    parser = argparse.ArgumentParser(description="SmartTube VOX Bounded Speech VOT Verification Harness")
    parser.add_argument("--global-timeout", type=int, default=180, help="Global suite deadline in seconds (default: 180)")
    parser.add_argument("--per-video-timeout", type=int, default=35, help="Per-video deadline in seconds (default: 35)")
    parser.add_argument("--adb-timeout", type=int, default=10, help="Individual ADB command timeout (default: 10)")
    parser.add_argument("--poll-interval", type=float, default=1.5, help="Logcat polling interval in seconds (default: 1.5)")
    parser.add_argument("--single-video", type=str, default=None, help="Test only specified video ID (e.g. UF8uR6Z6KLc)")
    parser.add_argument("--auto-stop", action="store_true", help="Stop AVD cleanly on suite exit")
    parser.add_argument("--mock-scenario", type=str, default=None,
                        choices=["normal_success", "adb_hang", "adb_offline", "qemu_vanished", "stale_logs", "vot_timeout"],
                        help="Run in mock mode for contract validation without real AVD or memory")
    args = parser.parse_args()

    videos = VOT_SPEECH_TEST_SET
    if args.single_video:
        matched = [v for v in VOT_SPEECH_TEST_SET if v["id"] == args.single_video]
        if not matched:
            print(f"Error: Unknown single video ID '{args.single_video}'", file=sys.stderr)
            sys.exit(1)
        videos = matched

    runner = SpeechVotRunner(
        adb_timeout=args.adb_timeout,
        global_timeout=args.global_timeout,
        per_video_timeout=args.per_video_timeout,
        poll_interval=args.poll_interval,
        mock_scenario=args.mock_scenario,
        auto_stop=args.auto_stop
    )

    results = runner.run_suite(videos)

    print("\n=======================================================", flush=True)
    print("VOT SPEECH TEST SET EXECUTION SUMMARY", flush=True)
    print("=======================================================", flush=True)
    all_pass = len(results) > 0 and all(r["status"] == "TELEMETRY_PASS" for r in results)
    for r in results:
        err_str = f" [error: {r['error']}]" if r['error'] else ""
        print(f" - [{r['status']}] {r['id']}: {r['title']} (time={r['elapsed_sec']}s, ready={r['telemetry_ready']}, attached={r['telemetry_attached']}){err_str}", flush=True)

    print(f"OVERALL_TELEMETRY: {'PASS' if all_pass else 'FAIL'}", flush=True)
    print(f"HUMAN_AUDIO: NOT_CHECKED (Automated telemetry does NOT evaluate human audibility, naturalness, or quality)", flush=True)
    print(f"VOT_AUDIO_E2E: MANUAL_OWNER_CHECK_REQUIRED", flush=True)

    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
