import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import capture_logs


def native(message):
    return f"E/EuiccGoogle(123): [9] BlockingEuiccCardManager$ResultRetriever.onComplete: {message}\n"


class CaptureLogsTest(unittest.TestCase):
    def test_original_callback_and_error_mapping_codes(self):
        for text, event, codes in (
            ("Error in createCallback with result code -1.", "card_callback", [-1]),
            ("AuthenticateServer failed with error code 3", "authenticate_server", [3]),
            ("Invalid euiccErrorCode: -1 in TransactionErrorUtil#euiccTransactionErrorToDetailedCode. Use 0xff instead.", "invalid_card_code", [-1]),
            ("Mapping for error code: 525311", "native_error", [525311]),
            ("Mapping error code: 525311 to: 117440512", "mapped_error", [525311, 117440512]),
            ("onGetDownloadableSubscriptionMetadata errorCode: 117440512", "metadata_result", [117440512]),
        ):
            with self.subTest(event=event):
                self.assertEqual(capture_logs.parse_line(native(text)),
                                 {"source": "native", "event": event, "codes": codes})

    def test_exception_messages_are_never_saved(self):
        line = "E/EuiccGoogle(123): Caused by: com.android.euicc.service.EuiccServiceException: secret-eid-token (Error Code=0x803ff)"
        self.assertEqual(capture_logs.parse_line(line),
                         {"source": "native", "event": "service_exception", "codes": [0x803ff]})
        line = "E/EuiccGoogle(123): com.android.euicc.smdp.SmdpException: private-url (responseStatus=1, httpResponseCode=200, subjectCode=8.1, reasonCode=3.8, resultInformation=secret-token)"
        self.assertEqual(capture_logs.parse_line(line),
                         {"source": "native", "event": "smdp_exception", "response_status": 1,
                          "http_status": 200, "subject": "8.1", "reason": "3.8"})

    def test_unknown_payloads_identifiers_and_partial_messages_are_dropped(self):
        for message in (
            "Error in createCallback with result code 89012345678901234567890123456789.",
            "Error in createCallback with result code 9999999999.",
            "Error in createCallback with result code -1. secret",
            "Error in createCallback with result",
            "SM-DP+ response: {activationCode:secret}",
            "Send request to SM-DP+: host=secret path=secret request=secret",
            "AuthenticateServer: eid=secret payload=secret",
        ):
            self.assertIsNone(capture_logs.parse_line(native(message)))
        self.assertIsNone(capture_logs.parse_line(native("Mapping for error code: 12").replace("EuiccGoogle", "OtherApp")))
        self.assertIsNone(capture_logs.parse_line("permission denied secret"))

    def test_module_allowlist_and_native_phases(self):
        self.assertEqual(capture_logs.parse_line("I/FlexibleEsimTransfer(123): com.lixingchi.flexibleesimtransfer: hooks: READY:LPA"),
                         {"source": "module", "event": "hooks", "value": "READY:LPA"})
        self.assertIsNone(capture_logs.parse_line("I/FlexibleEsimTransfer(123): hooks: READY:LPA secret"))
        self.assertEqual(capture_logs.parse_line("E/AndroidRuntime(123): com.lixingchi.flexibleesimtransfer.Selection$Blocked: EID_MISMATCH\n"),
                         {"source": "module", "event": "routing_exception", "value": "EID_MISMATCH"})
        self.assertEqual(capture_logs.parse_line(native("Failed to get profile metadata.")),
                         {"source": "native", "event": "metadata_failed"})

    def test_stream_is_redacted_create_only_and_does_not_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(adb="adb", serial="private-serial", root=True, seconds=5,
                                   output=Path(temp) / "events.jsonl")
            process = Mock()
            process.stdout = io.StringIO("--------- beginning of main\n" + native("Error in createCallback with result code -1.") + native("activationCode: secret"))
            process.poll.return_value = 0
            process.returncode = 0
            with patch("capture_logs.subprocess.Popen", return_value=process) as popen:
                self.assertEqual(capture_logs.capture(args), 0)
                self.assertEqual(popen.call_count, 1)
                command = popen.call_args.args[0]
                self.assertEqual(command[:4], ["adb", "-s", "private-serial", "shell"])
                self.assertIn("logcat -T 1", command[4])
                self.assertNotIn("setprop", command[4])
                with self.assertRaises(FileExistsError):
                    capture_logs.capture(args)
                self.assertEqual(popen.call_count, 1)
            content = args.output.read_text()
            self.assertNotIn("secret", content)
            self.assertNotIn("private-serial", content)
            records = [json.loads(line) for line in content.splitlines()]
            self.assertEqual([r["event"] for r in records],
                             ["capture_start", "stream_ready", "card_callback", "capture_end"])
            self.assertEqual(records[-1]["reason"], "stream_ended")

    def test_missing_adb_and_failed_stream_are_not_reported_ready(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(adb="adb", serial="private", root=False, seconds=1,
                                   output=Path(temp) / "missing.jsonl")
            with patch("capture_logs.subprocess.Popen", side_effect=OSError("secret")):
                self.assertEqual(capture_logs.capture(args), 1)
            self.assertNotIn("secret", args.output.read_text())
            args.output = Path(temp) / "failed.jsonl"
            process = Mock(stdout=io.StringIO("permission denied\n"), returncode=1)
            process.poll.return_value = 1
            with patch("capture_logs.subprocess.Popen", return_value=process):
                self.assertEqual(capture_logs.capture(args), 1)
            self.assertFalse(json.loads(args.output.read_text().splitlines()[-1])["stream_ready"])

    def test_duration_terminates_the_reader(self):
        with tempfile.TemporaryDirectory() as temp:
            args = SimpleNamespace(adb="adb", serial="private", root=False, seconds=1,
                                   output=Path(temp) / "timeout.jsonl")
            process = Mock(stdout=io.StringIO("--------- beginning of main\n"), returncode=-15)
            process.poll.return_value = None
            timer = Mock()
            def make_timer(seconds, callback):
                self.assertEqual(seconds, 1)
                timer.start.side_effect = callback
                return timer
            with patch("capture_logs.subprocess.Popen", return_value=process), patch("capture_logs.threading.Timer", side_effect=make_timer):
                self.assertEqual(capture_logs.capture(args), 0)
            process.terminate.assert_called()
            timer.cancel.assert_called_once()
            self.assertEqual(json.loads(args.output.read_text().splitlines()[-1])["reason"], "duration_reached")


if __name__ == "__main__":
    unittest.main()
