import unittest
import diagnostics


class DiagnosticsTest(unittest.TestCase):
    def test_only_known_event_payloads_are_exported(self):
        raw = """[private timestamp] I/FlexibleEsimTransfer: hooks: READY:LPA
[private timestamp] I/FlexibleEsimTransfer: service_result: download=0
[private timestamp] I/FlexibleEsimTransfer: blocked: EID_MISMATCH
OtherApp: blocked: EID_MISMATCH
I/FlexibleEsimTransfer: eid: 89012345678901234567890123456789
I/FlexibleEsimTransfer: manager: routing:getEid secret-token
I/FlexibleEsimTransfer: blocked: PASSWORD
I/FlexibleEsimTransfer: service_result: download=89012345678901234567890123456789
"""
        self.assertEqual(diagnostics.events(raw), [
            {"event": "hooks", "value": "READY:LPA"},
            {"event": "service_result", "value": "download=0"},
            {"event": "blocked", "value": "EID_MISMATCH"},
        ])

    def test_missing_or_truncated_logs_do_not_invent_success(self):
        self.assertEqual(diagnostics.events("permission denied"), [])
        self.assertEqual(diagnostics.events("FlexibleEsimTransfer: hooks: REA"), [])
        self.assertEqual(len(diagnostics.events("FlexibleEsimTransfer: hooks: READY:GMS\n" * 600)), 500)

    def test_package_output_keeps_only_versions(self):
        self.assertEqual(diagnostics.package_version("versionCode=2 minSdk=33\nversionName=0.2.0-routing\nuid=123 token=secret"),
                         {"versionName": "0.2.0-routing", "versionCode": "2"})
        self.assertEqual(diagnostics.package_version("permission denied"), {"status": "unavailable"})


if __name__ == "__main__":
    unittest.main()
