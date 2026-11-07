# Releases

The source repository is `doccstat/flexible-esim-transfer`. Its signed, stable
releases are mirrored to the listing repository
`Xposed-Modules-Repo/com.lixingchi.flexibleesimtransfer`.

## One-time setup

1. Create the source repository and enable Actions. Submit the package to the
   LSPosed module directory and accept the listing repository's maintainer invite.
2. Choose a **permanent release signing key** and retain it outside the source
   tree. Never distribute a debug key or generate a replacement during a build.
3. Create the source repository's `release` environment, restricted to `main`.
   Add these environment secrets:
   - `ANDROID_KEYSTORE_BASE64`
   - `ANDROID_KEYSTORE_PASSWORD`
   - `ANDROID_KEY_PASSWORD`
   - `ANDROID_KEY_ALIAS`
4. Set the environment variable `ANDROID_SIGNING_CERT_SHA256` to the certificate's
   **64 lowercase hexadecimal SHA-256 digest**, not the APK checksum.
5. Add `LSPOSED_RELEASE_TOKEN`, a credential with Contents write access to the
   listing repository. The source repository's `GITHUB_TOKEN` cannot write there.

The private key and its base64 representation are secrets. Keep a durable copy
and use the same key for every update. The workflows never create keys.

A release signed with a different certificate cannot update the existing
same-package debug APK. Before deliberately switching signing identities, export
any saved diagnostics and record the selected destination/port/options; Android
requires removing the old APK before installing the new one. Uninstalling the
module loses its preferences and private report. This is not an automatic upgrade
or migration, and the release tooling never uninstalls an app or operates on SIM
profiles.

## Publication

- Version authority: `versionCode` and `versionName` in `app/build.gradle`.
  `python3 tools/release.py metadata` prints the exact tag; no automatic bump.
- Update `RELEASE_NOTES.md` with the same version's changes.
- Each `main` push runs Python/JVM tests, lint and an unsigned release build,
  then signs with the supplied permanent key. Signing credentials are available
  only to the signing step, not the build/test steps.
- Verification checks the signature, single pinned certificate, package ID,
  version and non-debuggable flag before publishing `FlexibleEsimTransfer.apk`
  and its SHA-256 file. A missing key/pin or failed check stops publication.
- The stable tag is **`versionCode-versionName`**; the current source declares
  **`7-0.4.1`**. A later push with the same declared version replaces that tag's
  source release and assets. Deliberately bump both values for a new app update.
- The distribution workflow resolves **GitHub Latest**, downloads those exact
  assets, rechecks checksum/signature/package/version, and creates or replaces
  the same stable listing release. It has no signing key and does not rebuild.
  Source publication dispatches distribution explicitly because `GITHUB_TOKEN`
  release writes do not trigger release-event workflows.
- Signing/distribution share a concurrency group. An old source build checks
  the current `main` SHA before publishing, so it cannot overwrite a newer push.

Pull requests run the same build checks without credentials and upload only an
unsigned APK artifact. Workflow actions and Gradle's wrapper/distribution are
checksum/revision pinned. Hosted publication needs the repositories, permissions
and secrets above; local tests cannot provision them.

## Local checks

Use JDK 17+, Android SDK 36 and build-tools 35.0.0 in a disposable build copy:

```sh
python3 -B -m unittest discover -s tools -p 'test_*.py'
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The wrapper pins Gradle 8.13. On Windows use `gradlew.bat`. To verify an already
signed release without exposing passwords:

```sh
SIGNING_CERT_SHA256=<certificate-digest> python3 tools/release.py verify \
  FlexibleEsimTransfer.apk 7-0.4.1 --checksum FlexibleEsimTransfer.apk.sha256
```

Set `ANDROID_HOME` to the SDK, or `BUILD_TOOLS` to its build-tools directory.
This command verifies only; it never signs, installs, or publishes an APK.
