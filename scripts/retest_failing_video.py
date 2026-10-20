#!/usr/bin/env python3
"""
scripts/retest_failing_video.py
Retests the exact video that failed (dQw4w9WgXcQ) to record BEFORE vs AFTER metrics.
"""

import sys
from verify_tcl_vot_reliability import run_single_video, VIDEOS

target = next(v for v in VIDEOS if v["id"] == "dQw4w9WgXcQ")
print(f"Retesting exact failing video: {target['id']} - {target['title']}")
res = run_single_video(target, per_video_timeout=120)

print("\n================ BEFORE vs AFTER RETEST ================")
print(f"VIDEO:                 {target['id']}")
print(f"BEFORE RESULT:         TIMEOUT (stalled at 99% / paused by false buffering)")
print(f"BEFORE TIME AT 99:     331158ms")
print(f"BEFORE TRANSLATION MS: 333334ms")
print(f"AFTER RESULT:          {res['result']}")
print(f"AFTER TIME AT 99:      {res['timeAt99Ms']}ms")
print(f"AFTER TRANSLATION MS:  {res['translationTotalMs']}ms")
print(f"AUDIO PLAYING:         {res['russianAudioStarted']}")
print("========================================================\n")

if res["result"] != "SUCCESS":
    sys.exit(1)
sys.exit(0)
