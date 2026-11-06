# Flexible eSIM Transfer

Route Android’s incoming eSIM transfers and new profile downloads to a real
eUICC you choose, including a removable SIM-form-factor eUICC.

**0.2.0-routing implements destination routing, not just observation.** It is an
experimental build ready for a controlled trial, not a claim of a successful
carrier transfer. There is no carrier, country, phone-model or card-brand
allowlist. Your carrier and the destination card still have to support the
stock transfer and cryptographic download protocols.

## Requirements

- Android 13+, root, and modern libxposed API 101+, such as Vector.
- Google SIM Manager (`com.google.android.euicc`) and Google Play services
  (`com.google.android.gms`). Other manufacturers’ proprietary LPAs are not
  implemented by this adapter.
- A real eUICC recognized by Android, already mapped to an active modem port.
  USB/CCID-only readers and cards visible only to an OMAPI app are not Android
  telephony destinations. This module does not change modem slot mappings.

Both removable and built-in eUICCs can be selected. Card IDs and slots are
resolved from the actual inventory, never assumed to be slot 0 or 1. Private
LPA hooks are discovered by method signatures and application accessor return
types, rather than a device name, APK hash, or hardcoded obfuscated class name.
An incompatible LPA reports incomplete hooks; do not start a transfer then.

## Install and use

1. Install the APK on the **receiving phone only**. Enable
   `com.lixingchi.flexibleesimtransfer` in your framework. Scope **SIM Manager
   and Google Play services, owner user only**. Other packages/users are ignored.
2. Open **Flexible eSIM Transfer**. Enter the destination’s exact 32-digit EID
   from your existing LPA. Leave the port blank to use the **only active port**;
   with multiple active ports, enter the desired port index. Input is not trimmed
   or rewritten. Choose **Route to this eUICC** and confirm.
3. Close any existing transfer, then restart **both scoped processes** using
   your framework’s supported activation procedure. Do not blindly reboot a
   temporary-root phone: it may lose root. A stale process or previously issued
   transfer credential must not be reused after changing destination.
4. Check framework logs tagged `FlexibleEsimTransfer` for `hooks: READY:LPA`
   and `hooks: READY:GMS`, without `INCOMPLETE`. Then start a **fresh incoming
   transfer** through Android’s normal SIM settings, or a new QR-code download.
   Keep the stock authorization and confirmation steps.
5. Verify the profile is on the selected card with your existing LPA and test
   service. Close the flow, choose **Off / forget destination**, and restart
   both scoped processes to restore stock behavior.

**While enabled, this applies to all new downloads handled by SIM Manager,
not only transfers.** Its default destination displays and slot selectors also
point to the selected card. Do not use it on the source phone, perform unrelated
SIM maintenance, or change destination during a transfer. A real carrier flow
can deactivate the source line or consume an activation code even if installation
later fails. Use a recoverable test line first; do not repeatedly retry an
uncertain result without checking the card and carrier.

The settings app’s framework connection proves preference access, **not** hook
activation. Installing or selecting a destination does not initiate a transfer,
switch a modem slot, download/enable/delete a profile, or restart any process.
The native flow still performs its normal operations when you initiate it.

## Routing and safety

The selected card is used both when Play services prepares destination identity
and when SIM Manager downloads and enables the new profile. Explicit source-card
lookups stay real. The final slot/port and cryptographic card operations are
checked against the same destination; signed payloads are never rewritten.

Missing, ambiguous, inactive or moved targets do not fall back to the built-in
chip. After a pinned target disappears or settings change, that process requires
restart, even if the old selection is restored. The module never turns a native
error into success or retries a carrier/card transaction. The stock framework’s
port-resolution policy still applies; a conflicting port is rejected rather
than silently enabling another one.

This is not SIM cloning, an arbitrary EID/IMEI spoofer, a carrier-lock bypass,
or a replacement LPA. IMEIs, source subscription identities, authentication
material, capabilities, carrier policy and profile deletion are not patched.
Only the destination fingerprint and explicit port text are saved in private
framework preferences. The old observation-only settings do not enable routing
on upgrade. Module logs contain status labels, not EIDs, fingerprints, ICCIDs,
IMEIs or activation codes; stock software may have its own sensitive logging.

## Export diagnostics

With Python 3 and your existing ADB, run:

```sh
python tools/diagnostics.py --serial YOUR_ADB_SERIAL --root --output diagnostics.json
```

`--root` reads Vector’s module status, scope and logs through `su`; omit it for
versions and available logcat events only. Optionally add `--routing on` (or
`off`) and `--port auto` (or an index) to record the settings you see in the app.
These settings are explicitly labelled operator-reported, not independently
verified; defaults are `unknown`. The script changes no device state.

The report contains Android/app versions, module status, sanitized routing
and error/result events, and unavailable checks. Serial numbers, destination
identifiers, activation codes and raw logs are excluded. Review before sharing.
Nothing is uploaded, and existing output files are never overwritten. No events
does **not** establish hook activation or transfer success.

## Build and validation

Use JDK 17+, Gradle 8.13, Android SDK 36 and build-tools 35.0.0. Build in a
disposable copy:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python -B -m unittest discover -s tools -p 'test_*.py'
```

- `app/build/outputs/apk/debug/app-debug.apk`: debug-signed **test build**.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: unsigned release;
  supply your own durable signing key for distribution.

Unit tests exercise the production interceptors with Android/LPA doubles:
entitlement selection → card-bound download → slot verification → final card
operation, plus original errors, source lookups, cancellation/configuration
changes, removed cards and incomplete hooks. These tests do not exercise ART,
radio firmware, real eUICC cryptography or carrier servers. They cannot establish
universal compatibility. No proprietary APK or signing key is included.

Licensed under [MIT](LICENSE).
