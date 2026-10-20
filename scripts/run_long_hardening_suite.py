#!/usr/bin/env python3
"""
scripts/run_long_hardening_suite.py
Executes the physical TCL long video translation hardening and regression matrix suite.

Target: TCL Smart TV Pro (Android 14 / API34)
Serial: 192.168.2.114:5555
Package: io.github.kiryuhak.smarttubevot.stable
"""

import os
import sys
import time
import subprocess
import csv
import re
import select

SERIAL = "192.168.2.114:5555"
PACKAGE = "io.github.kiryuhak.smarttubevot.stable"
ADB = "/home/kirill/Android/Sdk/platform-tools/adb"
REPORTS_DIR = "build/reports/translation-long-hardening"
os.makedirs(REPORTS_DIR, exist_ok=True)
MATRIX_CSV = os.path.join(REPORTS_DIR, "long-matrix.csv")

TARGET_VIDEOS = [
    # OLD 99% REGRESSION
    {
        "group": "REG_99",
        "index": 1,
        "id": "dQw4w9WgXcQ",
        "title": "Rick Astley - Never Gonna Give You Up",
        "duration": 213,
        "category": "Old 99% Stall Regression (3.5m)",
        "timeout": 60
    },
    {
        "group": "REG_99",
        "index": 2,
        "id": "kJQP7kiw5Fk",
        "title": "Luis Fonsi - Despacito",
        "duration": 282,
        "category": "Old 99% Stall Regression (4.7m)",
        "timeout": 60
    },

    # BUFFERING REGRESSION
    {
        "group": "REG_BUF",
        "index": 3,
        "id": "fJ9rUzIMcZQ",
        "title": "Queen - Bohemian Rhapsody 4K",
        "duration": 359,
        "category": "Buffering Regression 4K (6m)",
        "timeout": 60
    },
    {
        "group": "REG_BUF",
        "index": 4,
        "id": "aircAruvnKk",
        "title": "3Blue1Brown - Neural Networks",
        "duration": 1153,
        "category": "Buffering Regression AV1/VP9 (19m)",
        "timeout": 120
    },
    {
        "group": "REG_BUF",
        "index": 5,
        "id": "WXuK6gekU1Y",
        "title": "AlphaGo Documentary 4K",
        "duration": 5428,
        "category": "Buffering Regression 4K Doc (1h 30m)",
        "timeout": 90
    },

    # LONG MATRIX
    {
        "group": "LONG",
        "index": 6,
        "id": "ZA-tUyM_y7s",
        "title": "MIT Algorithms and Computation",
        "duration": 2880,
        "category": "Long L1 (30-45m / 48m)",
        "timeout": 90
    },
    {
        "group": "LONG",
        "index": 7,
        "id": "kBdfcR-8hEY",
        "title": "Harvard Justice Episode 01",
        "duration": 3300,
        "category": "Long L2 (45-60m / 55m)",
        "timeout": 90
    },
    {
        "group": "LONG",
        "index": 8,
        "id": "kqtD5dpn9C8",
        "title": "Python for Beginners - Learn in 1 Hour",
        "duration": 3600,
        "category": "Long L3 (60-90m / 1h)",
        "timeout": 90
    },
    {
        "group": "LONG",
        "index": 9,
        "id": "L_Guz73e6fw",
        "title": "Lex Fridman Podcast #367 - Sam Altman",
        "duration": 9300,
        "category": "Long L4 (2-3h / 2.6h)",
        "timeout": 120
    },
    {
        "group": "LONG",
        "index": 10,
        "id": "M576WGiDBdQ",
        "title": "freeCodeCamp - Solidity Course",
        "duration": 57600,
        "category": "Long L5 (>4h extreme / 16h)",
        "timeout": 30
    }
]

def adb_cmd(args):
    cmd = [ADB, "-s", SERIAL] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout.strip(), res.returncode

def ensure_screen_active():
    adb_cmd(["shell", "wm", "dismiss-keyguard"])
    adb_cmd(["shell", "input", "keyevent", "82"])

screenshot_taken = False

def run_single(video):
    global screenshot_taken
    vid = video["id"]
    title = video["title"]
    duration = video["duration"]
    cat = video["category"]
    per_video_timeout = video["timeout"]
    grp = video["group"]
    idx = video["index"]

    print(f"\n=======================================================")
    print(f"[{grp} #{idx}] Testing: {vid} - {title}")
    print(f"Category: {cat} | Duration: {duration}s | Timeout: {per_video_timeout}s")
    print(f"=======================================================")

    ensure_screen_active()

    t0 = time.time()
    video_url = f"https://www.youtube.com/watch?v={vid}"
    adb_cmd(["shell", "am", "start", "-a", "android.intent.action.VIEW",
             "-d", video_url,
             "-n", f"{PACKAGE}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])

    time.sleep(4.5)
    startup_ms = int((time.time() - t0) * 1000)

    adb_cmd(["logcat", "-c"])
    logcat_proc = subprocess.Popen(
        [ADB, "-s", SERIAL, "logcat", "-v", "time"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", bufsize=1
    )
    time.sleep(0.5)

    t_start = time.time()
    adb_cmd(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
             "-p", PACKAGE, "--es", "action", "startTranslation"])

    collected_lines = []
    t_first_progress = None
    t_90 = None
    t_99 = None
    t_audio_url = None
    t_russian_audio = None
    t_ducked = None

    fallback_used = False
    route_details = "Direct S3"
    rebuffer_count = 0
    rebuffer_start = None
    max_rebuffer_ms = 0
    is_main_playing = True

    stall_99 = False
    unsupported_detected = False
    error_detected = None
    termination_source = "NONE"
    result = "UNKNOWN"
    failure_category = "NONE"

    deadline = t_start + per_video_timeout

    while time.time() < deadline:
        r, _, _ = select.select([logcat_proc.stdout], [], [], 0.5)
        if not r:
            continue
        line = logcat_proc.stdout.readline()
        if not line:
            break
        collected_lines.append(line)
        now = time.time()

        if "UNSUPPORTED_VIDEO" in line or "duration unsupported" in line:
            unsupported_detected = True
            termination_source = "PROVIDER"
            failure_category = "UNSUPPORTED_DURATION"
            result = "UNSUPPORTED"
            print(f"  [+{now - t_start:.2f}s] FAST-FAIL: UNSUPPORTED_DURATION (>4h limit)")
            break

        if ("progress: 9" in line or "updateRealProgress(9" in line) and not t_90:
            t_90 = now

        if ("progress: 99%" in line or "ATTACHING_STAGE_99" in line) and not t_99:
            t_99 = now
            print(f"  [+{now - t_start:.2f}s] PROGRESS_99")

        if ("VOT translation ready" in line or "source_set url=" in line) and not t_audio_url:
            t_audio_url = now
            if "vot-worker" in line or "audio-proxy" in line:
                route_details = "Proxy Worker"

        if "VOT UI expanded to ready pill" in line or "showActivated" in line:
            if not screenshot_taken:
                screenshot_taken = True
                print(f"  [+{now - t_start:.2f}s] Capturing HUD Success Pill screenshot on TCL...")
                adb_cmd(["shell", "screencap", "-p", "/sdcard/hud_success_pill.png"])
                adb_cmd(["pull", "/sdcard/hud_success_pill.png", os.path.join(REPORTS_DIR, "hud_success_pill.png")])

        if "AUDIBLE_ACTIVE" in line or "audible playback active" in line:
            if not t_russian_audio:
                t_russian_audio = now
                print(f"  [+{now - t_start:.2f}s] AUDIBLE_ACTIVE: Russian audio playing!")

        if "duckMainAudio" in line or "VOT main audio ducked" in line:
            if not t_ducked:
                t_ducked = now
                print(f"  [+{now - t_start:.2f}s] MAIN_AUDIO_DUCKED")

        if "onPlayerStateChanged: true, 2" in line:
            if rebuffer_start is None:
                rebuffer_start = now
                rebuffer_count += 1
        elif "onPlayerStateChanged: true, 3" in line:
            if rebuffer_start is not None:
                dur = int((now - rebuffer_start) * 1000)
                if dur > max_rebuffer_ms:
                    max_rebuffer_ms = dur
                rebuffer_start = None

        if "TRANSLATION_POSTPROCESS_TIMEOUT" in line or "Не удалось подключить перевод" in line:
            stall_99 = True
            termination_source = "APP"
            error_detected = "99% postprocess timeout"
            failure_category = "AUDIO_ATTACH_TIMEOUT"
            result = "FAIL_99_STALL"
            break

        if "TRANSLATION_WATCHDOG_TIMEOUT" in line:
            termination_source = "APP"
            error_detected = "watchdog_timeout"
            failure_category = "PROVIDER_TIMEOUT"
            result = "FAIL_WATCHDOG"
            break

        if t_russian_audio:
            result = "SUCCESS"
            termination_source = "NONE"
            failure_category = "NONE"
            time.sleep(3.0)
            break

    logcat_proc.terminate()
    try:
        logcat_proc.wait(timeout=1.0)
    except Exception:
        logcat_proc.kill()

    total_ms = int((t_russian_audio - t_start) * 1000) if t_russian_audio else int((time.time() - t_start) * 1000)
    time_to_90_ms = int((t_90 - t_start) * 1000) if t_90 else -1
    time_90_to_99_ms = int((t_99 - t_90) * 1000) if (t_99 and t_90) else -1
    time_at_99_ms = int(((t_russian_audio or time.time()) - t_99) * 1000) if t_99 else -1

    if result == "UNKNOWN":
        result = "TIMEOUT"
        termination_source = "HARNESS"
        failure_category = "HARNESS_TIMEOUT"

    print(f"  FINAL RESULT: {result} | Total: {total_ms}ms | TimeAt99: {time_at_99_ms}ms | TermSource: {termination_source}")

    time.sleep(2.0)

    return {
        "group": grp,
        "index": idx,
        "videoId": vid,
        "title": title,
        "durationSec": duration,
        "category": cat,
        "terminationSource": termination_source,
        "routeDetails": route_details,
        "playerStartupMs": startup_ms,
        "translationTotalMs": total_ms,
        "timeTo90Ms": time_to_90_ms,
        "time90To99Ms": time_90_to_99_ms,
        "timeAt99Ms": time_at_99_ms,
        "fallbackUsed": "YES" if fallback_used else "NO",
        "audioUrlReceived": "YES" if t_audio_url else "NO",
        "russianAudioStarted": "YES" if t_russian_audio else "NO",
        "rebufferCount": rebuffer_count,
        "result": result,
        "failureCategory": failure_category
    }

def main():
    print(f"Starting SmartTube VOX Long & Regression Verification Suite on TCL ({SERIAL})...")
    results = []
    for vid in TARGET_VIDEOS:
        res = run_single(vid)
        results.append(res)

    # Write CSV
    fields = [
        "group", "index", "videoId", "title", "durationSec", "category",
        "terminationSource", "routeDetails", "playerStartupMs", "translationTotalMs",
        "timeTo90Ms", "time90To99Ms", "timeAt99Ms", "fallbackUsed",
        "audioUrlReceived", "russianAudioStarted", "rebufferCount", "result", "failureCategory"
    ]
    with open(MATRIX_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(results)

    print(f"\nAll results saved to {MATRIX_CSV}!")

if __name__ == "__main__":
    main()
