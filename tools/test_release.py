import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release

PIN = "a" * 64
TAG = "7-0.4.1"
SIGNATURE = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + PIN + "\n"
BADGING = "package: name='com.lixingchi.flexibleesimtransfer' versionCode='7' versionName='0.4.1' platformBuildVersionName='16'\n"


class ReleaseVerificationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.apk = self.root / release.APK_NAME
        self.apk.write_bytes(b"fixture APK, not executable")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.checksum = self.root / (release.APK_NAME + ".sha256")
        self.checksum.write_text(self.digest + "  " + release.APK_NAME + "\n", encoding="ascii")
        self.environment = patch.dict(os.environ, {"SIGNING_CERT_SHA256": PIN})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_matching_signed_package_and_checksum(self):
        with patch.object(release, "run_tool", side_effect=[SIGNATURE, BADGING]) as tool:
            self.assertEqual(release.verify(self.apk, TAG, self.checksum), self.digest)
        self.assertEqual([call.args[0] for call in tool.call_args_list], ["apksigner", "aapt"])

    def test_invalid_tag_or_missing_pin_never_invokes_tools(self):
        for tag, pin in [("0-0.4.1", PIN), ("7-0.4.1-test", PIN), (TAG, ""), (TAG, PIN.upper())]:
            with self.subTest(tag=tag, pin=pin), patch.dict(os.environ, {"SIGNING_CERT_SHA256": pin}):
                with patch.object(release, "run_tool") as tool, self.assertRaises(ValueError):
                    release.verify(self.apk, tag)
                tool.assert_not_called()

    def test_wrong_checksum_filename_and_extra_lines_are_rejected(self):
        for text in ["b" * 64 + "  " + release.APK_NAME + "\n",
                     self.digest + "  Other.apk\n", self.checksum.read_text() + "extra\n"]:
            with self.subTest(text=text):
                self.checksum.write_text(text, encoding="ascii")
                with patch.object(release, "run_tool") as tool, self.assertRaises(ValueError):
                    release.verify(self.apk, TAG, self.checksum)
                tool.assert_not_called()

    def test_signature_failure_is_not_treated_as_valid_metadata(self):
        with patch.object(release, "run_tool", side_effect=subprocess.CalledProcessError(1, "apksigner")) as tool:
            with self.assertRaises(subprocess.CalledProcessError):
                release.verify(self.apk, TAG)
            self.assertEqual(tool.call_count, 1)

    def test_different_missing_or_multiple_signers_are_rejected(self):
        for signature in [SIGNATURE.replace(PIN, "b" * 64), "", SIGNATURE + SIGNATURE.replace("#1", "#2")]:
            with self.subTest(signature=signature):
                with patch.object(release, "run_tool", return_value=signature) as tool:
                    with self.assertRaises(ValueError):
                        release.verify(self.apk, TAG)
                    self.assertEqual(tool.call_count, 1)

    def test_wrong_package_version_and_debuggable_apks_are_rejected(self):
        for badging in [BADGING.replace(release.PACKAGE, "example.other"),
                        BADGING.replace("'7'", "'8'"), BADGING.replace("'0.4.1'", "'0.4.2'"),
                        BADGING + "application-debuggable\n", BADGING * 2, ""]:
            with self.subTest(badging=badging), patch.object(release, "run_tool", side_effect=[SIGNATURE, badging]):
                with self.assertRaises(ValueError):
                    release.verify(self.apk, TAG)

    def test_source_metadata_and_duplicate_version_rejection(self):
        actual = release.metadata()
        self.assertEqual(actual["package"], release.PACKAGE)
        self.assertEqual(actual["tag"], actual["code"] + "-" + actual["name"])
        (self.root / "app").mkdir()
        source = (release.ROOT / "app/build.gradle").read_text()
        (self.root / "app/build.gradle").write_text(source + "\nversionCode 999\n")
        with patch.object(release, "ROOT", self.root), self.assertRaises(ValueError):
            release.metadata()

    def test_tool_execution_uses_checked_argument_list(self):
        with patch.object(release, "tool", return_value="/tools with spaces/apksigner"), \
                patch.object(release.subprocess, "check_output", return_value=SIGNATURE) as run:
            release.run_tool("apksigner", "verify", self.apk)
            self.assertEqual(run.call_args.args[0], ["/tools with spaces/apksigner", "verify", str(self.apk)])
            self.assertNotIn("shell", run.call_args.kwargs)


class ReleasePipelineTests(unittest.TestCase):
    def test_stable_main_release_and_verified_listing_distribution(self):
        workflows = release.ROOT / ".github/workflows"
        source = (workflows / "release.yml").read_text()
        distribution = (workflows / "distribute-release.yml").read_text()
        checks = (workflows / "android.yml").read_text()
        self.assertIn("branches: [main]", source)
        self.assertIn("github.ref == 'refs/heads/main'", source)
        self.assertIn("environment: release", source)
        self.assertIn("--prerelease=false", source)
        self.assertIn("--ks-pass env:SIGNING_STORE_PASSWORD --key-pass env:SIGNING_KEY_PASSWORD", source)
        self.assertNotIn("keytool", source)
        self.assertIn("gh workflow run distribute-release.yml", source)
        for workflow in [source, distribution]:
            self.assertIn("group: signed-release", workflow)
            self.assertIn("cancel-in-progress: false", workflow)
            self.assertIn("python3 tools/release.py verify", workflow)
            self.assertIn("persist-credentials: false", workflow)
            self.assertIn("--latest", workflow)
        self.assertIn("/releases/latest", distribution)
        self.assertIn("--checksum", distribution)
        self.assertIn("LSPOSED_RELEASE_TOKEN", distribution)
        self.assertNotIn("ANDROID_KEYSTORE_BASE64", distribution)
        self.assertNotIn("secrets.", checks)
        self.assertNotIn("pull_request_target", checks)
        self.assertNotIn("environment: release", checks)
        for workflow in (source, checks):
            self.assertIn("testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:assembleRelease", workflow)
            self.assertIn("tools/build_zygisk.py", workflow)
            self.assertIn("ndk;30.0.16248370", workflow)
        for workflow in (source, distribution):
            self.assertIn("tools/release.py verify-zygisk", workflow)
            self.assertGreaterEqual(workflow.count("FlexibleEsimTransfer-Zygisk.zip.sha256"), 2)
            self.assertLess(workflow.index("--draft=true"), workflow.index("gh release upload"))
            self.assertGreater(workflow.index("--draft=false"), workflow.index("gh release upload"))
        self.assertNotIn("tools/build_zygisk.py", distribution)

    def test_gradle_wrapper_and_distribution_are_pinned(self):
        wrapper = release.ROOT / "gradle/wrapper"
        self.assertEqual(hashlib.sha256((wrapper / "gradle-wrapper.jar").read_bytes()).hexdigest(),
                         "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f")
        properties = (wrapper / "gradle-wrapper.properties").read_text()
        self.assertIn("gradle-8.13-bin.zip", properties)
        self.assertIn("distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78", properties)


if __name__ == "__main__":
    unittest.main()
