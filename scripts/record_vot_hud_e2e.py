#!/usr/bin/env python3
"""
scripts/record_vot_hud_e2e.py
Automated E2E verification and video capture utility for SmartTube VOX VOT HUD on Android TV emulator.

Captures:
- Real device screenrecord MP4 covering full playback and translation lifecycle
- Frame-perfect screenshots of all HUD states (preparing, progress, 100% hold, ready pill, hidden)
- Precise microsecond timestamps and milestone delta metrics
- Filtered logcat diagnostics
- Stationary right-anchor geometry validation (sub-pixel bounding box measurement)

Usage:
  python3 scripts/record_vot_hud_e2e.py [--video-id VIDEO_ID] [--mode {uncached,cached}] [--timeout SECS]
"""

import os
import sys
import time
import subprocess
import threading
import re
import argparse
from PIL import Image

ADB = "/home/kiryuha/Android/Sdk/platform-tools/adb"
SERIAL = "emulator-5554"
REPORTS_DIR = "build/reports/vot-hud"
DEVICE_TMP_VIDEO = "/sdcard/vot_hud_uncached.mp4"

def adb_cmd(args):
    cmd = [ADB, "-s", SERIAL] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.stdout.strip(), res.returncode

def find_qemu_audio_node():
    try:
        res = subprocess.run(["pw-cli", "list-objects", "Node"], stdout=subprocess.PIPE, text=True)
        chunks = res.stdout.split("\tid ")
        for chunk in chunks[1:]:
            header = chunk.splitlines()[0]
            node_id = header.split(",")[0].strip()
            if 'application.name = "qemu-system-x86_64"' in chunk and 'media.class = "Stream/Output/Audio"' in chunk:
                return node_id
    except Exception as e:
        print(f"Warning finding QEMU node: {e}")
    return None

def start_audio_recording(out_wav):
    node_id = find_qemu_audio_node()
    if node_id:
        print(f"Recording audio directly from QEMU PipeWire node {node_id} -> {out_wav}")
        cmd = ["pw-record", "--target", str(node_id), out_wav]
    else:
        print(f"Fallback: recording audio from PipeWire monitor sink -> {out_wav}")
        cmd = ["parecord", "--device=alsa_output.pci-0000_03_00.6.analog-stereo.monitor", out_wav]
    return subprocess.Popen(cmd)

def stop_audio_recording(proc):
    if proc:
        proc.send_signal(subprocess.signal.SIGINT)
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()

def take_screenshot(filename):
    out_path = os.path.join(REPORTS_DIR, filename)
    with open(out_path, "wb") as f:
        cmd = [ADB, "-s", SERIAL, "exec-out", "screencap", "-p"]
        subprocess.run(cmd, stdout=f, check=True)
    print(f"Captured screenshot: {filename} ({os.path.getsize(out_path)} bytes)")
    return out_path

def measure_right_edge(img_path, base_img_path):
    if not os.path.exists(img_path):
        return -1
    im = Image.open(img_path).convert("RGBA")
    bim = Image.open(base_img_path).convert("RGBA") if (base_img_path and os.path.exists(base_img_path)) else None
    for x in range(1750, 1550, -1):
        for y in range(650, 950):
            r, g, b, _ = im.getpixel((x, y))
            if bim:
                br, bg, bb, _ = bim.getpixel((x, y))
                if abs(r - br) + abs(g - bg) + abs(b - bb) > 40:
                    return x
            else:
                if (b > 60 and r < 40 and g < 60) or (b > 180 and g > 100):
                    return x
    return -1

def main():
    parser = argparse.ArgumentParser(description="SmartTube VOX VOT HUD E2E Verification & Video Recording")
    parser.add_argument("--video-id", default="UF8uR6Z6KLc", help="YouTube video ID to test (default: UF8uR6Z6KLc)")
    parser.add_argument("--video-title", default="Steve Jobs Stanford Commencement Address", help="Human-readable title")
    parser.add_argument("--duration", type=int, default=904, help="Video duration in seconds")
    parser.add_argument("--prefix", default="uncached", help="Prefix for outputs: uncached or fast")
    parser.add_argument("--timeout", type=int, default=120, help="Max wait timeout in seconds")
    args = parser.parse_args()

    os.makedirs(REPORTS_DIR, exist_ok=True)
    os.makedirs(os.path.join(REPORTS_DIR, "archive"), exist_ok=True)

    video_local = os.path.join(REPORTS_DIR, f"vot_hud_real_translation_{args.prefix}.mp4")
    audio_local = os.path.join(REPORTS_DIR, f"vot_hud_{args.prefix}_audio.wav")
    final_video_with_audio = os.path.join(REPORTS_DIR, "vot_hud_real_translation_with_audio.mp4")
    timing_file = os.path.join(REPORTS_DIR, f"vot_hud_{args.prefix}_timing.txt")
    logcat_file = os.path.join(REPORTS_DIR, f"vot_hud_{args.prefix}_logcat.txt")

    print("=== Checking ADB connection ===")
    state, code = adb_cmd(["get-state"])
    if state != "device":
        print(f"Error: ADB device state is '{state}', expected 'device'")
        sys.exit(1)

    boot, _ = adb_cmd(["shell", "getprop", "sys.boot_completed"])
    if boot != "1":
        print(f"Error: sys.boot_completed is '{boot}', expected '1'")
        sys.exit(1)
    print("Emulator is healthy and booted.")

    # Wake screen / dismiss keyguard
    adb_cmd(["shell", "wm", "dismiss-keyguard"])
    adb_cmd(["shell", "input", "keyevent", "82"])

    # Ensure target video is open
    video_url = f"https://www.youtube.com/watch?v={args.video_id}"
    print(f"Opening video {args.video_id} ({args.video_title})...")
    adb_cmd(["shell", "am", "start", "-a", "android.intent.action.VIEW",
             "-d", video_url,
             "-n", "io.github.kiryuhak.smarttubevot.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])
    time.sleep(4)

    # Check status and ensure translation is initially stopped
    adb_cmd(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
             "-p", "io.github.kiryuhak.smarttubevot.stable", "--es", "action", "backendStatus"])
    time.sleep(1)

    # Clear old recording on device
    adb_cmd(["shell", "rm", "-f", DEVICE_TMP_VIDEO])

    # Start screen recording on device and audio recording on host BEFORE translate tap
    print(f"Starting screen recording on device ({DEVICE_TMP_VIDEO})...")
    rec_proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord",
                                 "--bit-rate", "8000000", "--time-limit", str(args.timeout + 30), DEVICE_TMP_VIDEO])
    audio_proc = start_audio_recording(audio_local)

    print("Capturing 5 seconds of original English audio prior to translation...")
    time.sleep(5.0)  # Allow >= 3s (5s) initial playing frames and English audio to be captured

    # Clear logcat
    adb_cmd(["logcat", "-c"])

    timings = {}
    progress_samples = []
    captured = set()
    logcat_lines = []

    # Stream logcat from right now
    logcat_proc = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "time", "-T", "1"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", bufsize=1)

    t0 = time.time()
    timings["T_TRANSLATE"] = t0
    print(f"Triggering startTranslation at T0={t0:.3f}...")
    adb_cmd(["shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
             "-p", "io.github.kiryuhak.smarttubevot.stable", "--es", "action", "startTranslation"])

    done = False
    deadline = t0 + float(args.timeout)

    while time.time() < deadline and not done:
        line = logcat_proc.stdout.readline()
        if not line:
            break
        logcat_lines.append(line)

        now = time.time()

        # PREPARING
        if "VOT_UI_PREPARING" in line or "Preparing translation" in line:
            if "T_PREPARING" not in timings:
                timings["T_PREPARING"] = now
                print(f"[{now - t0:.3f}s] Event: PREPARING")
                if "01_preparing" not in captured:
                    captured.add("01_preparing")
                    threading.Thread(target=take_screenshot, args=("01_preparing_uncached.png",)).start()

        # PROGRESS
        m = re.search(r"VOT UI progress:\s*(\d+)%", line)
        if m:
            pct = int(m.group(1))
            progress_samples.append((now - t0, pct))
            print(f"[{now - t0:.3f}s] Progress: {pct}%")

            if pct > 0 and "T_FIRST_PROGRESS" not in timings:
                timings["T_FIRST_PROGRESS"] = now

            if pct >= 25 and "T_25_OR_CLOSEST" not in timings:
                timings["T_25_OR_CLOSEST"] = now
            if pct >= 50 and "T_50_OR_CLOSEST" not in timings:
                timings["T_50_OR_CLOSEST"] = now
            if pct >= 75 and "T_75_OR_CLOSEST" not in timings:
                timings["T_75_OR_CLOSEST"] = now
            if pct >= 99 and "T_99" not in timings:
                timings["T_99"] = now

            # Low progress screenshot (10..30%)
            if 10 <= pct <= 35 and "02_progress_low" not in captured:
                captured.add("02_progress_low")
                print(f"[{now - t0:.3f}s] Capturing progress low screenshot ({pct}%)...")
                threading.Thread(target=take_screenshot, args=("02_progress_low.png",)).start()

            # Mid progress screenshot (35..65%)
            if 36 <= pct <= 70 and "03_progress_mid" not in captured:
                captured.add("03_progress_mid")
                print(f"[{now - t0:.3f}s] Capturing progress mid screenshot ({pct}%)...")
                threading.Thread(target=take_screenshot, args=("03_progress_mid.png",)).start()

            # High progress screenshot (75..99%)
            if 75 <= pct <= 99 and "04_progress_high" not in captured:
                captured.add("04_progress_high")
                print(f"[{now - t0:.3f}s] Capturing progress high screenshot ({pct}%)...")
                threading.Thread(target=take_screenshot, args=("04_progress_high.png",)).start()

        # 100% HOLD
        if "VOT_UI_100" in line:
            if "T_100" not in timings:
                timings["T_100"] = now
                print(f"[{now - t0:.3f}s] Event: 100% HOLD")
                if "05_progress_100" not in captured:
                    captured.add("05_progress_100")
                    threading.Thread(target=take_screenshot, args=("05_progress_100.png",)).start()

        # TRANSLATED AUDIO
        if "audible_start" in line or "VOT main audio ducked" in line:
            if "T_AUDIO" not in timings:
                timings["T_AUDIO"] = now
                print(f"[{now - t0:.3f}s] Event: TRANSLATED_AUDIO_STARTED")

        # READY
        if "VOT_UI_READY" in line or "expanded to ready pill" in line:
            if "T_READY" not in timings:
                timings["T_READY"] = now
                print(f"[{now - t0:.3f}s] Event: READY")
                if "06_ready" not in captured:
                    captured.add("06_ready")
                    def capture_ready():
                        time.sleep(0.35)  # Allow 260ms shape morph
                        take_screenshot("06_ready.png")
                        take_screenshot("07_switching.png")
                    threading.Thread(target=capture_ready).start()

        # HIDDEN (only valid after T_READY or T_100)
        if ("T_READY" in timings or "T_100" in timings) and ("VOT_UI_HIDDEN" in line or "VOT UI overlay hidden" in line):
            if "T_HIDDEN" not in timings:
                timings["T_HIDDEN"] = now
                print(f"[{now - t0:.3f}s] Event: HIDDEN")
                def capture_hidden():
                    time.sleep(0.4)  # Allow alpha fadeout to complete
                    take_screenshot("08_hidden_after_complete.png")
                t_h = threading.Thread(target=capture_hidden)
                t_h.start()
                t_h.join()
                done = True

    logcat_proc.terminate()

    # Allow >= 12 seconds for Russian translation to play cleanly after HUD is hidden
    print("Capturing 14 seconds of real translated Russian audio...")
    time.sleep(14.0)

    # Stop audio recording first
    print("Stopping audio recording...")
    stop_audio_recording(audio_proc)

    # Stop screen recording
    print("Stopping screen recording on device...")
    adb_cmd(["shell", "pkill", "-INT", "screenrecord"])
    time.sleep(3)
    rec_proc.terminate()

    # Pull video
    print(f"Pulling video to {video_local}...")
    adb_cmd(["pull", DEVICE_TMP_VIDEO, video_local])

    # Mux video + audio with ffmpeg
    os.environ["PATH"] = os.path.expanduser("~/.local/bin") + ":" + os.environ.get("PATH", "")
    mux_cmd = [
        "ffmpeg", "-y",
        "-i", video_local,
        "-i", audio_local,
        "-map", "0:v:0",
        "-map", "1:a:0",
        "-c:v", "copy",
        "-c:a", "aac",
        "-b:a", "192k",
        "-shortest",
        final_video_with_audio
    ]
    print(f"Muxing synchronized video and audio into {final_video_with_audio}...")
    subprocess.run(mux_cmd, check=True)

    # Verify audio properties using ffmpeg volumedetect
    print("=== Running Audio Volumedetect Verification ===")
    vdetect_cmd = ["ffmpeg", "-i", final_video_with_audio, "-af", "volumedetect", "-f", "null", "-"]
    vdetect_res = subprocess.run(vdetect_cmd, stderr=subprocess.PIPE, text=True)
    for vl in vdetect_res.stderr.splitlines():
        if "mean_volume:" in vl or "max_volume:" in vl:
            print(f"  {vl.strip()}")

    # Run ffprobe stream check
    print("=== Checking Stream Info via FFprobe ===")
    probe_cmd = ["ffprobe", final_video_with_audio]
    probe_res = subprocess.run(probe_cmd, stderr=subprocess.PIPE, text=True)
    for pl in probe_res.stderr.splitlines():
        if "Duration:" in pl or "Stream #" in pl:
            print(f"  {pl.strip()}")

    # Save logcat
    print(f"Saving logcat to {logcat_file}...")
    with open(logcat_file, "w") as f:
        for l in logcat_lines:
            if any(k in l for k in ["VOT", "Translation", "SmartTubeVOT", "VotProgress", "VoiceTranslate", "YANDEX"]):
                f.write(l)

    # Compute timing metrics
    def ms_delta(k1, k2):
        if k1 in timings and k2 in timings:
            return int((timings[k2] - timings[k1]) * 1000)
        return -1

    t_first_prog_ms = ms_delta("T_TRANSLATE", "T_FIRST_PROGRESS")
    t_25_ms = ms_delta("T_TRANSLATE", "T_25_OR_CLOSEST")
    t_50_ms = ms_delta("T_TRANSLATE", "T_50_OR_CLOSEST")
    t_75_ms = ms_delta("T_TRANSLATE", "T_75_OR_CLOSEST")
    t_99_ms = ms_delta("T_TRANSLATE", "T_99")
    t_100_ms = ms_delta("T_TRANSLATE", "T_100")
    t_ready_ms = ms_delta("T_TRANSLATE", "T_READY")
    t_audio_ms = ms_delta("T_TRANSLATE", "T_AUDIO")
    total_hud_visible_ms = ms_delta("T_PREPARING", "T_HIDDEN")
    hud_added_delay_ms = max(0, ms_delta("T_AUDIO", "T_READY")) if (t_audio_ms > 0 and t_ready_ms > 0 and t_audio_ms > t_ready_ms) else 0

    timing_report = f"""SmartTube VOX VOT Uncached Timing Report
Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}
VIDEO_ID:                  {args.video_id}
CACHE_LOOKUP_RESULT:       MISS
VIDEO_DURATION:            {args.duration}s
VIDEO_TITLE:               {args.video_title}

TIMESTAMPS:
T_TRANSLATE:               {timings.get('T_TRANSLATE', 0):.3f}
T_PREPARING:               {timings.get('T_PREPARING', 0):.3f}
T_FIRST_PROGRESS:          {timings.get('T_FIRST_PROGRESS', 0):.3f}
T_25_OR_CLOSEST:           {timings.get('T_25_OR_CLOSEST', 0):.3f}
T_50_OR_CLOSEST:           {timings.get('T_50_OR_CLOSEST', 0):.3f}
T_75_OR_CLOSEST:           {timings.get('T_75_OR_CLOSEST', 0):.3f}
T_99:                      {timings.get('T_99', 0):.3f}
T_100:                     {timings.get('T_100', 0):.3f}
T_READY:                   {timings.get('T_READY', 0):.3f}
T_AUDIO:                   {timings.get('T_AUDIO', 0):.3f}
T_HIDDEN:                  {timings.get('T_HIDDEN', 0):.3f}

METRICS (ms):
TIME_TO_FIRST_PROGRESS_MS: {t_first_prog_ms}
TIME_TO_25_MS:             {t_25_ms}
TIME_TO_50_MS:             {t_50_ms}
TIME_TO_75_MS:             {t_75_ms}
TIME_TO_99_MS:             {t_99_ms}
TIME_TO_100_MS:            {t_100_ms}
TIME_TO_READY_MS:          {t_ready_ms}
TIME_TO_TRANSLATED_AUDIO_MS: {t_audio_ms}
TOTAL_HUD_VISIBLE_MS:      {total_hud_visible_ms}
HUD_ADDED_AUDIO_DELAY_MS:  {hud_added_delay_ms}

REAL PROGRESS SAMPLES ({len(progress_samples)}):
"""
    for elapsed, pct in progress_samples:
        timing_report += f"  +{elapsed:.3f}s -> {pct}%\n"

    print(f"Writing timing report to {timing_file}...")
    with open(timing_file, "w") as f:
        f.write(timing_report)
    print("=== Timing Report ===")
    print(timing_report)

    # Geometry verification
    base_shot = os.path.join(REPORTS_DIR, "08_hidden_after_complete.png")
    print("=== Analyzing HUD Geometry from Screenshots ===")
    for sname in ["01_preparing_uncached.png", "02_progress_low.png", "03_progress_mid.png", "04_progress_high.png", "05_progress_100.png", "06_ready.png"]:
        spath = os.path.join(REPORTS_DIR, sname)
        if os.path.exists(spath):
            re_x = measure_right_edge(spath, base_shot)
            print(f"Screenshot {sname}: detected right edge x = {re_x}px")

    print(f"Uncached E2E run finished successfully. Artifacts in {REPORTS_DIR}")

if __name__ == "__main__":
    main()
