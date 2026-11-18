# SPDX-License-Identifier: MIT
import base64
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci


class CiTests(unittest.TestCase):
    def version(self, text):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text(text)
            return ci.load_version(path)

    def test_source_version(self):
        value = self.version("# comment\nversionName=0.1.0\nversionCode=1\n")
        self.assertEqual(ci.Version("0.1.0", 1), value)
        self.assertFalse(value.prerelease)

    def test_prerelease_and_build_metadata(self):
        value = self.version("versionName=1.2.3-rc.1+build.2\nversionCode=8")
        self.assertTrue(value.prerelease)
        self.assertFalse(ci.Version("1.2.3+build-with-dashes", 8).prerelease)

    def test_invalid_semantic_versions(self):
        for name in ["v1.2.3", "01.2.3", "1.2", "1.2.3-rc.01", "1.2.3;touch evil", "1.2.3-"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.version(f"versionName={name}\nversionCode=1")

    def test_invalid_version_codes(self):
        for code in ["0", "-1", "01", "1.5", "2100000001", "nope"]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.version(f"versionName=1.2.3\nversionCode={code}")

    def test_missing_duplicate_and_unknown_fields(self):
        for text in ["versionName=1.2.3", "versionCode=1", "versionName=1.2.3\nversionName=1.2.4\nversionCode=1", "versionName=1.2.3\nversionCode=1\nother=2"]:
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.version(text)

    def test_tag_must_match_source(self):
        ci.validate_tag(ci.Version("1.2.3", 4), "v1.2.3")
        for tag in ["1.2.3", "v1.2.4", "main", "v1.2.3\n"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                ci.validate_tag(ci.Version("1.2.3", 4), tag)

    def test_extracts_one_signing_certificate(self):
        digest = "a1" * 32
        self.assertEqual(digest, ci.certificate_digest("Signer #1 certificate SHA-256 digest: " + digest.upper() + "\n"))
        with self.assertRaises(ValueError):
            ci.certificate_digest("No certificate")
        with self.assertRaises(ValueError):
            ci.certificate_digest(("Signer #1 certificate SHA-256 digest: " + digest + "\n") * 2)

    def signing_environment(self, directory, kind="release"):
        prefix = "A4DY_" + kind.upper()
        return {
            "RUNNER_TEMP": directory,
            "KEYSTORE_BASE64": base64.b64encode(b"\x30" + b"x" * 300).decode(),
            prefix + "_KEYSTORE": str(Path(directory) / ("a4dy-" + kind + ".p12")),
            prefix + "_STORE_PASSWORD": "test-only-store-password",
            prefix + "_KEY_ALIAS": "test-only-alias",
            prefix + "_KEY_PASSWORD": "test-only-key-password",
        }

    def test_restore_key_is_private_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.dict(os.environ, self.signing_environment(directory), clear=True):
                path = ci.restore_key("release")
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
                self.assertEqual(b"\x30" + b"x" * 300, path.read_bytes())
                with self.assertRaises(FileExistsError):
                    ci.restore_key("release")

    def test_restore_refuses_path_outside_runner_temp(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = self.signing_environment(directory)
            environment["A4DY_RELEASE_KEYSTORE"] = str(Path(directory) / "different.p12")
            with patch.dict(os.environ, environment, clear=True), self.assertRaises(ValueError):
                ci.restore_key("release")

    def test_restore_rejects_malformed_base64(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = self.signing_environment(directory)
            environment["KEYSTORE_BASE64"] = "not base64!"
            with patch.dict(os.environ, environment, clear=True), self.assertRaises(ValueError):
                ci.restore_key("release")

    def test_partial_configuration_does_not_fall_back(self):
        with patch.dict(os.environ, {"KEYSTORE_BASE64": "provided-but-incomplete"}, clear=True):
            with self.assertRaises(ValueError):
                ci.restore_key("debug", optional=True)

    def test_unconfigured_debug_can_use_local_key(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(ci.restore_key("debug", optional=True))
            with self.assertRaises(ValueError):
                ci.restore_key("release")


if __name__ == "__main__":
    unittest.main()
