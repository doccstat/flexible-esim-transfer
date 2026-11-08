#!/usr/bin/env python3
"""Verify release identity before publication; never generate a signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import stat
import struct
import zipfile
import io

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.lixingchi.flexibleesimtransfer"
APK_NAME = "FlexibleEsimTransfer.apk"
ZIP_NAME = "FlexibleEsimTransfer-Zygisk.zip"
ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}


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


def verify_zygisk(archive, tag, checksum=None):
    match = re.fullmatch(r"([1-9][0-9]*)-([0-9]+(?:\.[0-9]+)+)", tag)
    if not match:
        raise ValueError("Expected versionCode-versionName tag")
    archive = Path(archive)
    if archive.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Oversized module")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if checksum is not None and Path(checksum).read_text(encoding="ascii") != digest + "  " + ZIP_NAME + "\n":
        raise ValueError("ZIP checksum or asset name mismatch")
    with zipfile.ZipFile(archive) as module:
        infos = module.infolist()
        names = {entry.filename for entry in infos}
        if len(infos) != len(names) or sum(entry.file_size for entry in infos) > 32 * 1024 * 1024:
            raise ValueError("Duplicate or oversized ZIP entries")
        for entry in infos:
            if not re.fullmatch(r"[A-Za-z0-9_./-]+", entry.filename) or entry.filename.startswith("/") or ".." in entry.filename.split("/"):
                raise ValueError("Unsafe ZIP path")
            if not stat.S_ISREG(entry.external_attr >> 16):
                raise ValueError("Non-regular ZIP entry")
        required = {"module.prop", "payload.dex", "payload.jar", "customize.sh", "ctl.sh", "uninstall.sh",
                    "webroot/index.html", "webroot/app.js", "webroot/bridge.mjs", "webroot/style.css", "webroot/strings.mjs",
                    "licenses/project.txt", "licenses/LSPlant.txt", "licenses/Dobby.txt", "licenses/DexBuilder.txt",
                    "licenses/parallel-hashmap.txt", "licenses/libxposed-api.txt", "licenses/GPL-3.0.txt", "licenses/VENDORED.md", "licenses/source.txt",
                    "files.sha256.json"} | {"zygisk/" + abi + ".so" for abi in ABIS}
        if int(match[1]) >= 9:
            required.add("licenses/XZ-Embedded.txt")
        if names != required:
            raise ValueError("Missing or unexpected standalone module files")
        manifest = json.loads(module.read("files.sha256.json"))
        expected = {name: hashlib.sha256(module.read(name)).hexdigest() for name in names - {"files.sha256.json"}}
        if manifest != expected:
            raise ValueError("Module file digest mismatch")
        properties = {}
        for line in module.read("module.prop").decode("utf-8").splitlines():
            key, value = line.split("=", 1)
            if key in properties:
                raise ValueError("Duplicate module property")
            properties[key] = value
        if (properties.get("id"), properties.get("versionCode"), properties.get("version")) != ("flexible_esim_transfer", match[1], match[2]):
            raise ValueError("Module identity/version does not match release tag")
        dex = module.read("payload.dex")
        if not re.match(rb"dex\n0[0-9]{2}\x00", dex):
            raise ValueError("Missing standalone DEX")
        with zipfile.ZipFile(io.BytesIO(module.read("payload.jar"))) as payload:
            if payload.namelist() != ["classes.dex", "version.json", "diagnostic-rules.tsv", "diagnostic-reader.sh"]:
                raise ValueError("Unexpected controller payload")
            if sum(info.file_size for info in payload.infolist()) > 8 * 1024 * 1024:
                raise ValueError("Oversized controller payload")
            if payload.read("classes.dex") != dex:
                raise ValueError("Controller and hook payloads differ")
            if json.loads(payload.read("version.json")) != dict(package=PACKAGE, code=match[1], name=match[2], tag=tag):
                raise ValueError("Controller version mismatch")
        for abi, (bits, machine) in ABIS.items():
            elf = module.read("zygisk/" + abi + ".so")
            if len(elf) < 64 or elf[:6] != b"\x7fELF" + bytes([bits, 1]) or struct.unpack_from("<HH", elf, 16) != (3, machine):
                raise ValueError("Wrong native architecture: " + abi)
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("metadata", help="Print the source-declared package, version and tag as JSON")
    check = commands.add_parser("verify", help="Verify APK signature, identity and optional checksum")
    check.add_argument("apk", type=Path)
    check.add_argument("tag")
    check.add_argument("--checksum", type=Path)
    module = commands.add_parser("verify-zygisk", help="Verify standalone ZIP identity, payloads, ABIs and checksums")
    module.add_argument("archive", type=Path)
    module.add_argument("tag")
    module.add_argument("--checksum", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "metadata":
            print(json.dumps(metadata()))
        elif args.command == "verify-zygisk":
            print(verify_zygisk(args.archive, args.tag, args.checksum))
        else:
            print(verify(args.apk, args.tag, args.checksum))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        # Tool output may contain signing paths; only report the failed step.
        parser.exit(1, "Release verification failed: " + (
            "Android verification tool rejected the APK" if isinstance(error, subprocess.CalledProcessError)
            else str(error)) + "\n")


if __name__ == "__main__":
    main()
