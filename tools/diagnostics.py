#!/usr/bin/env python3
"""Export a read-only, identifier-free report through an existing adb installation."""
import argparse
import datetime
import json
from pathlib import Path
import re
import subprocess

from redaction import parse_message

MODULE = "com.lixingchi.flexibleesimtransfer"
PACKAGES = (MODULE, "com.google.android.euicc", "com.google.android.gms", "com.android.phone")


def events(text):
    result = []
    for line in text.splitlines():
        if "FlexibleEsimTransfer" not in line:
            continue
        # Framework prefixes vary. Export only complete, known event payloads, never raw lines.
        match = re.search(r"\b(hooks|bootstrap|inventory|inventory_bridge|inventory_read|manager|service|card|card_session|blocked|service_result): (\S+)$", line)
        if not match:
            continue
        record = parse_message("module", match[0])
        if record:
            result.append({"event": record["event"], "value": record["value"]})
    return result[-500:]


def routing_exceptions(text):
    result = []
    for line in text.splitlines():
        if "AndroidRuntime" not in line:
            continue
        match = re.search(r"\b" + re.escape(MODULE) + r"\.Selection\$Blocked: ([A-Z_]+)$", line)
        if match and parse_message("module", "blocked: " + match[1]):
            result.append(match[1])
    return result[-50:]


def package_version(text):
    result = {}
    for key in ("versionName", "versionCode"):
        match = re.search(r"\b" + key + r"=([A-Za-z0-9._()+-]{1,100})(?=\s|$)", text)
        if match:
            result[key] = match.group(1)
    return result or {"status": "unavailable"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="existing adb executable")
    parser.add_argument("--serial", required=True, help="device to inspect; not included in the report")
    parser.add_argument("--output", required=True, type=Path, help="new JSON file; never overwritten")
    parser.add_argument("--root", action="store_true", help="read Vector module status, scope and logs through su")
    parser.add_argument("--routing", choices=("on", "off", "unknown"), default="unknown",
                        help="report the setting shown in the module app; no setting is changed")
    parser.add_argument("--port", default="unknown", help="report the saved port: auto, an index, or unknown")
    args = parser.parse_args()
    if args.port not in ("auto", "unknown") and not re.fullmatch(r"[0-9]{1,10}", args.port):
        parser.error("--port must be auto, unknown, or a nonnegative index")
    if args.output.exists():
        parser.error("--output already exists; choose another path")
    errors = []

    def run(label, *command):
        try:
            process = subprocess.run([args.adb, "-s", args.serial, *command],
                                     capture_output=True, text=True, timeout=30, errors="replace")
            if process.returncode == 0:
                return process.stdout
        except (OSError, subprocess.TimeoutExpired):
            pass
        errors.append(label + ": unavailable")  # stderr may contain device identifiers.
        return ""

    report = {"schema": 1, "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "configuration": {"source": "operator-reported, not independently verified",
                                "routing": args.routing, "port": args.port,
                                "destination": "identifier omitted"},
              "versions": {package: package_version(run(package, "shell", "dumpsys", "package", package))
                           for package in PACKAGES}}
    report["android"] = {}
    for prop in ("ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch"):
        value = run(prop, "shell", "getprop", prop).strip()
        report["android"][prop] = value if re.fullmatch(r"[A-Za-z0-9._-]{1,30}", value) else "unavailable"
    logs = run("logcat", "logcat", "-d", "-v", "brief", "FlexibleEsimTransfer:I", "*:S")
    if args.root:
        cli = "/data/adb/lspd/cli"
        modules = run("framework status", "shell", f"su -c '{cli} modules ls'")
        match = re.search(r"^" + re.escape(MODULE) + r"\s+\d+\s+(enabled|disabled)\s*$", modules, re.M)
        report["framework_module"] = match.group(1) if match else "unavailable"
        scope = run("framework scope", "shell", f"su -c '{cli} scope ls {MODULE}'")
        report["scope_packages_seen"] = [p for p in PACKAGES[1:] if p in scope]
        report["scope_note"] = "Package presence only; verify owner-user scope in the framework UI."
        logs += "\n" + run("framework logs", "shell", f"su -c '{cli} log cat'")
    report["events"] = events(logs)
    report["routing_exceptions"] = routing_exceptions(run(
        "crash log", "logcat", "-d", "-b", "crash", "-v", "brief", "AndroidRuntime:E", "*:S"))
    report["errors"] = errors
    report["limitations"] = ("No events does not prove hooks loaded. Routing exceptions may predate the current build/process. "
                              "No SIM inventory, credentials, raw logs or source-line data collected.")
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print("Diagnostics exported locally. Review before sharing; nothing uploaded.")


if __name__ == "__main__":
    main()
