import unittest
from pathlib import Path
import xml.etree.ElementTree as ET
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

    def test_inventory_events_never_export_eids_or_ports(self):
        raw = """FlexibleEsimTransfer: inventory_bridge: READY
FlexibleEsimTransfer: inventory_read: EMPTY
FlexibleEsimTransfer: inventory_read: FAILED
FlexibleEsimTransfer: inventory_read: READY
FlexibleEsimTransfer: inventory_read: 89012345678901234567890123456789
FlexibleEsimTransfer: inventory_read: READY eid=89012345678901234567890123456789
FlexibleEsimTransfer: inventory_read: port=7
"""
        self.assertEqual(diagnostics.events(raw), [
            {"event": "inventory_bridge", "value": "READY"},
            {"event": "inventory_read", "value": "EMPTY"},
            {"event": "inventory_read", "value": "FAILED"},
            {"event": "inventory_read", "value": "READY"},
        ])

    def test_inventory_permission_is_signature_only_and_no_telephony_permission_is_requested(self):
        manifest = ET.parse(Path(__file__).resolve().parents[1] / "app/src/main/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        permission = diagnostics.MODULE + ".permission.READ_INVENTORY"
        self.assertEqual([(node.get(android + "name"), node.get(android + "protectionLevel"))
                          for node in manifest.findall("permission")], [(permission, "signature")])
        self.assertEqual([node.get(android + "name") for node in manifest.findall("uses-permission")], [permission])

    def test_routing_crashes_export_only_known_reason_codes(self):
        raw = """E/AndroidRuntime(123): Caused by: com.lixingchi.flexibleesimtransfer.Selection$Blocked: SETTINGS_CHANGED_RESTART_REQUIRED
E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: RESTART_REQUIRED
E/OtherApp(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: EID_MISMATCH
E/AndroidRuntime(123): com.other.app.Selection$Blocked: EID_MISMATCH
E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: PRIVATE_TOKEN
E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: EID_MISMATCH secret
E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: 89012345678901234567890123456789
E/AndroidRuntime(123): Process: private.package, PID: 123
"""
        self.assertEqual(diagnostics.routing_exceptions(raw), [
            "SETTINGS_CHANGED_RESTART_REQUIRED", "RESTART_REQUIRED"])

    def test_missing_truncated_or_long_crash_logs_are_bounded(self):
        self.assertEqual(diagnostics.routing_exceptions("permission denied"), [])
        self.assertEqual(diagnostics.routing_exceptions(
            "E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: RESTART_"), [])
        line = "E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: EID_MISMATCH\n"
        self.assertEqual(diagnostics.routing_exceptions(line * 60), ["EID_MISMATCH"] * 50)

    def test_package_output_keeps_only_versions(self):
        self.assertEqual(diagnostics.package_version("versionCode=2 minSdk=33\nversionName=0.2.0-routing\nuid=123 token=secret"),
                         {"versionName": "0.2.0-routing", "versionCode": "2"})
        self.assertEqual(diagnostics.package_version("permission denied"), {"status": "unavailable"})


if __name__ == "__main__":
    unittest.main()
