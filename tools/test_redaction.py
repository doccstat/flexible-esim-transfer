import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

import redaction

ROOT = Path(__file__).resolve().parents[1]


class RedactionTest(unittest.TestCase):
    def test_shared_android_python_fixtures(self):
        for line in (ROOT / "app/src/test/resources/diagnostic-cases.tsv").read_text(encoding="utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            raw, expected = line.split("\t")
            with self.subTest(raw=raw):
                result = redaction.parse_line(raw)
                self.assertEqual(result, None if expected == "DROP" else json.loads(expected))
                self.assertNotIn("SECRET", json.dumps(result))
        self.assertIsNone(redaction.parse_line("I/FlexibleEsimTransfer(1): hooks: READY:LPA" + " " * redaction.MAX_LINE))

    def test_recording_is_private_explicit_and_has_no_network_permission(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        name = "{http://schemas.android.com/apk/res/android}name"
        exported = "{http://schemas.android.com/apk/res/android}exported"
        permissions = {e.get(name) for e in manifest.findall("uses-permission")}
        self.assertNotIn("android.permission.INTERNET", permissions)
        self.assertNotIn("android.permission.READ_LOGS", permissions)
        self.assertNotIn("android.permission.RECEIVE_BOOT_COMPLETED", permissions)
        components = {e.get(name): e for e in manifest.find("application")}
        self.assertEqual(components[".DiagnosticService"].get(exported), "false")
        self.assertEqual(components[".DiagnosticsActivity"].get(exported), "false")


@unittest.skipUnless(os.name == "posix" and shutil.which("timeout"), "POSIX process-lifetime check")
class ReaderLifetimeTest(unittest.TestCase):
    def check_reader(self, mode):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "id").write_text("#!/bin/sh\nprintf '0\\n'\n")
            fake = root / "logcat"
            fake.write_text(f"#!{sys.executable}\nimport os,sys,time\n"
                            "if '-d' in sys.argv: sys.exit(0)\n"
                            f"open({str(root / 'reader.pid')!r}, 'w').write(str(os.getpid()))\n"
                            "print('I/EuiccGoogle(1): Starting new download transaction.', flush=True)\n"
                            "while True: time.sleep(1)\n")
            fake.chmod(0o700)
            (root / "id").chmod(0o700)
            script = (ROOT / "app/src/main/resources/diagnostic-reader.sh").read_text().replace("/system/bin/", temp + "/")
            process = subprocess.Popen(["timeout", "-s", "TERM", "2", "sh", "-c", script, "reader", "10001,10002"],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                self.assertIn(b"FET_READER_STARTED", process.stdout.readline())
                for _ in range(100):
                    if (root / "reader.pid").exists():
                        break
                    time.sleep(.01)
                pid = int((root / "reader.pid").read_text())
                if mode == "eof":
                    process.stdin.close()
                elif mode == "stop":
                    process.stdin.write(b"stop\n")
                    process.stdin.flush()
                process.wait(timeout=5)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
            finally:
                process.kill()
                process.wait()
                for stream in (process.stdin, process.stdout, process.stderr):
                    stream.close()

    def test_explicit_stop_kills_only_the_reader(self):
        self.check_reader("stop")

    def test_app_death_eof_kills_the_reader(self):
        self.check_reader("eof")

    def test_hard_timeout_reaps_the_reader(self):
        self.check_reader("timeout")


if __name__ == "__main__":
    unittest.main()
