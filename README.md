# Flexible eSIM Transfer

Choose the real eUICC you want a carrier-supported transfer to reach — including
an eUICC in a removable SIM card.

**Status: 0.1.0-preflight, an observation-only Vector module. It cannot redirect,
transfer, download, or activate an eSIM. No successful transfer is claimed.**
The current build checks where the stock software is pointing before a routing
implementation is attempted. Do not start a transfer merely to test it: the
stock flow still runs normally and can deactivate the source line.

## Why not just replace the EID?

A destination has an EID **and** an Android card ID, physical slot and port.
The carrier must authorize the replacement, and the actual destination eUICC
must perform the cryptographic profile-download exchange. Changing only a
reported EID does not move that exchange or unlock an existing profile.

This project is not SIM cloning, a carrier-lock bypass, an arbitrary EID
spoofer, or a replacement LPA. Future routing must keep entitlement metadata
and the download endpoint on the same real, explicitly selected card.

## Preflight module

- Android 13+ and modern libxposed API 101+, such as Vector.
- Package: `com.lixingchi.flexibleesimtransfer`.
- Scope **only SIM Manager (`com.google.android.euicc`), owner user**. Other
  packages and Android profiles are ignored, even if added to scope.
- Observation defaults **off**. Enter the removable card's exact 32-digit EID
  from your existing LPA, then choose **Observe this destination**. Input is
  validated without trimming or rewriting. Only its SHA-256 fingerprint is
  saved through the framework's private remote preferences, not the raw EID.
- **Off / forget destination** clears the saved fingerprint. A failed save
  reports unknown state and requires reconnecting the settings app; it is not
  presented as a successful disable.
- No root commands, APDUs, carrier requests, clipboard access, profile actions,
  device-setting writes or automatic process restarts. Settings changes affect
  only this module's observation. Installing a module is itself a device change.
- Existing system queries provide the card inventory. A candidate must match
  uniquely, be removable and eUICC-capable, have a valid card/slot address and
  an active port. There is no first-card or built-in-card fallback.

The standard API hooks observe default card selection, explicit card binding,
EID results and **already initiated** download requests. Every original call
runs once with its original arguments; its result or exception is preserved.
A download-request log is not confirmation of installation.

Private LPA hooks are restricted to Android API 37 and this exact SIM Manager
APK, version `D.2.1.989982212-google` / `306110`:

```text
SHA-256 579f75da460e6a6137a1f2b39e603e20999b4f55c2ec1bde51acc4feac1c6760
```

They observe preferred/active slot selection and the target EID carried by
transfer metadata. On other builds, only standard API observation is attempted.
Missing hooks are reported as incomplete, not success. Inlined calls and code
in Google Play services may escape these hooks; absence of an event proves
nothing. This is not yet an end-to-end or transaction-correlated preflight.

Enable and activate through your framework's supported procedure. **No device
activation has been validated.** Temporary-root devices may lose root on an
ordinary reboot; do not reboot them just to follow generic module instructions.
The settings app's connection status confirms only preference access, not
loaded hooks. Inspect logs tagged `FlexibleEsimTransfer`: installation status,
selection state, and match booleans only. This module does not log EIDs, their
fingerprints, ICCIDs, IMEIs, activation codes, raw metadata or exceptions.
Stock apps and other modules may still log sensitive data.

## Build and tests

Use an existing JDK 17+, Gradle 8.13, Android SDK 36 and build-tools 35.0.0.
Run in a disposable build copy; no build step accesses a phone:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

`app/build/outputs/apk/release/app-release-unsigned.apk` is deliberately unsigned.
Choose your own signing key before installation. There is no updater, public
release, automatic installation or bundled proprietary APK.

## Before enabling real routing

1. Validate hook loading and redacted read-only observations on the actual build.
2. Determine the source device/carrier's transfer protocol and where Google Play
   services produces destination-bound credentials. Never rewrite a credential
   after it has been issued for another EID.
3. Add an explicit destination selector spanning authorization, card/slot/port
   routing and the actual LPA transaction. Refuse missing, replaced, ambiguous
   or stale targets; never fall back to the built-in eUICC mid-operation.
4. Separate installation, profile enabling and source deletion confirmations;
   preserve cancellation/recovery without automatically repeating carrier calls.
5. Test with an expendable, carrier-supported line before publishing a working
   transfer claim. A successful local hook test cannot establish carrier support.

Licensed under [MIT](LICENSE).
