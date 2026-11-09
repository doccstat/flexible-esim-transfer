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

Ordinary Android signature enforcement rejects an update signed by a different
certificate. Check the installed environment before choosing a migration; do not
assume an uninstall is needed on systems with explicitly modified signature checks.
Export diagnostics and retain settings before any deliberately chosen data-losing
migration. Release tooling never uninstalls an app or operates on SIM profiles.
The ZIP is independent of APK installation/signing and needs no companion APK.

## Publication

- Version authority: `versionCode` and `versionName` in `app/build.gradle`.
  `python3 tools/release.py metadata` prints the exact tag; no automatic bump.
- Update `RELEASE_NOTES.md` with the same version's changes.
- Each `main` push runs Python/native-policy/WebUI/JVM checks, lint, an unsigned
  APK build and a four-ABI standalone ZIP build, then signs the APK with the
  supplied permanent key. Signing credentials are available
  only to the signing step, not the build/test steps.
- Every release contains `FlexibleEsimTransfer.apk`,
  `FlexibleEsimTransfer.apk.sha256`, `FlexibleEsimTransfer-Zygisk.zip`, and
  `FlexibleEsimTransfer-Zygisk.zip.sha256`.
- APK verification checks its signature, single pinned certificate, package ID,
  version and non-debuggable flag. ZIP verification checks the matching version,
  controller/hook DEX equality, four native ELF architectures, file manifest,
  safe archive layout, licenses and checksum. A missing artifact/key/pin or failed
  check stops publication. ZIP checksums are not APK-style signatures.
- The stable tag is **`versionCode-versionName`**; the current source declares
  **`12-0.5.4`**. A later push with the same declared version replaces that tag's
  source release and assets. Deliberately bump both values for a new app update.
- The distribution workflow resolves **GitHub Latest**, downloads those exact
  APK and ZIP assets, rechecks their checksums/identity/version, and creates or replaces
  the same stable listing release. It has no signing key and does not rebuild.
  Source publication dispatches distribution explicitly because `GITHUB_TOKEN`
  release writes do not trigger release-event workflows.
- Both publishers stage uploads as drafts and publish only after all four assets
  upload successfully. A failed upload leaves a draft for an explicit workflow
  rerun, rather than exposing a partly replaced pair.
- Signing/distribution share a concurrency group. An old source build checks
  the current `main` SHA before publishing, so it cannot overwrite a newer push.

Pull requests run the same build checks without credentials and upload an
unsigned APK plus the standalone ZIP/checksum. Workflow actions and Gradle's wrapper/distribution are
checksum/revision pinned. Hosted publication needs the repositories, permissions
and secrets above; local tests cannot provision them.

## Local checks

Use JDK 17+, Android SDK 36 and build-tools 35.0.0 in a disposable build copy:

```sh
python3 -B -m unittest discover -s tools -p 'test_*.py'
./gradlew --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:assembleRelease :zygisk:lintRelease :zygisk:assembleRelease
```

The wrapper pins Gradle 8.13. On Windows use `gradlew.bat`. To verify an already
signed release without exposing passwords:

```sh
SIGNING_CERT_SHA256=<certificate-digest> python3 tools/release.py verify \
  FlexibleEsimTransfer.apk 12-0.5.4 --checksum FlexibleEsimTransfer.apk.sha256
```

Set `ANDROID_HOME` to the SDK, or `BUILD_TOOLS` to its build-tools directory.
This command verifies only; it never signs, installs, or publishes an APK.

### Standalone ZIP

Use an existing JDK 21, SDK 36/build-tools 35.0.0, NDK **30.0.16248370**, CMake
**3.31.0** and Ninja. Gradle resolves the pinned libxposed **API contract AAR**;
pass its local path (not the service AAR). Run `:zygisk:assembleRelease` first:
the Android library task compiles the payload against Android’s Java APIs, not
the host JDK’s APIs. No installed framework is needed.
From a disposable source copy, for example on Linux x86_64:

```sh
python3 tools/build_zygisk.py \
  --sdk "$ANDROID_HOME" --ndk "$ANDROID_HOME/ndk/30.0.16248370" \
  --java "$JAVA_HOME" --api /path/to/api-101.0.0.aar \
  --cmake "$ANDROID_HOME/cmake/3.31.0/bin/cmake" \
  --ninja "$ANDROID_HOME/cmake/3.31.0/bin/ninja" \
  --out "$HOME/.cache/flexible-esim-transfer/build-0.5.4"
python3 tools/release.py verify-zygisk \
  "$HOME/.cache/flexible-esim-transfer/build-0.5.4/FlexibleEsimTransfer-Zygisk.zip" \
  12-0.5.4 --checksum "$HOME/.cache/flexible-esim-transfer/build-0.5.4/FlexibleEsimTransfer-Zygisk.zip.sha256"
```

Windows is supported with the existing SDK/NDK executables (`cmake.exe`,
`ninja.exe`) and equivalent paths. The output directory must not exist; no tool
installer runs. C++ modules compile explicitly with the NDK Clang compiler, so
the SDK’s older Ninja is sufficient for the remaining Dobby build.

All native dependency source is vendored at pinned revisions. The same command
rebuilds/relinks with modified dependency sources; keep licenses and corresponding
source available when distributing a modified build. The ZIP includes the versioned
source URL. Both backends remain independently configurable and use one declared
version; do not publish an APK-only or ZIP-only release.
