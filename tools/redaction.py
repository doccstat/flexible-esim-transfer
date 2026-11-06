"""Shared allowlist used by the on-device recorder and the host tools."""
from pathlib import Path
import re

MAX_LINE = 16384
RULES = []
for line in (Path(__file__).resolve().parents[1] / "app/src/main/resources/diagnostic-rules.tsv").read_text(encoding="utf-8").splitlines():
    if line and not line.startswith("#"):
        source, event, kind, pattern = line.split("\t")
        RULES.append((source, event, kind, re.compile(pattern)))


def parse_message(source, message):
    if len(message) > MAX_LINE:
        return None
    for rule_source, event, kind, pattern in RULES:
        if source != rule_source:
            continue
        match = pattern.fullmatch(message)
        if not match:
            continue
        record = {"source": source, "event": event}
        if kind in ("codes", "unsigned_code"):
            codes = [int(v, 16 if v.startswith("0x") else 10) for v in match.groups()]
            maximum = 0xffffffff if kind == "unsigned_code" else 0x7fffffff
            if not all(-0x80000000 <= code <= maximum for code in codes):
                return None
            record["codes"] = codes
        elif kind in ("value", "result"):
            value = match[1]
            if kind == "result" and "=" in value and not -0x80000000 <= int(value.split("=")[1]) <= 0x7fffffff:
                return None
            record["value"] = value
        elif kind == "smdp":
            record.update(response_status=int(match[1]), http_status=int(match[2]), subject=match[3], reason=match[4])
        elif kind != "phase":
            raise ValueError("Unknown diagnostic rule type")
        return record
    return None


def parse_line(line):
    if len(line) > MAX_LINE:
        return None
    match = re.fullmatch(r"[VDIWEF]/(FlexibleEsimTransfer|AndroidRuntime|EuiccGoogle|Euicc|NearbyConnections)\s*\(\s*[0-9]+\):\s*(.*)", line.rstrip("\r\n"))
    if not match:
        return None
    tag, message = match.groups()
    if tag == "FlexibleEsimTransfer":
        return parse_message("module", re.sub(r"^com\.lixingchi\.flexibleesimtransfer: ", "", message))
    if tag == "AndroidRuntime":
        match = re.fullmatch(r"(?:Caused by: )?com\.lixingchi\.flexibleesimtransfer\.Selection\$Blocked: ([A-Z_]+)", message)
        record = parse_message("module", "blocked: " + match[1]) if match else None
        if record:
            record["event"] = "routing_exception"
        return record
    if tag == "NearbyConnections":
        # Flogger can append context metadata; none of it belongs in an exported record.
        return parse_message("nearby", re.sub(r" \[CONTEXT [^\r\n]*\]$", "", message))
    return parse_message("native", re.sub(r"^\[[0-9]+\] [\w.$]+: ", "", message))
