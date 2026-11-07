#!/usr/bin/env python3
"""Verify release identity before publication; never generate a signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.lixingchi.flexibleesimtransfer"
APK_NAME = "FlexibleEsimTransfer.apk"


def metadata():
    text = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    values = {}
    for key, pattern in {
        "package": r"^\s*applicationId '([^']+)'\s*$",
        "code": r"^\s*versionCode ([1-9][0-9]*)\s*$",
        "name": r"^\s*versionName '([0-9]+(?:\.[0-9]+)+)'\s*$",
    }.items():
        matches = re.findall(pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise ValueError("Expected one literal " + key + " in app/build.gradle")
        values[key] = matches[0]
    if values["package"] != PACKAGE:
        raise ValueError("Unexpected application ID")
    values["tag"] = values["code"] + "-" + values["name"]
    return values


def tool(name):
    directory = os.environ.get("BUILD_TOOLS")
    if not directory:
        sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ValueError("Set ANDROID_HOME or BUILD_TOOLS")
        directory = str(Path(sdk) / "build-tools/35.0.0")
    suffix = (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else ""
    path = Path(directory) / (name + suffix)
    if not path.is_file():
        raise ValueError("Missing Android build tool: " + name)
    return str(path)


def run_tool(name, *arguments):
    return subprocess.check_output([tool(name), *map(str, arguments)], text=True,
                                   encoding="utf-8", errors="strict", stderr=subprocess.STDOUT)


def verify(apk, tag, checksum=None):
    match = re.fullmatch(r"([1-9][0-9]*)-([0-9]+(?:\.[0-9]+)+)", tag)
    if not match:
        raise ValueError("Expected versionCode-versionName tag")
    pin = os.environ.get("SIGNING_CERT_SHA256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        raise ValueError("SIGNING_CERT_SHA256 must be the 64 lowercase hex certificate digest")
    apk = Path(apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksum is not None:
        expected = digest + "  " + APK_NAME + "\n"
        if Path(checksum).read_text(encoding="ascii") != expected:
            raise ValueError("APK checksum or asset name mismatch")
    signature = run_tool("apksigner", "verify", "--verbose", "--print-certs", apk)
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-f]{64})$",
                         signature, re.MULTILINE)
    if signers != [pin]:
        raise ValueError("APK signer does not match the single pinned release certificate")
    badging = run_tool("aapt", "dump", "badging", apk)
    identities = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
                            badging, re.MULTILINE)
    if identities != [(PACKAGE, match[1], match[2])]:
        raise ValueError("APK package/version does not match the release tag")
    if re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE):
        raise ValueError("A debuggable APK is not a release")
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("metadata", help="Print the source-declared package, version and tag as JSON")
    check = commands.add_parser("verify", help="Verify APK signature, identity and optional checksum")
    check.add_argument("apk", type=Path)
    check.add_argument("tag")
    check.add_argument("--checksum", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "metadata":
            print(json.dumps(metadata()))
        else:
            print(verify(args.apk, args.tag, args.checksum))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Tool output may contain signing paths; only report the failed step.
        parser.exit(1, "Release verification failed: " + (
            "Android verification tool rejected the APK" if isinstance(error, subprocess.CalledProcessError)
            else str(error)) + "\n")


if __name__ == "__main__":
    main()
