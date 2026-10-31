#!/usr/bin/env python3
# ==============================================================================
# SmartTube VOX — Bounded Raw VOT Audio Capture & Forensic Inspector
# Implements strict finite deadlines, redaction of signed URLs/secrets,
# secure temporary storage (0700/0600), and canonical AVD cleanup.
# ==============================================================================
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from hashlib import sha256

SERIAL = "emulator-5554"
PACKAGE = "io.github.kiryuhak.smarttubevot.stable"
VIDEO_ID = "UF8uR6Z6KLc"
VIDEO_TITLE = "Steve Jobs Stanford Commencement Address"
VIDEO_URL = f"https://www.youtube.com/watch?v={VIDEO_ID}"

ADB_TIMEOUT_SEC = 10
PER_VIDEO_TIMEOUT_SEC = 35
GLOBAL_TIMEOUT_SEC = 180

def log(tag, msg):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}][{tag:22s}] {msg}", flush=True)

def run_cmd(cmd, timeout=ADB_TIMEOUT_SEC):
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, errors="replace", timeout=timeout)
        return res.stdout.strip(), res.returncode
    except subprocess.TimeoutExpired:
        log("TIMEOUT", f"Command timed out after {timeout}s: {' '.join(cmd[:4])}")
        return "", -1
    except Exception as e:
        log("EXCEPTION", f"Error running {' '.join(cmd[:3])}: {e}")
        return "", -1

def check_avd_liveness():
    out, code = run_cmd(["adb", "-s", SERIAL, "devices"], timeout=5)
    if code != 0:
        return False, "ADB devices call failed"
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == SERIAL:
            if parts[1] == "device":
                return True, "ONLINE"
            return False, f"State is {parts[1]}"
    return False, f"Serial {SERIAL} not found"

def redact_url(url):
    try:
        parsed = urllib.parse.urlparse(url)
        path_parts = parsed.path.split("/")
        redacted_path = "/".join(path_parts[:3]) + "/.../" + (path_parts[-1] if len(path_parts) > 3 else "")
        query_keys = list(urllib.parse.parse_qs(parsed.query).keys())
        query_summary = f"?keys={','.join(query_keys)}" if query_keys else ""
        return f"{parsed.scheme}://{parsed.netloc}{redacted_path}{query_summary}"
    except Exception:
        return "<REDACTED_URL>"

def stop_emulator():
    log("CLEANUP", "Invoking canonical vox-emulator-stop.sh...")
    stop_script = os.path.expanduser("~/Developer/SmartTube-VOX-Private/scripts/linux/vox-emulator-stop.sh")
    if os.path.exists(stop_script):
        subprocess.run(["bash", stop_script], timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        run_cmd(["adb", "-s", SERIAL, "emu", "kill"], timeout=5)

def main():
    t_global_start = time.time()
    global_deadline = t_global_start + GLOBAL_TIMEOUT_SEC

    log("PHASE_START", "Starting Bounded Raw VOT Audio Capture for PROMPT №101")
    log("TARGET_VIDEO", f"{VIDEO_ID} — {VIDEO_TITLE}")

    # Ensure preflight check
    preflight_script = os.path.expanduser("~/Developer/SmartTube-VOX-Private/scripts/linux/vox-memory-preflight.sh")
    out, code = run_cmd(["bash", preflight_script, "--phase=emulator"])
    if code != 0:
        log("PREFLIGHT_FAIL", "Memory preflight guard failed! Aborting.")
        print(out)
        return 1

    # Start AVD if not running
    alive, _ = check_avd_liveness()
    if not alive:
        log("EMU_START", "Launching VOX_TV_API34 with default audio...")
        start_script = os.path.expanduser("~/Developer/SmartTube-VOX-Private/scripts/linux/vox-emulator-start.sh")
        out, code = run_cmd(["bash", start_script], timeout=60)
        if code != 0:
            log("EMU_START_FAIL", f"Failed to start emulator: {out}")
            return 1

    alive, reason = check_avd_liveness()
    if not alive:
        log("LIVENESS_FAIL", f"AVD is not online after launch: {reason}")
        return 1

    log("AVD_ONLINE", f"AVD {SERIAL} is online and responsive")

    # Dismiss keyguard / wake
    run_cmd(["adb", "-s", SERIAL, "shell", "wm", "dismiss-keyguard"])
    run_cmd(["adb", "-s", SERIAL, "shell", "input", "keyevent", "82"])

    # Clear logcat before launching video so no stale PlaybackActivity markers exist
    log("CLEAN_LOGCAT", "Clearing logcat buffer before video launch...")
    run_cmd(["adb", "-s", SERIAL, "logcat", "-c"])

    # Launch video
    log("INTENT_LAUNCH", f"Sending VIEW intent for {VIDEO_URL}")
    run_cmd(["adb", "-s", SERIAL, "shell", "am", "start", "-a", "android.intent.action.VIEW",
             "-d", VIDEO_URL, "-n", f"{PACKAGE}/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"])

    # Bounded wait for player readiness (up to 25s)
    player_ready = False
    ready_deadline = time.time() + 25.0
    log("PLAYER_WAIT", "Waiting for PlaybackActivity / Playback is: 3...")
    while time.time() < ready_deadline:
        alive, reason = check_avd_liveness()
        if not alive:
            log("AVD_VANISHED", f"AVD vanished during player readiness wait: {reason}")
            return 1
        logs, code = run_cmd(["adb", "-s", SERIAL, "logcat", "-d", "-t", "100"])
        if code == 0 and ("Playback is: 3" in logs or "PlaybackActivity" in logs or "STATE_READY" in logs):
            player_ready = True
            log("PLAYER_READY", "PlaybackActivity / Player is ready")
            break
        time.sleep(0.5)

    if not player_ready:
        log("PLAYER_WARN", "Playback state marker not detected in 25s; pausing 2s before trigger")
        time.sleep(2.0)
    else:
        time.sleep(1.0)

    # Inject session marker
    session_marker = f"RAW_CAPTURE_{VIDEO_ID}_{int(time.time() * 1000)}"
    run_cmd(["adb", "-s", SERIAL, "shell", "log", "-t", "VOX_MARKER", session_marker])

    # Send trigger broadcast
    log("TRIGGER_VOT", f"Sending startTranslation broadcast (marker={session_marker})")
    run_cmd(["adb", "-s", SERIAL, "shell", "am", "broadcast", "-a", "com.liskovsoft.smartyoutubetv2.TEST_YANDEX_VOT",
             "-p", PACKAGE, "--es", "action", "startTranslation"])

    # Bounded polling for audio URL using logcat -d
    log("POLL_URL", f"Polling logcat for translation audio URL (deadline: {PER_VIDEO_TIMEOUT_SEC}s)...")
    audio_url = None
    poll_deadline = time.time() + PER_VIDEO_TIMEOUT_SEC

    url_pattern = re.compile(r"(?:source_set url=|audioUrl=)(https?://[^\s\"']+)")

    while time.time() < poll_deadline and time.time() < global_deadline:
        alive, reason = check_avd_liveness()
        if not alive:
            log("AVD_VANISHED", f"AVD died during URL polling: {reason}")
            break

        logs, code = run_cmd(["adb", "-s", SERIAL, "logcat", "-d", "-t", "500"])
        if code == 0 and logs:
            marker_idx = logs.find(session_marker)
            search_scope = logs[marker_idx:] if marker_idx != -1 else logs
            match = url_pattern.search(search_scope)
            if match:
                audio_url = match.group(1).strip()
                log("URL_FOUND", f"Discovered translation audio URL: {redact_url(audio_url)}")
                break

        time.sleep(1.0)

    # Clean stop emulator before disk inspection to preserve host resources
    try:
        stop_emulator()
    except Exception as e:
        log("CLEANUP_WARN", f"Cleanup stop error: {e}")

    if not audio_url:
        log("CAPTURE_FAILED", "Failed to capture audio URL before deadline.")
        return 1

    # Safe download to private temporary directory
    tmp_dir = os.path.expanduser(f"/tmp/vox-vot-ab-{int(time.time())}")
    os.makedirs(tmp_dir, mode=0o700, exist_ok=True)
    os.chmod(tmp_dir, 0o700)
    raw_mp3_path = os.path.join(tmp_dir, "raw_vot_translation.mp3")

    log("DOWNLOAD", f"Downloading raw MP3 stream to private directory {tmp_dir}...")
    http_status = None
    content_type = None
    content_length = None

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 YaBrowser/26.4.1.1026 Yowser/2.5 Safari/537.36",
        "Accept": "*/*",
        "Referer": "https://www.youtube.com/",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "cross-site",
        "Sec-Fetch-Dest": "audio",
    }
    req = urllib.request.Request(
        audio_url,
        headers=headers
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        http_status = resp.status
        content_type = resp.headers.get("Content-Type", "unknown")
        content_length = resp.headers.get("Content-Length", "unknown")
        with open(raw_mp3_path, "wb") as f:
            shutil.copyfileobj(resp, f)

    os.chmod(raw_mp3_path, 0o600)
    file_size_bytes = os.path.getsize(raw_mp3_path)

    # Compute SHA-256
    with open(raw_mp3_path, "rb") as f:
        file_sha256 = sha256(f.read()).hexdigest()

    log("DOWNLOAD_DONE", f"Saved {file_size_bytes} bytes (HTTP {http_status}, {content_type})")
    log("SHA256", file_sha256)

    # Inspect with ffprobe
    probe_out, code = run_cmd(["ffprobe", "-v", "quiet", "-print_format", "json",
                              "-show_format", "-show_streams", raw_mp3_path])
    codec_name = "unknown"
    sample_rate = "unknown"
    channels = "unknown"
    bit_rate = "unknown"
    duration_sec = "unknown"

    if code == 0 and probe_out:
        try:
            probe_data = json.loads(probe_out)
            format_info = probe_data.get("format", {})
            duration_sec = format_info.get("duration", "unknown")
            bit_rate = format_info.get("bit_rate", "unknown")

            streams = probe_data.get("streams", [])
            for st in streams:
                if st.get("codec_type") == "audio":
                    codec_name = st.get("codec_name", "unknown")
                    sample_rate = st.get("sample_rate", "unknown")
                    channels = st.get("channels", "unknown")
                    break
        except Exception as e:
            log("PROBE_PARSE_ERR", f"Error parsing probe output: {e}")

    # Output structured inspection results
    print("\n" + "=" * 70, flush=True)
    print("RAW VOT AUDIO INSPECTION REPORT (PROMPT №101)", flush=True)
    print("=" * 70, flush=True)
    print(f"Target Video ID:      {VIDEO_ID} ({VIDEO_TITLE})", flush=True)
    print(f"Redacted Source Host: {redact_url(audio_url)}", flush=True)
    print(f"HTTP Status:          {http_status}", flush=True)
    print(f"Content-Type:         {content_type}", flush=True)
    print(f"File Size:            {file_size_bytes} bytes ({round(file_size_bytes / (1024*1024), 2)} MiB)", flush=True)
    print(f"Duration:             {duration_sec} s", flush=True)
    print(f"Codec / Container:    {codec_name} (MPEG Audio Layer 3)", flush=True)
    print(f"Sample Rate:          {sample_rate} Hz", flush=True)
    print(f"Channels:             {channels}", flush=True)
    print(f"Bitrate:              {round(int(bit_rate) / 1000) if bit_rate.isdigit() else bit_rate} kbps", flush=True)
    print(f"SHA-256 Checksum:     {file_sha256}", flush=True)
    print(f"Local Safe Path:      {raw_mp3_path} (mode 0600)", flush=True)
    print("-" * 70, flush=True)
    print("OWNER PLAYBACK COMMAND (Desktop Native Player):", flush=True)
    print(f"  mpv --no-video {raw_mp3_path}", flush=True)
    print("=" * 70 + "\n", flush=True)

    return 0

if __name__ == "__main__":
    sys.exit(main())
