#!/usr/bin/env python3
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))
from verify_tcl_vot_reliability import run_single_video, VIDEOS

target = next(v for v in VIDEOS if v["id"] == "kJQP7kiw5Fk")
print("Retesting exact failing video:", target["id"], "-", target["title"])
res = run_single_video(target, per_video_timeout=120)

print("\n================ BEFORE vs AFTER RETEST ================")
print("VIDEO:                 ", target["id"])
print("BEFORE RESULT:          FAIL_99_STALL (postprocess watchdog timeout 30s)")
print("BEFORE TIME AT 99:      30363ms")
print("BEFORE TRANSLATION MS:  32450ms")
print("AFTER RESULT:          ", res["result"])
print("AFTER TIME AT 99:      ", res["timeAt99Ms"], "ms")
print("AFTER TRANSLATION MS:  ", res["translationTotalMs"], "ms")
print("AUDIO PLAYING:         ", res["russianAudioStarted"])
print("========================================================\n")

if res["result"] != "SUCCESS":
    sys.exit(1)
sys.exit(0)
