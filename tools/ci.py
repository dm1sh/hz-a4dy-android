#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Small standard-library-only CI helpers. Never print signing material."""
import argparse
import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = "35.0.0"
SEMVER = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)


@dataclass(frozen=True)
class Version:
    name: str
    code: int

    @property
    def prerelease(self):
        return "-" in self.name.split("+", 1)[0]


def load_version(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key not in {"versionName", "versionCode"} or key in values:
            raise ValueError("version.properties must contain versionName and versionCode exactly once")
        values[key] = value
    if set(values) != {"versionName", "versionCode"}:
        raise ValueError("version.properties is missing a version field")
    name = values["versionName"]
    match = SEMVER.fullmatch(name)
    if not match or len(name) > 128:
        raise ValueError("versionName must be a Semantic Version, e.g. 0.1.0 or 0.2.0-rc.1")
    if match.group(4) and any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in match.group(4).split(".")):
        raise ValueError("Numeric prerelease identifiers must not have leading zeroes")
    if not re.fullmatch(r"[1-9][0-9]*", values["versionCode"]):
        raise ValueError("versionCode must be a positive integer")
    code = int(values["versionCode"])
    if code > 2_100_000_000:
        raise ValueError("versionCode exceeds Android's limit")
    return Version(name, code)


def validate_tag(version, tag):
    if tag != "v" + version.name:
        raise ValueError("Release tag must equal v<versionName> from version.properties")


def restore_key(kind, optional=False):
    """Decode a key only into RUNNER_TEMP; secrets remain in this one step's environment."""
    encoded = os.environ.get("KEYSTORE_BASE64", "")
    prefix = "A4DY_" + kind.upper()
    store_password = os.environ.get(prefix + "_STORE_PASSWORD", "")
    key_password = os.environ.get(prefix + "_KEY_PASSWORD", "")
    if optional and not encoded and not store_password and not key_password:
        print("No CI debug signing secret configured; using an ephemeral local debug key.")
        return None
    required = [prefix + suffix for suffix in ("_KEYSTORE", "_STORE_PASSWORD", "_KEY_ALIAS", "_KEY_PASSWORD")]
    if not encoded or any(not os.environ.get(name) for name in required):
        raise ValueError("Signing configuration is incomplete; check the repository's Actions secrets")
    temp = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    destination = temp / ("a4dy-" + kind + ".p12")
    if Path(os.environ[prefix + "_KEYSTORE"]).absolute() != destination:
        raise ValueError("Signing file must be the expected file directly inside RUNNER_TEMP")
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Signing keystore secret is not valid single-line base64") from None
    if not 128 <= len(raw) <= 65_536 or raw[0] != 0x30:
        raise ValueError("Signing keystore secret has an unexpected format or size")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(raw)
    print("Restored temporary " + kind + " keystore; private contents and passwords are not logged.")
    return destination


def command(arguments):
    result = subprocess.run([str(arg) for arg in arguments], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        # Only public APK/tool output passes through this helper, never a signer password.
        raise ValueError("APK verification command failed:\n" + result.stdout)
    return result.stdout


def certificate_digest(report):
    matches = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", report, re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("Expected exactly one APK signing certificate")
    return matches[0].lower()


def collect(kind):
    version = load_version(ROOT / "version.properties")
    commit = command(["git", "-C", ROOT, "rev-parse", "HEAD"]).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Cannot identify the source commit")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ValueError("ANDROID_HOME must point to an Android SDK")
    tools = Path(sdk) / "build-tools" / BUILD_TOOLS
    source = ROOT / "app/build/outputs/apk" / kind / ("app-" + kind + ".apk")
    expected_name = version.name + ("-test" if kind == "debug" else "")
    expected_package = "app.a4dy.print" + (".debug" if kind == "debug" else "")
    badging = command([tools / "aapt2", "dump", "badging", source])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if not package or package.groups() != (expected_package, str(version.code), expected_name):
        raise ValueError("APK package/version does not match the source version and build type")
    verification = command([tools / "apksigner", "verify", "--verbose", "--print-certs", source])
    digest = certificate_digest(verification)
    if kind == "release":
        identity = json.loads((ROOT / "config/signing-certificates.json").read_text())
        if digest != identity["release"]["certificate_sha256"]:
            raise ValueError("Release certificate does not match the public signing identity in source")
    verification += "\n" + command([tools / "zipalign", "-c", "-P", "16", "-v", "4", source])
    destination = ROOT / "artifacts" / kind
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        raise ValueError("Artifact directory must be empty; refusing to mix old and new outputs")
    stem = "A4DY-Print-" + expected_name + ("-" + commit[:7] if kind == "debug" else "")
    apk_name = stem + ".apk"
    shutil.copyfile(source, destination / apk_name)
    files = [apk_name]
    if kind == "debug":
        tests = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
        test_verification = command([tools / "apksigner", "verify", "--verbose", "--print-certs", tests])
        if certificate_digest(test_verification) != digest:
            raise ValueError("Instrumentation APK and app APK have different signing identities")
        verification += "\nInstrumentation APK:\n" + test_verification
        test_name = stem + "-androidTest.apk"
        shutil.copyfile(tests, destination / test_name)
        files.append(test_name)
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repository = os.environ.get("GITHUB_REPOSITORY")
    run_id = os.environ.get("GITHUB_RUN_ID")
    metadata = {
        "application_id": expected_package,
        "version_name": expected_name,
        "version_code": version.code,
        "build_type": kind,
        "source_commit": commit,
        "source_tag": os.environ.get("GITHUB_REF_NAME") if os.environ.get("GITHUB_REF_TYPE") == "tag" else None,
        "actions_run": f"{server}/{repository}/actions/runs/{run_id}" if repository and run_id else None,
        "certificate_sha256": digest,
        "build_tools": BUILD_TOOLS,
        "verified": ["APK signature", "16 KB zip alignment", "package and source version"],
        "device_instrumentation_tests_run": False,
        "apk_files": files,
    }
    (destination / "build-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (destination / "verification.txt").write_text(verification)
    checksums = []
    for path in sorted(destination.iterdir()):
        checksums.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
    (destination / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
    print(json.dumps(metadata, indent=2))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(f"\n### Verified {kind} APK\n\n- `{apk_name}`\n- Source: `{commit}`\n- Certificate SHA-256: `{digest}`\n- Checksums and metadata are included in the artifact.\n- Device instrumentation tests are compiled, not run.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version_parser = commands.add_parser("version")
    version_parser.add_argument("--tag")
    key_parser = commands.add_parser("restore-key")
    key_parser.add_argument("--kind", choices=["debug", "release"], required=True)
    key_parser.add_argument("--optional", action="store_true")
    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("--kind", choices=["debug", "release"], required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == "version":
            version = load_version(ROOT / "version.properties")
            if arguments.tag is not None:
                validate_tag(version, arguments.tag)
            print(f"Source version: {version.name} ({version.code})")
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                    stream.write(f"version_name={version.name}\nversion_code={version.code}\nprerelease={str(version.prerelease).lower()}\n")
        elif arguments.command == "restore-key":
            if arguments.kind == "release" and arguments.optional:
                raise ValueError("Release signing must never be optional")
            restore_key(arguments.kind, arguments.optional)
        else:
            collect(arguments.kind)
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
