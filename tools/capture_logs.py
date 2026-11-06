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

from diagnostics import events, routing_exceptions

NUMBER = r"(-?\d{1,10})"
NATIVE_CODES = (
    ("card_callback", rf"Error in createCallback with result code {NUMBER}\."),
    ("authenticate_server", rf"AuthenticateServer failed with error code {NUMBER}"),
    ("invalid_card_code", rf"Invalid euiccErrorCode: {NUMBER} in TransactionErrorUtil#euiccTransactionErrorToDetailedCode\. Use 0xff instead\."),
    ("native_error", rf"Mapping for error code: {NUMBER}"),
    ("mapped_error", rf"Mapping error code: {NUMBER} to: {NUMBER}"),
    ("metadata_result", rf"onGetDownloadableSubscriptionMetadata errorCode: {NUMBER}"),
    ("transfer_connection_result", rf"onConnectionResult: statusCode: {NUMBER}, model: .*, manufacturer: .*"),
    ("transfer_generic_error", rf"showGenericError:(?:true|false), destroyed:(?:true|false), resultCode:{NUMBER}"),
    ("transfer_start_error", rf"Failed to start the esim transfer session: (?:com\.google\.android\.gms\.common\.api\.ApiException: )?{NUMBER}(?:: .*)?"),
)
PHASES = {
    "Failed to get profile metadata.": "metadata_failed",
    "Failed to create download transaction.": "transaction_failed",
    "SM-DP+ cannot authenticate the eUICC.": "client_authentication_failed",
    "Cannot initiate a profile download due to an SM-DP+ error.": "initiation_failed",
    "Cannot initiate a profile download due to an eUICC error.": "card_response_failed",
    "unAuthenticatedSessionConnected": "transfer_session_connected_unauthenticated",
    "LPA onDeepLinkUrlUpdate": "transfer_qr_updated",
    "Start QR code fallback for cloud transfer": "transfer_qr_fallback",
    "Teardown failed with D2D connection timeout. Source is offline.": "transfer_teardown_timeout",
}


def parse_line(line):
    # Export fixed labels/codes, never a raw message with identifiers merely substituted.
    match = re.fullmatch(r"[VDIWEF]/(FlexibleEsimTransfer|AndroidRuntime|EuiccGoogle|Euicc)\s*\(\s*\d+\):\s*(.*)", line.rstrip("\r\n"))
    if not match:
        return None
    tag, message = match.groups()
    if tag == "FlexibleEsimTransfer":
        found = events(line)
        return {"source": "module", **found[0]} if found else None
    if tag == "AndroidRuntime":
        found = routing_exceptions(line.rstrip("\r\n"))
        return {"source": "module", "event": "routing_exception", "value": found[0]} if found else None
    message = re.sub(r"^\[\d+\] [\w.$]+: ", "", message)
    for event, pattern in NATIVE_CODES:
        match = re.fullmatch(pattern, message)
        if match:
            codes = [int(value) for value in match.groups()]
            if all(-(2**31) <= code < 2**31 for code in codes):
                return {"source": "native", "event": event, "codes": codes}
            return None
    if message in PHASES:
        return {"source": "native", "event": PHASES[message]}
    if message.startswith("Failed to start the esim transfer session: "):
        return {"source": "native", "event": "transfer_start_failed"}
    match = re.fullmatch(r"(?:Caused by: )?com\.android\.euicc\.service\.EuiccServiceException: .* \(Error Code=(-?\d{1,10}|0x[0-9a-fA-F]{1,8})\)", message)
    if match:
        code = int(match[1], 16 if match[1].startswith("0x") else 10)
        if -(2**31) <= code <= 0xffffffff:
            return {"source": "native", "event": "service_exception", "codes": [code]}
    if re.match(r"(?:Caused by: )?com\.android\.euicc\.smdp\.SmdpException:", message):
        match = re.search(r"\(responseStatus=(\d{1,3}), httpResponseCode=(\d{1,3}), subjectCode=(null|\d{1,2}(?:\.\d{1,2}){0,2}), reasonCode=(null|\d{1,2}(?:\.\d{1,2}){0,2}), resultInformation=", message)
        if match:
            return {"source": "native", "event": "smdp_exception",
                    "response_status": int(match[1]), "http_status": int(match[2]),
                    "subject": match[3], "reason": match[4]}
    return None


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
