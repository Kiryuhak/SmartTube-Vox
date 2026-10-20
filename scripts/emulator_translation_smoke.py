#!/usr/bin/env python3
"""
scripts/emulator_translation_smoke.py
Verifies the 5 translation smoke scenarios on VOX_TV_API34 emulator (emulator-5554).
"""

import os
import sys
import time
import subprocess
import select

SERIAL = "emulator-5554"
PACKAGE = "io.github.kiryuhak.smarttubevot.stable"
ADB = "/home/kirill/Android/Sdk/platform-tools/adb"

TARGETS = [
    {
        "id": "k2qgadSvNyU",
        "tag": "T1",
        "name": "Short Foreign (Dua Lipa)",
        "timeout": 45
    },
    {
        "id": "dQw4w9WgXcQ",
        "tag": "T2",
        "name": "Old Problematic (Rick Astley)",
        "timeout": 45
    },
    {
        "id": "kJQP7kiw5Fk",
        "tag": "T3",
        "name": "Old Problematic (Despacito)",
        "timeout": 45
    },
    {
        "id": "aircAruvnKk",
        "tag": "T4",
        "name": "Buffering/Complex (3Blue1Brown)",
        "timeout": 45
    },
    {
        "id": "kqtD5dpn9C8",
        "tag": "T5",
        "name": "Long ~1h (Python Tutorial)",
        "timeout": 45
    }
]

def adb(args):
    cmd = [ADB, "-s", SERIAL] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout.strip(), res.returncode

def dismiss_keyguard():
    adb(["shell", "wm", "dismiss-keyguard"])
    adb(["shell", "input", "keyevent", "82"])

def run_test(target):
    tag = target["tag"]
    vid = target["id"]
    name = target["name"]
    timeout = target["timeout"]

    print(f"\n=======================================================")
    print(f"[{tag}] Testing on Emulator: {vid} ({name})")
    print(f"=======================================================")

    dismiss_keyguard()

    # Launch video
    video_url = f"https://www.youtube.com/watch?v={vid}"
    adb(["shell", "am", "start", "-a", "android.intent.action.VIEW",
         "-d", video_url,
         "-n", f"{PACKAGE}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])

    time.sleep(4.0)

    # Clear logcat
    adb(["logcat", "-c"])
    logcat = subprocess.Popen(
        [ADB, "-s", SERIAL, "logcat", "-v", "time"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", bufsize=1
    )
    time.sleep(0.5)

    t_start = time.time()
    adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
         "-p", PACKAGE, "--es", "action", "startTranslation"])

    t_99 = None
    t_audible = None
    stall_99 = False
    error_detected = None
    deadline = t_start + timeout

    while time.time() < deadline:
        r, _, _ = select.select([logcat.stdout], [], [], 0.5)
        if not r:
            continue
        line = logcat.stdout.readline()
        if not line:
            break
        now = time.time()

        if "ATTACHING_STAGE_99" in line or "progress: 99%" in line:
            if not t_99:
                t_99 = now
                print(f"  [+{now - t_start:.2f}s] Stage: 99% (attaching)")

        if "audible playback active" in line or "AUDIBLE_ACTIVE" in line or "VOT main audio ducked" in line:
            if not t_audible:
                t_audible = now
                print(f"  [+{now - t_start:.2f}s] Audible: Russian audio active & ducked!")
                time.sleep(2.0)
                break

        if "TRANSLATION_POSTPROCESS_TIMEOUT" in line:
            stall_99 = True
            error_detected = "99% postprocess timeout"
            break

        if "TRANSLATION_WATCHDOG_TIMEOUT" in line:
            error_detected = "Watchdog timeout"
            break

    logcat.terminate()
    try:
        logcat.wait(timeout=1.0)
    except Exception:
        logcat.kill()

    total_ms = int((t_audible - t_start) * 1000) if t_audible else int((time.time() - t_start) * 1000)
    at_99_ms = int((t_audible - t_99) * 1000) if (t_audible and t_99) else 0

    if t_audible and not stall_99:
        print(f"  Result: PASS | Total: {total_ms}ms | Time at 99%: {at_99_ms}ms")
        return {"tag": tag, "id": vid, "result": "PASS", "latency": total_ms, "timeAt99": at_99_ms}
    else:
        err = error_detected or "TIMEOUT"
        print(f"  Result: FAIL ({err}) | Total: {total_ms}ms")
        return {"tag": tag, "id": vid, "result": "FAIL", "latency": total_ms, "error": err}

def main():
    print("Starting Translation Smoke on Android TV Emulator (emulator-5554)...")
    results = []
    for target in TARGETS:
        res = run_test(target)
        results.append(res)
        time.sleep(1.5)

    print("\n================ SUMMARY ================")
    for r in results:
        print(f"{r['tag']} ({r['id']}): {r['result']} ({r.get('latency', 0)}ms)")
    all_pass = all(r["result"] == "PASS" for r in results)
    print("=========================================")
    if not all_pass:
        sys.exit(1)
    print("ALL 5 EMULATOR SMOKE TESTS PASSED!")

if __name__ == "__main__":
    main()
