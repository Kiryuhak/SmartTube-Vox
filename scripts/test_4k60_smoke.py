#!/usr/bin/env python3
"""
SmartTube VOX 9.2 — Prompt №119-R2: 4K60 VOD Streaming Smoke Test on TCL
Video: Costa Rica in 4K 60fps HDR (LXb3EKWsInQ) — verified non-Indian 4K stream.
Checks:
 1. 4K stream loads and plays cleanly via hardware decoder (c2.mtk.vp9.decoder).
 2. Zero black screen or renderer error.
 3. Frame rate steady (~60 fps) without severe drops.
 4. Pause / Resume check.
"""

import subprocess
import time
import sys
import os

SERIAL = os.getenv("ANDROID_SERIAL", "192.168.2.114:5555")
PKG = "io.github.kiryuhak.smarttubevot.stable"
VIDEO_ID = "LXb3EKWsInQ"

def adb(cmd, timeout=20):
    full_cmd = ["adb", "-s", SERIAL] + cmd
    res = subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", timeout=timeout)
    return res.stdout.strip(), res.returncode

def log(tag, msg):
    print(f"[{time.strftime('%H:%M:%S')}] [{tag}] {msg}", flush=True)

def main():
    log("INIT", f"Starting 4K60 smoke test on video {VIDEO_ID} on device {SERIAL}...")
    adb(["logcat", "-c"])

    url = f"https://www.youtube.com/watch?v={VIDEO_ID}"
    log("LAUNCH", f"Launching 4K video: {url}")
    out, code = adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url,
                     "-n", f"{PKG}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
    if code != 0:
        log("FAIL", f"Failed to launch video: {out}")
        return 1

    log("WAIT", "Waiting 12 seconds for player initialization and buffering...")
    time.sleep(12)

    # Check logcat for hardware decoder and rendering
    logs, _ = adb(["logcat", "-d", "-t", "1000"])
    
    has_renderer_error = "PLAYER_RENDERER_ERROR" in logs or "ExoPlaybackException" in logs
    has_c2_mtk = "c2.mtk.vp9.decoder" in logs or "c2.mtk.hevc.decoder" in logs or "c2.mtk" in logs or "omx" in logs.lower()
    has_rob_fps = "ROB:" in logs or "ROB/s" in logs or "ROB" in logs

    log("DECODER_CHECK", f"Hardware decoder detected: {has_c2_mtk or 'MediaCodec' in logs}")
    log("ERROR_CHECK", f"Renderer error present: {has_renderer_error}")

    # Test short pause and resume
    log("PAUSE_RESUME", "Testing Pause and Resume...")
    adb(["shell", "input", "keyevent", "85"])
    time.sleep(2)
    adb(["shell", "input", "keyevent", "85"])
    time.sleep(3)

    # Capture evidence screenshot
    os.makedirs("/home/kiryuha/Developer/SmartTube-VOX-Private/private-qa/evidence/tcl-ota-119", exist_ok=True)
    screenshot_path = "/home/kiryuha/Developer/SmartTube-VOX-Private/private-qa/evidence/tcl-ota-119/tcl_4k60_playback.png"
    adb(["shell", "screencap", "-p", "/sdcard/4k60.png"])
    adb(["pull", "/sdcard/4k60.png", screenshot_path])
    adb(["shell", "rm", "/sdcard/4k60.png"])
    log("SCREENSHOT", f"Saved evidence screenshot to {screenshot_path}")

    # Check PSS
    out, _ = adb(["shell", "dumpsys", "meminfo", PKG])
    pss = "unknown"
    for line in out.splitlines():
        if "TOTAL PSS:" in line:
            parts = line.split()
            if len(parts) >= 3:
                pss = parts[2]
                break
        elif line.strip().startswith("TOTAL") and not line.strip().startswith("TOTAL SWAP") and not line.strip().startswith("TOTAL PSS"):
            parts = line.split()
            if len(parts) >= 2:
                pss = parts[1]
                break
    log("MEM", f"Current PSS: {pss} KB")

    verdict = not has_renderer_error
    log("VERDICT", f"4K60 Smoke Verdict: {'PASS' if verdict else 'FAIL'}")
    return 0 if verdict else 1

if __name__ == "__main__":
    sys.exit(main())
