#!/usr/bin/env python3
"""Run isolated native TV regressions and collect their screenshots."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

CHECKS = ("performance", "intro", "controls", "alignment", "subtitles", "skip", "title", "lists", "search", "genres", "trailer", "updates")


def passed(output):
    return bool(re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M)
                and re.search(r"^INSTRUMENTATION_RESULT: stream=PASS:", output, re.M))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("app/build/reports/tv-checks"))
    parser.add_argument("--check", action="append", choices=CHECKS)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        assert passed("INSTRUMENTATION_RESULT: stream=PASS: fixture\nINSTRUMENTATION_CODE: -1\n")
        assert not passed("INSTRUMENTATION_STATUS: stream=PUBLIC PASS: video\nINSTRUMENTATION_CODE: 0\n")
        assert not passed("INSTRUMENTATION_RESULT: stream=AssertionError\nINSTRUMENTATION_CODE: -1\n")
        assert not passed("INSTRUMENTATION_FAILED: Process crashed.\n")
        print("PASS: instrumentation result validation")
        return 0
    if not args.serial:
        parser.error("--serial is required: choose a disposable emulator, never a signed-in TV")
    if not args.serial.startswith("emulator-"):
        parser.error("Fixture checks require a disposable emulator; they replace local preferences")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]
    subprocess.run(adb + ["wait-for-device"], check=True, timeout=60)
    subprocess.run(adb + ["shell", "setprop", "debug.lily.readonly", "1"], check=True, timeout=15)
    for apk in ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"):
        subprocess.run(adb + ["install", "-r", "-t", apk], check=True, timeout=90)
    failed = []
    for check in args.check or CHECKS:
        subprocess.run(adb + ["logcat", "-c"], check=True, timeout=15)
        command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "check", check,
                         "com.doxigo.tv30.test/com.doxigo.tv30.PerformanceChecks"]
        try:
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            output = result.stdout
            ok = result.returncode == 0 and passed(output)
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode() if isinstance(error.stdout, bytes) else error.stdout or ""
            output += "\nFAIL: instrumentation exceeded 180 seconds\n"
            ok = False
            subprocess.run(adb + ["shell", "am", "force-stop", "com.doxigo.tv30"], check=True, timeout=15)
        (args.output / f"{check}.txt").write_text(output)
        with (args.output / f"{check}-logcat.txt").open("w") as log:
            subprocess.run(adb + ["logcat", "-d", "-v", "threadtime"], stdout=log, stderr=subprocess.STDOUT, timeout=30)
        print(output, flush=True)
        print(f"{check}: {'PASS' if ok else 'FAIL'}", flush=True)
        if not ok:
            failed.append(check)
    screenshots = args.output / "screenshots"
    screenshots.mkdir(exist_ok=True)
    subprocess.run(adb + ["pull", "/sdcard/Android/data/com.doxigo.tv30/files/.", str(screenshots)], timeout=60)
    with (args.output / "device.txt").open("w") as device:
        subprocess.run(adb + ["shell", "getprop"], stdout=device, check=True, timeout=15)
    if failed:
        print("Failed checks: " + ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
