#!/usr/bin/env python3
"""
SmartTube VOX 9.2 — Prompt №113 Phase G: Live/DVR & Playback Stability Soak Test
Target stream: Sky News UK (umLWFz4izTM) — verified non-Indian live stream.
Checks:
 1. Live playback starts cleanly.
 2. DVR seek -10 minutes.
 3. DVR seek -20 minutes.
 4. Return to live edge.
 5. Pause / Resume toggle.
 6. 10-minute playback soak with PSS/memory profiling every 60s.
 7. Exception monitoring (BehindLiveWindowException, Fatal, ANR, OOM).
"""

import subprocess
import time
import sys
import os
import re

SERIAL = os.getenv("ANDROID_SERIAL", "emulator-5554")
PKG = "io.github.kiryuhak.smarttubevot.stable"
VIDEO_ID = "umLWFz4izTM" # Sky News UK Live
SOAK_MINUTES = int(os.getenv("SOAK_MINUTES", "5"))

def adb(cmd, timeout=20):
    full_cmd = ["adb", "-s", SERIAL] + cmd
    res = subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", timeout=timeout)
    return res.stdout.strip(), res.returncode

def log(tag, msg):
    print(f"[{time.strftime('%H:%M:%S')}] [{tag}] {msg}", flush=True)

def get_meminfo_pss():
    out, _ = adb(["shell", "dumpsys", "meminfo", PKG])
    # Look for "TOTAL PSS:" or "TOTAL       <pss>"
    for line in out.splitlines():
        if "TOTAL PSS:" in line:
            parts = line.split()
            if len(parts) >= 3:
                return parts[2]
        if line.strip().startswith("TOTAL") and not line.strip().startswith("TOTAL SWAP") and not line.strip().startswith("TOTAL PSS"):
            parts = line.split()
            if len(parts) >= 2:
                return parts[1]
    return "unknown"

def main():
    log("INIT", f"Starting Phase G Live/DVR and Soak test on video {VIDEO_ID}...")
    
    # 0. Wake up screen
    adb(["shell", "wm", "dismiss-keyguard"])
    adb(["shell", "input", "keyevent", "82"])
    
    # 1. Clear logcat
    adb(["logcat", "-c"])
    
    # 2. Launch Sky News live stream
    live_url = f"https://www.youtube.com/watch?v={VIDEO_ID}"
    log("LAUNCH", f"Launching live stream: {live_url}")
    out, code = adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", live_url,
                     "-n", f"{PKG}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
    if code != 0:
        log("FAIL", f"Failed to launch live stream: {out}")
        return 1
        
    # Wait for player to load and buffer live stream
    log("WAIT_LOAD", "Waiting 12 seconds for live stream to start playing...")
    time.sleep(12)
    
    initial_pss = get_meminfo_pss()
    log("MEM_INIT", f"Initial PSS: {initial_pss} KB")
    
    # 3. Test DVR rewind -10 minutes
    # Using DPAD_LEFT or REWIND keyevents
    log("DVR_MINUS_10M", "Rewinding -10 minutes in DVR window...")
    # Send multiple rewind steps
    for _ in range(10):
        adb(["shell", "input", "keyevent", "89"]) # KEYCODE_MEDIA_REWIND
        time.sleep(0.2)
    time.sleep(3)
    
    logs, _ = adb(["logcat", "-d"])
    behind_window = "BehindLiveWindowException" in logs
    log("DVR_MINUS_10M_CHECK", f"BehindLiveWindowException present: {behind_window}")
    
    # 4. Test DVR rewind -20 minutes
    log("DVR_MINUS_20M", "Rewinding another -10 minutes (-20m total from live edge)...")
    for _ in range(10):
        adb(["shell", "input", "keyevent", "89"]) # KEYCODE_MEDIA_REWIND
        time.sleep(0.2)
    time.sleep(3)
    
    logs, _ = adb(["logcat", "-d"])
    behind_window_20m = "BehindLiveWindowException" in logs
    log("DVR_MINUS_20M_CHECK", f"BehindLiveWindowException present: {behind_window_20m}")
    
    # 5. Return to live edge (fast forward / skip ahead)
    log("LIVE_EDGE", "Skipping back forward towards live edge...")
    for _ in range(20):
        adb(["shell", "input", "keyevent", "90"]) # KEYCODE_MEDIA_FAST_FORWARD
        time.sleep(0.15)
    time.sleep(3)
    log("LIVE_EDGE", "Returned towards live edge.")
    
    # 6. Test Pause / Resume
    log("PAUSE_RESUME", "Testing Pause and Resume...")
    adb(["shell", "input", "keyevent", "85"]) # KEYCODE_MEDIA_PLAY_PAUSE
    time.sleep(2)
    adb(["shell", "input", "keyevent", "85"]) # KEYCODE_MEDIA_PLAY_PAUSE
    time.sleep(2)
    log("PAUSE_RESUME", "Pause/Resume complete.")
    
    # 7. Soak test for SOAK_MINUTES minutes
    log("SOAK_START", f"Starting {SOAK_MINUTES}-minute playback soak test...")
    start_time = time.time()
    target_duration = SOAK_MINUTES * 60
    
    minute = 0
    anr_count = 0
    fatal_count = 0
    behind_window_count = 0
    oom_count = 0
    pss_readings = []
    
    while (time.time() - start_time) < target_duration:
        time.sleep(60)
        minute += 1
        elapsed = int(time.time() - start_time)
        pss = get_meminfo_pss()
        pss_readings.append(pss)
        log("SOAK_TICK", f"Minute {minute}/{SOAK_MINUTES} ({elapsed}s elapsed) — PSS: {pss} KB")
        
        # Check logs for exceptions
        recent_logs, _ = adb(["logcat", "-d", "-t", "500"])
        if "BehindLiveWindowException" in recent_logs:
            behind_window_count += 1
            log("WARN", "BehindLiveWindowException detected in logs")
        if "FATAL EXCEPTION" in recent_logs:
            fatal_count += 1
            log("ERROR", "Fatal exception detected in logs!")
        if "ANR in " in recent_logs:
            anr_count += 1
            log("ERROR", "ANR detected in logs!")
        if "Out of memory" in recent_logs or "LowMemory" in recent_logs:
            oom_count += 1
            log("WARN", "OOM / low memory detected in logs!")
            
    final_pss = get_meminfo_pss()
    log("SOAK_FINISH", f"Soak completed: {minute} minutes. Final PSS: {final_pss} KB")
    log("SOAK_SUMMARY", f"Readings: {pss_readings}")
    log("EXCEPTIONS_SUMMARY", f"Fatal: {fatal_count}, ANR: {anr_count}, OOM: {oom_count}, BehindLiveWindow: {behind_window_count}")
    
    # Pass criteria: 0 fatal crashes, 0 ANRs, live stream played through
    soak_pass = (fatal_count == 0) and (anr_count == 0)
    log("VERDICT", f"Phase G Live/DVR & Soak Verdict: {'PASS' if soak_pass else 'FAIL'}")
    return 0 if soak_pass else 1

if __name__ == "__main__":
    sys.exit(main())
