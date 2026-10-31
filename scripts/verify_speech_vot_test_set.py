#!/usr/bin/env python3
"""
scripts/verify_speech_vot_test_set.py
Dedicated Speech VOT Test Suite for SmartTube VOX.
Focuses strictly on pure conversational/speech English content.
Musical clips (dQw4w9WgXcQ, k2qgadSvNyU) are isolated to optional player smoke.
"""

import subprocess
import time
import json
import sys

ADB = "/home/kiryuha/Android/Sdk/platform-tools/adb"
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

def adb_cmd(args):
    cmd = [ADB, "-s", SERIAL] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace")
    return res.stdout.strip(), res.returncode

def test_speech_video(item):
    vid = item["id"]
    print(f"\n=======================================================")
    print(f"Testing Speech Video: {vid} - {item['title']}")
    print(f"Category: {item['category']} | Duration: {item['duration_sec']}s")
    print(f"=======================================================")

    adb_cmd(["shell", "wm", "dismiss-keyguard"])
    adb_cmd(["shell", "input", "keyevent", "82"])

    # Clear logcat
    adb_cmd(["logcat", "-c"])

    # Launch video
    video_url = f"https://www.youtube.com/watch?v={vid}"
    adb_cmd(["shell", "am", "start", "-a", "android.intent.action.VIEW",
             "-d", video_url,
             "-n", f"{PACKAGE}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])

    # Wait for player start
    time.sleep(6)

    # Trigger translation via broadcast
    t0 = time.time()
    adb_cmd(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
             "-p", PACKAGE, "--es", "action", "startTranslation"])

    # Poll logcat for readiness & attach
    ready = False
    ducked = False
    attached = False
    drift_seek = False
    deadline = t0 + 25

    while time.time() < deadline:
        logs, _ = adb_cmd(["logcat", "-d", "-t", "80"])
        for line in logs.splitlines():
            if "VOT UI showing switching to Russian pill" in line or "VOT UI expanded to ready pill" in line:
                ready = True
            if "VOT_DUCKING" in line or "ducking" in line.lower():
                ducked = True
            if "TranslationAudioPlayer: VOT_AUDIO session=" in line and "initial_seek" in line:
                attached = True
            if "drift_seek" in line or "sync action=" in line:
                drift_seek = True
        if ready and attached:
            break
        time.sleep(1)

    elapsed = time.time() - t0
    status = "PASS" if (ready and attached) else "FAIL"
    print(f"Result for {vid}: {status} in {elapsed:.1f}s (ready={ready}, ducked={ducked}, attached={attached}, drift_seek={drift_seek})")
    return {
        "id": vid,
        "title": item["title"],
        "category": item["category"],
        "duration_sec": item["duration_sec"],
        "status": status,
        "ready": ready,
        "ducked": ducked,
        "attached": attached,
        "elapsed_sec": round(elapsed, 2)
    }

def main():
    results = []
    for item in VOT_SPEECH_TEST_SET:
        res = test_speech_video(item)
        results.append(res)
    print("\n=======================================================")
    print("VOT SPEECH TEST SET EXECUTION SUMMARY")
    print("=======================================================")
    all_pass = all(r["status"] == "PASS" for r in results)
    for r in results:
        print(f" - [{r['status']}] {r['id']}: {r['title']} (attach={r['attached']}, time={r['elapsed_sec']}s)")
    print(f"OVERALL: {'PASS' if all_pass else 'FAIL'}")
    return 0 if all_pass else 1

if __name__ == "__main__":
    sys.exit(main())
