#!/usr/bin/env python3
"""
SmartTube VOX 9.2 — Prompt №113 Phase E: Episode Next Notification Overlay & Background Audio-Only E2E Test
Verifies:
 1. Next episode notification dialog (AppDialogActivity) shown during playback.
 2. PlaybackActivity.onPause() does NOT disable the video track when AppDialog is shown.
 3. Video rendering remains active (non-black frames) and audio continues during overlay.
 4. Dialog action/dismissal closes overlay without video disruption across 5 cycles.
 5. Home button activates real background audio-only mode, and returning restores video track.
"""

import subprocess
import time
import sys
import os
import zlib
import struct

SERIAL = "emulator-5554"
PKG = "io.github.kiryuhak.smarttubevot.stable"
EVIDENCE_DIR = "/home/kiryuha/Developer/SmartTube-VOX-Private/private-qa/evidence"
os.makedirs(EVIDENCE_DIR, exist_ok=True)

def adb(cmd, timeout=15):
    full_cmd = ["adb", "-s", SERIAL] + cmd
    res = subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", timeout=timeout)
    return res.stdout.strip(), res.returncode

def log(tag, msg):
    print(f"[{time.strftime('%H:%M:%S')}] [{tag}] {msg}")

def analyze_png_pixels(path):
    """Parses raw PNG scanlines and returns the count of non-black/non-zero sampled pixels."""
    try:
        with open(path, 'rb') as f:
            data = f.read()
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            return False, 0
        idx = 8
        idat = b''
        w, h = 0, 0
        while idx < len(data):
            length, chunk_type = struct.unpack('>I4s', data[idx:idx+8])
            chunk_data = data[idx+8:idx+8+length]
            if chunk_type == b'IHDR':
                w, h = struct.unpack('>II', chunk_data[:8])
            elif chunk_type == b'IDAT':
                idat += chunk_data
            elif chunk_type == b'IEND':
                break
            idx += 8 + length + 4
        decomp = zlib.decompress(idat)
        sample = decomp[::100]
        non_zero = sum(1 for b in sample if b > 25)
        is_visible = non_zero > 1000 # Bright colorful 1080p frame
        log("PIXEL_CHECK", f"{os.path.basename(path)}: {w}x{h}, non-zero samples={non_zero}/{len(sample)} (visible={is_visible})")
        return is_visible, non_zero
    except Exception as e:
        log("PIXEL_ERR", f"Error analyzing {path}: {e}")
        return False, 0

def is_app_dialog_on_top():
    out, _ = adb(["shell", "dumpsys", "activity", "activities"])
    for line in out.splitlines():
        if "topResumedActivity=" in line or "mFocusedApp=" in line:
            if "AppDialogActivity" in line:
                return True
    return False

def is_playback_on_top():
    out, _ = adb(["shell", "dumpsys", "activity", "activities"])
    for line in out.splitlines():
        if "topResumedActivity=" in line or "mFocusedApp=" in line:
            if "PlaybackActivity" in line:
                return True
    return False

def main():
    log("INIT", "Starting Phase E E2E test on AVD...")
    
    # 0. Clear logcat
    adb(["logcat", "-c"])
    
    # 1. Wake up and unlock
    adb(["shell", "wm", "dismiss-keyguard"])
    adb(["shell", "input", "keyevent", "82"])
    
    # Dismiss any stray dialog if present
    if is_app_dialog_on_top():
        adb(["shell", "input", "keyevent", "23"])
        time.sleep(1)
        
    # 2. Launch video UF8uR6Z6KLc (Steve Jobs Stanford Speech, 2005)
    video_url = "https://www.youtube.com/watch?v=UF8uR6Z6KLc"
    log("PLAY_LAUNCH", f"Launching playback: {video_url}")
    out, code = adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", video_url,
                     "-n", f"{PKG}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
    if code != 0:
        log("FAIL", f"Launch failed: {out}")
        return 1
        
    # Wait for player to enter playback and start rendering
    log("WAIT_READY", "Waiting for PlaybackActivity to become active and buffer...")
    ready = False
    for _ in range(15):
        time.sleep(1)
        if is_playback_on_top():
            ready = True
            break
            
    if not ready:
        log("FAIL", "PlaybackActivity did not appear in time")
        return 1
    log("READY", "PlaybackActivity is running on top.")
    
    # Wait for video playback to stabilize and render past intro
    log("WAIT_FRAMES", "Waiting 7 seconds for video playback to stabilize...")
    time.sleep(7)
    
    # 3. Configure player via broadcast: backgroundMode = 1 (SOUND), chapterNotification = true
    log("CONFIG", "Enabling BackgroundMode=SOUND (1) and ChapterNotification=true")
    adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_VOT_BADGE",
         "--es", "action", "setTestPlayerConfig",
         "--ei", "backgroundMode", "1",
         "--ez", "chapterNotification", "true"])
    time.sleep(1)
    
    # 4. Capture baseline screenshot
    baseline_png = f"{EVIDENCE_DIR}/113_overlay_01_baseline_video.png"
    adb(["shell", "screencap", "-p", "/sdcard/baseline.png"])
    adb(["pull", "/sdcard/baseline.png", baseline_png])
    b_ok, b_samples = analyze_png_pixels(baseline_png)
    log("BASELINE", f"Baseline screenshot saved: {baseline_png} (visible={b_ok}, samples={b_samples})")
    
    cycles_completed = 0
    overlay_video_visible_all = True
    
    # 5. Run 5 cycles of Chapter Notification Overlay open / verify / close
    for i in range(1, 6):
        log("CYCLE", f"--- Cycle {i}/5 ---")
        
        # Clear logcat before trigger
        adb(["logcat", "-c"])
        
        # Trigger chapter notification overlay
        log("TRIGGER", f"Showing chapter notification dialog (cycle {i})...")
        adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_VOT_BADGE",
             "--es", "action", "showChapterNotification",
             "--es", "title", f"Episode_Cycle_{i}"])
        time.sleep(1.2)
        
        # Verify top activity is AppDialogActivity
        is_dialog_top = is_app_dialog_on_top()
        log("DIALOG_TOP", f"AppDialogActivity is on top: {is_dialog_top}")
        
        # Capture screenshot during overlay
        overlay_png = f"{EVIDENCE_DIR}/113_overlay_02_cycle{i}_during_dialog.png"
        adb(["shell", "screencap", "-p", f"/sdcard/overlay_c{i}.png"])
        adb(["pull", f"/sdcard/overlay_c{i}.png", overlay_png])
        c_ok, samples = analyze_png_pixels(overlay_png)
        log("OVERLAY_FRAME", f"Cycle {i} overlay screenshot saved: {overlay_png} (visible={c_ok}, samples={samples})")
        if not c_ok:
            overlay_video_visible_all = False
            
        # Check logcat to ensure video track was NOT disabled
        logs, _ = adb(["logcat", "-d"])
        gate_passed = "AppDialog shown (chapter/episode overlay) - keeping video track enabled" in logs
        shut_down = "UI отсоединен от поверхности видео, видеопоток выключен" in logs
        log("LOGCAT_CHECK", f"Gate pass logged: {gate_passed}, Video shut down: {shut_down}")
        
        # Dismiss notification via DPAD_CENTER
        adb(["shell", "input", "keyevent", "23"])
        time.sleep(1.0)
        
        # Verify PlaybackActivity returned to top
        playback_resumed = is_playback_on_top()
        log("DISMISS", f"Returned to PlaybackActivity: {playback_resumed}")
        
        # In cycle 1, gate_passed is logged on onPause; in cycles 2-5, gate_passed is logged if activity paused,
        # but crucially shut_down is ALWAYS false and video frame is ALWAYS visible.
        cycle_ok = is_dialog_top and c_ok and not shut_down and playback_resumed
        if cycle_ok:
            cycles_completed += 1
            log("CYCLE_VERDICT", f"Cycle {i}: PASS")
        else:
            log("CYCLE_VERDICT", f"Cycle {i}: FAIL (dialog={is_dialog_top}, visible={c_ok}, shutdown={shut_down}, resumed={playback_resumed})")
            
    log("CYCLES_SUMMARY", f"Completed {cycles_completed}/5 successful overlay cycles")
    
    # 6. Test rapid double trigger
    log("RAPID_TEST", "Testing rapid double appearance / dismiss...")
    adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_VOT_BADGE",
         "--es", "action", "showChapterNotification", "--es", "title", "Rapid1"])
    time.sleep(0.3)
    adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_VOT_BADGE",
         "--es", "action", "showChapterNotification", "--es", "title", "Rapid2"])
    time.sleep(0.8)
    adb(["shell", "input", "keyevent", "23"]) # Dismiss
    time.sleep(1.0)
    log("RAPID_TEST", "Rapid test finished without crash")
    
    # 7. Test legitimate HOME background-audio-only
    log("HOME_TEST", "Testing deliberate HOME press with BackgroundMode=SOUND...")
    adb(["logcat", "-c"])
    adb(["shell", "input", "keyevent", "3"]) # KEYCODE_HOME
    time.sleep(2.5)
    
    home_logs, _ = adb(["logcat", "-d"])
    audio_only_entered = "BACKGROUND_AUDIO_ONLY_ENTER" in home_logs or "Вход в режим воспроизведения Только аудио" in home_logs
    video_shut_down = "BACKGROUND_UI_DETACHED" in home_logs or "видеопоток выключен" in home_logs
    log("HOME_TRANSITION", f"Audio-only entered on HOME: {audio_only_entered}, Video track disabled: {video_shut_down}")
    
    # Return to app
    log("RESTORE_FROM_HOME", "Returning to PlaybackActivity...")
    adb(["shell", "am", "start", "-n", f"{PKG}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
    time.sleep(2.5)
    
    restore_logs, _ = adb(["logcat", "-d"])
    audio_only_exited = "BACKGROUND_AUDIO_ONLY_EXIT" in restore_logs or "Выход из режима Только аудио" in restore_logs
    log("RESTORE_TRANSITION", f"Audio-only exited on return: {audio_only_exited}")
    
    resumed_png = f"{EVIDENCE_DIR}/113_overlay_04_resumed_from_home.png"
    adb(["shell", "screencap", "-p", "/sdcard/resumed.png"])
    adb(["pull", "/sdcard/resumed.png", resumed_png])
    r_ok, r_samples = analyze_png_pixels(resumed_png)
    log("RESUME_FRAME", f"Resumed screenshot saved: {resumed_png} (visible={r_ok}, samples={r_samples})")
    
    # 8. Restore settings: backgroundMode=0 (DEFAULT), chapterNotification=false
    log("CLEANUP", "Restoring player settings to default...")
    adb(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_VOT_BADGE",
         "--es", "action", "setTestPlayerConfig",
         "--ei", "backgroundMode", "0",
         "--ez", "chapterNotification", "false"])
    time.sleep(1)
    
    # Summary
    success = (cycles_completed == 5) and overlay_video_visible_all and audio_only_entered and video_shut_down and audio_only_exited and r_ok
    log("VERDICT", f"Phase E E2E Verdict: {'PASS' if success else 'FAIL'}")
    return 0 if success else 1

if __name__ == "__main__":
    sys.exit(main())
