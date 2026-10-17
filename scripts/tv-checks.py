#!/usr/bin/env python3
"""Run isolated native TV regressions and collect their screenshots."""
import argparse
import io
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile

CHECKS = ("performance", "intro", "controls", "alignment", "subtitles", "translate", "skip", "title", "lists", "search", "genres", "trailer", "updates", "watched", "resume", "filters", "ranks")


def passed(output):
    return bool(re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M)
                and re.search(r"^INSTRUMENTATION_RESULT: stream=PASS:", output, re.M))


def extract_reports(payload, destination):
    screenshots = 0
    with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
        for member in archive:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"Unsafe report path: {member.name}")
            if not member.isfile() or path.suffix not in (".png", ".xml", ".txt"):
                continue
            target = destination.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source:
                data = source.read()
            if path.suffix == ".png":
                if not data.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError(f"Invalid screenshot: {member.name}")
                screenshots += 1
            target.write_bytes(data)
    if screenshots == 0:
        raise ValueError("No PNG screenshots were collected")
    return screenshots


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
        for name, safe in (("./fixture.png", True), ("../outside.png", False)):
            image = b"\x89PNG\r\n\x1a\nproof"
            payload = io.BytesIO()
            with tarfile.open(fileobj=payload, mode="w") as archive:
                entry = tarfile.TarInfo(name)
                entry.size = len(image)
                archive.addfile(entry, io.BytesIO(image))
            with tempfile.TemporaryDirectory() as folder:
                try:
                    assert extract_reports(payload.getvalue(), Path(folder)) == 1
                    assert safe and (Path(folder) / "fixture.png").read_bytes() == image
                except ValueError:
                    assert not safe
        for payload in (b"tar: Permission denied\n", bytes(1024)):
            with tempfile.TemporaryDirectory() as folder:
                try:
                    extract_reports(payload, Path(folder))
                except (tarfile.TarError, ValueError):
                    pass
                else:
                    raise AssertionError("Invalid or empty report archive was accepted")
        print("PASS: instrumentation results, internal report extraction, PNG evidence and invalid archive rejection")
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
    try:
        reports = subprocess.run(adb + ["shell", "-T", "run-as", "com.doxigo.tv30", "tar", "-C", "files", "-cf", "-", "."],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        if reports.returncode != 0:
            raise RuntimeError(f"Remote tar exited {reports.returncode}: {reports.stderr.decode(errors='replace').strip()}")
        count = extract_reports(reports.stdout, screenshots)
        print(f"Collected {count} PNG screenshots", flush=True)
    except (subprocess.TimeoutExpired, tarfile.TarError, ValueError, OSError, RuntimeError) as error:
        message = f"Internal screenshot collection failed: {error}"
        (args.output / "screenshot-extraction.txt").write_text(message)
        print(message, file=sys.stderr)
        failed.append("screenshots")
    with (args.output / "device.txt").open("w") as device:
        subprocess.run(adb + ["shell", "getprop"], stdout=device, check=True, timeout=15)
    if failed:
        print("Failed checks: " + ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
