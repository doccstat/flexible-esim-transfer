#!/usr/bin/env python3
"""Follow existing Android logs, saving only allowlisted diagnostic codes as JSONL."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time

from redaction import parse_line


def capture(args):
    # An unfiltered stream proves the reader is live even while the relevant apps are quiet.
    command = ["logcat", "-T", "1", "-v", "brief", "-b", "main", "-b", "system", "-b", "crash", "*:V"]
    if args.root:
        command = ["shell", "su -c " + shlex.quote(shlex.join(command))]
    started = time.monotonic()
    timed_out = threading.Event()
    with args.output.open("x", encoding="utf-8") as output:
        def emit(record):
            record["elapsed_seconds"] = round(time.monotonic() - started, 3)
            output.write(json.dumps(record) + "\n")
            output.flush()

        emit({"event": "capture_start", "schema": 1, "duration_seconds": args.seconds,
              "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "note": "Initial log tail may predate capture. No raw logs or identifiers saved. No events is inconclusive."})
        try:
            process = subprocess.Popen([args.adb, "-s", args.serial, *command], stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                       text=True, encoding="utf-8", errors="replace")
        except OSError:
            emit({"event": "capture_end", "reason": "adb_unavailable"})
            return 1

        def expire():
            timed_out.set()
            if process.poll() is None:
                process.terminate()

        timer = threading.Timer(args.seconds, expire)
        timer.daemon = True
        timer.start()
        ready = False
        reason = "stream_ended"
        try:
            for line in process.stdout:
                if not ready and (line.startswith("--------- beginning of ") or re.match(r"[VDIWEF]/", line)):
                    ready = True
                    emit({"event": "stream_ready"})
                    print("Live redacted capture ready; no transfer started.", flush=True)
                record = parse_line(line)
                if record:
                    emit(record)
        except KeyboardInterrupt:
            reason = "interrupted"
        finally:
            timer.cancel()
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            process.stdout.close()
        if timed_out.is_set():
            reason = "duration_reached"
        emit({"event": "capture_end", "reason": reason, "stream_ready": ready,
              "adb_exit_code": process.returncode})
        return 0 if ready and (timed_out.is_set() or reason == "interrupted" or process.returncode == 0) else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="existing adb executable")
    parser.add_argument("--serial", required=True, help="device to inspect; never exported")
    parser.add_argument("--output", type=Path, required=True, help="new local JSONL file; never overwritten")
    parser.add_argument("--seconds", type=int, required=True, help="capture duration, 1–3600 seconds")
    parser.add_argument("--root", action="store_true", help="read existing log buffers through su")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error("--seconds must be between 1 and 3600")
    if args.output.exists():
        parser.error("--output already exists")
    return capture(args)


if __name__ == "__main__":
    raise SystemExit(main())
