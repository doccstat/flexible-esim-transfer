# Flexible eSIM Transfer

Route Android’s incoming eSIM transfers and new profile downloads to a real
eUICC you choose, including a removable SIM-form-factor eUICC.

Includes automatic eUICC discovery and an explicit card/port picker.
**0.4.1 adds optional native card-session preservation for metadata and downloads.**
On-device redacted diagnostic recording and manual export are included.
A native incoming transfer has completed on Android 17 with a removable eUICC
and session preservation enabled. There is no carrier, country, phone-model or
card-brand allowlist. Your carrier and the destination card still have to support
the stock transfer and cryptographic download protocols.

**Play Integrity interference remains unresolved:** package-wide Play services
scope loads the framework into more than the transfer process. Turning routing
off does not unload that injection. Removing it requires disabling the module in
the framework and restarting affected processes with the supported,
root-preserving procedure.

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
   and Google Play services, owner user only** (optional Phone scope below).
   Other packages/users are ignored.
   Close existing transfers and activate the module in both processes using your
   framework’s supported, root-preserving restart procedure. Leave routing off.
2. Open **Flexible eSIM Transfer**. It reads the real eUICCs and their ports from
   SIM Manager. Choose a card’s active port; nothing is selected automatically.
   Built-in/removable status, physical slot, logical slot and inactive ports are
   shown. Use **Show full EIDs** if needed, then **Route to this eUICC** and confirm.
   **Enter EID manually** remains available if discovery cannot read your card.
3. If your card needs a continuous authentication channel, choose **Preserve card
   session: OFF → Enable session preservation**. Also scope **Phone
   (`com.android.phone`), owner user**; the option defaults off on new installs
   and upgrades. It requires compatible native session APIs, described below.
4. Close any existing transfer, then restart **all scoped processes** using
   your framework’s supported activation procedure. Restarting Phone interrupts
   cellular service. Do not blindly reboot a
   temporary-root phone: it may lose root. A stale process or previously issued
   transfer credential must not be reused after changing destination.
5. Check framework logs tagged `FlexibleEsimTransfer` for `hooks: READY:LPA`
   and `hooks: READY:GMS`, plus `hooks: READY:PHONE` when preserving sessions,
   without `INCOMPLETE`. Then start a **fresh incoming
   transfer** through Android’s normal SIM settings, or a new QR-code download.
   Keep the stock authorization and confirmation steps.
6. Verify the profile is on the selected card with your existing LPA and test
   service. Close the flow, choose **Off / forget destination**, and restart
   all scoped processes to restore stock behavior. This also disables session
   preservation.

**While enabled, this applies to all new downloads handled by SIM Manager,
not only transfers.** Default destination identity and download-slot selection
point to the selected card; hardware slot lists keep their native classification.
Do not use it on the source phone, perform unrelated
SIM maintenance, or change destination during a transfer. A real carrier flow
can deactivate the source line or consume an activation code even if installation
later fails. Use a recoverable test line first; do not repeatedly retry an
uncertain result without checking the card and carrier.

The settings app’s framework connection or detected card list does **not** prove
that all routing hooks are active. Installing or selecting a destination does
not initiate a transfer, switch a modem slot, download/enable/delete a profile,
or restart any process.
The native flow still performs its normal operations when you initiate it.

## Cards, EIDs and ports

Discovery works with routing off. It requires the module to be loaded in a
running SIM Manager process; this app never launches or restarts that process.
Use **Refresh cards** after activating the module or changing a card. Failed,
empty and timed-out scans are distinguished and never change the saved destination.
A refresh clears an unsaved detected-card choice rather than selecting a replacement.

Port indexes belong to each eUICC; they are **not physical SIM slot numbers**.
There is no universal valid range. Only active ports with valid Android mappings
are selectable. Inactive ports remain visible, but the app does not activate them.

For manual entry, use the exact 32-digit EID from your existing LPA. Matching a
card from the scan shows its ports and validates your choice. Leave the port
blank for the **only active port**, or enter a reported active index. Multiple
active ports require an explicit index. Without a matching scan, the confirmation
marks the choice unverified; routing must still find that real card and port.
Input, including port text, is not trimmed or rewritten.

EIDs are masked by default. Discovered EIDs exist only in memory and in a
package-restricted reply to the settings app; the reader requires the module’s
signature-level permission. No ICCIDs or subscription details are transported.
Only the selected EID’s fingerprint, port and session-preservation option are saved. Scanned cards are cleared
when leaving the screen; screenshots and autofill are disabled.

The UI currently ships in English, using Android string resources as the source
and fallback for every device language. There is no separate language selector;
additional translation locales have not been selected.

## Routing and safety

The selected card is used both when Play services prepares destination identity
and when SIM Manager downloads and enables the new profile. Explicit source-card
lookups stay real. Physical-tray presence and built-in/removable slot lists are
not rewritten: the native UI uses those lists to choose its setup/transfer entry.
No tray is invented on eSIM-only hardware, and native carrier/transfer availability
checks are unchanged. The final slot/port and cryptographic card operations are
checked against the same destination; signed payloads are never rewritten.

Missing, ambiguous, inactive or moved targets do not fall back to the built-in
chip. After a pinned target disappears or settings change, that process requires
restart, even if the old selection is restored. Slot-status queries report no
usable slot while blocked, rather than throwing the routing exception during
service startup. Transfer options may remain unavailable until restart; this
does not enable transfers or fall back to another card. The module never turns
a native error into success or retries a carrier/card transaction. The stock
framework’s port-resolution policy still applies; a conflicting port is rejected
rather than silently enabling another one.

This is not SIM cloning, an arbitrary EID/IMEI spoofer, a carrier-lock bypass,
or a replacement LPA. IMEIs, source subscription identities, authentication
material, capabilities, carrier policy and profile deletion are not patched.
Routing preferences contain only the destination fingerprint, explicit port
text and session-preservation option. The old observation-only settings do not enable routing on upgrade.
Module logs contain status labels, not EIDs, fingerprints, ICCIDs, IMEIs or
activation codes; stock software may have its own sensitive logging.

## Preserve card session

Some eUICCs discard authentication context when Android closes the logical channel
between the challenge and server authentication. With this **default-off** option,
the Phone adapter starts a real native session for Google SIM Manager’s routed
metadata, default-list discovery and downloads. It counts that lease only for the selected port’s actual
APDU sender, not other cards. Native authorization, payloads, errors and results
are unchanged; no authentication is simulated or retried.

The lease survives metadata completion so SIM Manager can continue its stored
transaction in the following download. It ends when the native service connection
exits or unbinds (including disconnect and native idle timeout), or dispatch throws.
It does not keep the service alive or extend native timeouts. Other native session
owners remain responsible for their own channels. Avoid concurrent SIM maintenance.

This option needs `EuiccSession` and compatible `EuiccConnector`/APDU APIs in Phone;
Android versions without them can still use destination routing with the option
off. Missing Phone scope is not detected automatically: verify `READY:PHONE` before
an attempt. Hook readiness does not prove actual channel reuse. `card_session:
STARTED` means a native lease was acquired, not carrier acceptance. A card session
error can have other causes; a real transfer still needs device validation.
Changing this option requires restarting all scoped processes, just like changing
the destination. The app does not restart them or change framework scope for you.

## Record and export diagnostics

No computer or live maintainer connection is needed:

1. Open **Diagnostics → Start recording**, allow notifications, and explicitly
   choose **5, 15, 30 or 60 minutes**. Approve the app’s root request. Wait for
   **Recording redacted events** before making your own attempt.
2. **Stop recording** in the app or its notification, then **Review report →
   Export file…**. Android’s file picker lets you choose the name and destination.
   A cloud document provider uploads only if you choose it as the destination.
3. Use **Delete saved report** when finished. Starting another recording asks
   before replacing the previous report; export it first to keep both.

Recording is **off by default** and never resumes automatically. It stops at the
chosen duration (including root approval), at **1 MiB**, or when you stop it;
older records are not silently discarded. Root/reader startup has a 30-second
limit. The saved JSONL stays in app-private, backup-excluded storage after stopping
or reopening the app. A missing `capture_end` indicates interruption. Deleting
app data or uninstalling removes the private report, not exported copies.

Reports include installed versions, Android release/API/security patch, saved
routing on/off, port and session-preservation option (or unknown), elapsed receipt
times, module readiness and routing/session events when emitted, native card/download errors, and Android pairing,
check-in, integrity-token, attestation-exchange and peer-status categories.
Destination identifiers and fingerprints, phone numbers, credentials, QR/session
material, tokens, signed data, APDUs and raw exception text are never saved.
The app has no network permission and does not upload reports automatically.

The recorder reads existing logs for the owner-user module, SIM Manager, Play
services and Phone through `su`; recording adds no routing or attestation hooks. It changes no log
levels, clears no buffers, starts/restarts no phone apps and initiates no transfer.
**Reader started is not hook readiness or transfer success.** The initial tail can
predate capture. Unknown messages are counted rather than saved, and suppressed
messages and the other phone’s logs remain unavailable. Nearby categories can
also belong to other Play services features: correlate the timing, not just a code.

### Optional host tools

Keep the scripts with their `app/src/main/resources/diagnostic-rules.tsv`
allowlist. With Python 3 and your existing ADB, run:

```sh
python tools/diagnostics.py --serial YOUR_ADB_SERIAL --root --output diagnostics.json
```

`--root` reads Vector’s module status, scope and logs through `su`; omit it for
versions and available logcat events only. Optionally add `--routing on` (or
`off`) and `--port auto` (or an index) to record the settings you see in the app.
These settings are explicitly labelled operator-reported, not independently
verified; defaults are `unknown`. The script changes no device state.

The report contains Android/app versions, module status, sanitized routing,
inventory-reader status and error/result events, allowlisted routing-exception
codes from Android’s crash buffer, and unavailable checks. Crash records may
predate the current build or process; they are not current routing status. Serial
numbers, destination identifiers, activation codes and raw logs are excluded.
Review before sharing.
Nothing is uploaded, and existing output files are never overwritten. No events
does **not** establish hook activation or transfer success.

For a separately authorized live attempt, start a bounded capture first:

```sh
python tools/capture_logs.py --serial YOUR_ADB_SERIAL --root --seconds 1800 --output capture.jsonl
```

Wait for `Live redacted capture ready` before using the phone. The collector
saves only known module events, native error codes and Android transfer-pairing
callbacks, not raw messages, QR/session material, APDU bodies, identities or
credentials. It cannot read the other phone's logs or recover callbacks Android
did not emit. The duration is explicit (1–3600 seconds);
Ctrl+C stops early. Files are create-only. The initial log tail can be older than
the capture, and unavailable/suppressed logs cannot be recovered. No apps are
started/restarted, log levels changed, buffers cleared or transfers initiated.
Both recorders use the same reviewed allowlist; neither saves unrecognized raw
messages. Existing installations before 0.4.0 have only the host tools.

## Build and validation

Use JDK 17+, Gradle 8.13, Android SDK 36 and build-tools 35.0.0. Build in a
disposable copy:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
python -B -m unittest discover -s tools -p 'test_*.py'
```

- `app/build/outputs/apk/debug/app-debug.apk`: debug-signed **test build**.
- `app/build/outputs/apk/release/app-release-unsigned.apk`: unsigned release.

The wrapper pins Gradle 8.13; use `gradlew.bat` on Windows.
[Release setup](docs/releasing.md) describes the permanent signing key, stable
`versionCode-versionName` releases, certificate/checksum verification and LSPosed
listing distribution. CI never generates a signing key or chooses a version.

Unit tests exercise the production interceptors with Android/LPA doubles:
entitlement selection → card-bound download → slot verification → final card
operation, plus original errors, source lookups, cancellation/configuration
changes, removed cards, non-throwing slot polling, unchanged hardware slot lists
and incomplete hooks. Native-session doubles cover challenge lifetime, metadata →
download continuity, opt-out, other cards/owners, service disconnect, cleanup,
configuration/card changes, unchanged errors and required deoptimization. They
model a card that loses its challenge on channel closure, not proof that every
real card does. Discovery tests cover real-inventory conversion,
active/inactive and nonconsecutive ports,
manual fallback, protected request setup, failed/empty scans, timeout/cancellation
and redacted diagnostics. Recorder tests share Android/Python privacy fixtures
and cover root denial, stop, elapsed-time expiry, create-only persistence,
capacity and exact reader cleanup on stop/EOF/timeout. Diagnostic labels are
machine-readable; UI strings and duration plurals use the existing English
Android resource catalog.
These tests do not exercise the live UI, Android permission enforcement, ART,
radio firmware, real eUICC cryptography or carrier servers. They cannot establish
universal compatibility. No proprietary APK or signing key is included.

Licensed under [MIT](LICENSE).
