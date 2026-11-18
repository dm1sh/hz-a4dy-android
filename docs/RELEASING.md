# CI builds, releases and signing

## Workflows

| Workflow | Trigger | Result |
|---|---|---|
| `Debug APK` | Every branch push; pull request; manual dispatch | Host/CI-helper tests, debug lint, app and compiled instrumentation APKs, verification output, SHA-256 checksums and source/build metadata as 30-day Actions artifacts |
| `Release APK` | Push of a `v[0-9]*` tag; manual dispatch **against an existing version tag** | Tests, release lint, dedicated-key-signed release APK, certificate/package/alignment verification, 90-day artifact and a GitHub Release with downloadable APK/checksums/metadata |

A branch push builds the tip commit of that push (GitHub's normal `push` behavior). Push commits individually if a separate APK is needed for each intermediate commit. Earlier branch builds are not automatically cancelled. Tag pushes do not duplicate debug builds. No emulator/device/Bluetooth test is implied by a green CI run. Test/lint reports are retained for 14 days, including on failures when available.

All action references are pinned to full commit SHAs. Builds run on Ubuntu 24.04 with JDK 21, checksum-pinned Gradle 8.13, SDK platform 36 and build-tools 35.0.0. The Gradle setup action also validates the wrapper JAR. Dependencies are cached; keystores are not. No proprietary app or bytecode is fetched in CI.

Build jobs have only `contents: read` permission. Only the final release-publication job gets `contents: write`, and it downloads already-verified artifacts instead of checking out/executing the app source. Checkout does not persist git credentials. Publication uses GitHub's short-lived `GITHUB_TOKEN`, **not a personal access token**. Rerunning one tag serializes that tag's release jobs and replaces the assets for that release; it does not create a new version tag.

## Version tags

`version.properties` is the single source for Android version metadata:

```properties
versionName=0.1.0
versionCode=1
```

Before each new public version:

1. Set `versionName` to a Semantic Version. Prereleases such as `0.2.0-rc.1` are supported and published as GitHub prereleases.
2. **Increase `versionCode` for every published version, including prereleases.** It is a positive integer no greater than 2,100,000,000. Do not reuse a released code or rely on Actions run numbers to order Android updates.
3. Commit and push the version change and wait for the debug workflow to pass.
4. Create and push the matching tag. For the source's initial `0.1.0`, the commands would be:

   ```sh
   git tag -a v0.1.0 -m "A4DY Print 0.1.0"
   git push origin v0.1.0
   ```

The release workflow rejects a tag that does not equal `v` plus the source `versionName`; it does not silently override an APK's version. Tags without a leading `v` are not release triggers. Manual release runs must select a tag, not `main`. Do not move an already published tag; issue a new version/code for changed source.

## Signing identities

- Release: **`app.a4dy.print`**, dedicated RSA-4096 key, alias `a4dy-release`.
- Debug: **`app.a4dy.print.debug`**, a separate stable RSA-4096 CI key, alias `a4dy-ci-debug`.
- The public certificate SHA-256 fingerprints are in [`config/signing-certificates.json`](../config/signing-certificates.json). `tools/ci.py collect --kind release` refuses a release APK signed by any other certificate.
- Private keys use password-encrypted **PKCS12** files and live only in encrypted repository Actions secrets / the owner's private encrypted backup. During a signing step the decoded key is created with mode 0600 under `RUNNER_TEMP`, removed by an exit trap and again by an always-run cleanup step. Password variables are scoped to the signing step, not exported for the whole job.
- A release build without complete release signing configuration **fails**; it never substitutes a debug signer or uploads an unsigned APK. Debug builds never receive release secrets.
- Pull-request builds use ephemeral debug signing without repository signing secrets. In forks without configured CI debug secrets, push builds also fall back to a local debug key with an explicit notice. Partial signing configuration fails rather than silently changing identities.

The first manually supplied test APK used a different debug certificate, whose private key is not available. It cannot be updated in place with the new CI debug identity. Uninstall that old debug app once if Android rejects the CI APK (local data is erased). Subsequent CI debug builds can update one another. Local debug builds may use another certificate. Release builds are a separate app ID and can coexist with debug builds.

## Repository Actions secrets

Configured for `dm1sh/hz-a4dy-android`; **never put their values in source, workflow YAML, Gradle properties, issues, artifacts or releases**:

| Secret | Value |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Single-line base64 of the permanent release PKCS12 keystore |
| `ANDROID_RELEASE_STORE_PASSWORD` | Release store password |
| `ANDROID_RELEASE_KEY_ALIAS` | Release key alias |
| `ANDROID_RELEASE_KEY_PASSWORD` | Release private-key password (same as the PKCS12 store password) |
| `ANDROID_DEBUG_KEYSTORE_BASE64` | Single-line base64 of the separate CI debug PKCS12 keystore |
| `ANDROID_DEBUG_STORE_PASSWORD` | CI debug store/key password |

These are repository secrets encrypted with GitHub's repository public key before upload. Base64 by itself is **not** encryption. Restrict repository write access: trusted branches/workflows can use repository secrets. A protected release environment or tag rules can be added by the owner if approval gates are desired.

### Private backup and recovery

The repository owner receives a **private AES-256 encrypted ZIP backup** containing both PKCS12 keys, passwords/aliases, public certificates and restoration notes, plus a separate archive-password download. Neither download belongs in git or a GitHub Release. Open with an AES-ZIP-capable tool such as 7-Zip or PeaZip; basic ZIP tools may not support this encryption. Keep the archive and its password separately, with an offline backup.

To restore Actions after losing repository secrets, extract the private backup in a private location, read its `credentials.json`, and set the six secrets above in **Settings → Secrets and variables → Actions**. For a base64 value, Linux can use `base64 -w 0 /private/path/a4dy-release.p12`; avoid terminal logging/history containing secret values. Verify the restored certificate against the public fingerprint in source.

Losing the release key prevents compatible future updates to the installed release app. Do not generate a replacement just because a workflow fails. If a key is compromised, stop publication and plan an explicit signing-identity migration; changing a repository secret alone does not make old Android installs trust a new certificate.

## Local signed release build

Use JDK 21 and the SDK as in the README. Read secrets privately into the following environment variables (do not write them into checked-in files):

- `A4DY_RELEASE_KEYSTORE`: absolute path to the release `.p12`.
- `A4DY_RELEASE_STORE_PASSWORD`
- `A4DY_RELEASE_KEY_ALIAS`
- `A4DY_RELEASE_KEY_PASSWORD`

Then:

```sh
python3 tools/ci.py version --tag v0.1.0  # use the source's actual version
./gradlew :app:assembleRelease
python3 tools/ci.py collect --kind release
```

`ANDROID_HOME` must be set for the verification helper. It checks the APK certificate against the public source fingerprint, runs `apksigner`, `zipalign -c -P 16`, and `aapt2`, and writes the distributable bundle to the ignored `artifacts/release/` directory. Empty that directory before collecting a second time to avoid mixing outputs. This collection step requires a git checkout with a commit.

Optional local CI-debug signing uses the corresponding `A4DY_DEBUG_KEYSTORE`, `A4DY_DEBUG_STORE_PASSWORD`, `A4DY_DEBUG_KEY_ALIAS` and `A4DY_DEBUG_KEY_PASSWORD` variables. Leave all four unset for ordinary local debug signing.
