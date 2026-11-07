# Usage

Read the [eSIM risk notice](../README.md#before-you-use-it) before a real transfer.

## Activation and switching backends

- **APK:** enable `com.lixingchi.flexibleesimtransfer` in an API 101 framework.
  Scope owner-user `com.google.android.euicc` and `com.google.android.gms`;
  also scope `com.android.phone` for session preservation. Open the included app.
- **ZIP:** install `FlexibleEsimTransfer-Zygisk.zip` through KernelSU Manager
  with an active Zygisk API 5 provider. Configure it in the module WebUI—no
  Flexible eSIM Transfer APK or Vector framework is needed. It targets owner-user
  Phone, SIM Manager and Play services main/UI, not other GMS workers.

Close existing transfers and use the device/framework’s supported activation
procedure. Restart all affected processes after changing destination, port or
session preservation. Restarting Phone interrupts cellular service. A normal
reboot can lose temporary root; use the appropriate root-preserving procedure.

Use one backend at a time. Disable the old backend, enable the chosen one and
restart affected processes. Version 0.5.0 blocks routing with `BACKEND_CONFLICT`
if both implementations load in the same process. Older APKs lack this guard.
Neither route changes scope, disables modules, restarts Android or migrates the
other’s settings automatically. Other unrelated modules may stay enabled.

Look for `hooks: READY:LPA` and `hooks: READY:GMS` in diagnostics, plus
`hooks: READY:PHONE` when preserving sessions. Do not proceed after `INCOMPLETE`.
Saved settings, module installation and recorder readiness are not hook readiness.
A fresh recording sees readiness only when the process emits it.

## Destination and session settings

Routing and session preservation default off on a fresh installation. Discovery
works with routing off, from a running SIM Manager (APK) or Phone (ZIP) process.
Refresh does not launch those processes or automatically select a destination.
Select an active port on a real card. Inactive ports remain visible but are not
activated by this module. Physical slot, logical slot and port are different.

Manual entry accepts the exact 32-digit EID. Leave port blank for the **only
active port**, or enter its reported index; multiple active ports require an
explicit choice. Input is not trimmed or rewritten. Manual choices without
inventory verification require confirmation and must still match at runtime.
Missing, moved, inactive or ambiguous targets never fall back to another card.
Removal or settings changes latch a restart requirement even if restored later.

**Preserve card session** keeps a real native lease for the selected card across
metadata authentication and download. It needs compatible Phone session APIs and
defaults off. It respects native ownership/timeouts; card errors, signed material
and authorization stay unchanged. Avoid unrelated SIM maintenance during a flow.

**Off / forget destination** clears that backend’s selection and preservation
option, not installed profiles or carrier state. Restart to apply. Routing off
does not unload hooks; disable the backend and restart to remove its injection.
The ZIP’s narrower Java/ART process selection does not guarantee Play Integrity.

## Private diagnostics

Choose **5, 15, 30 or 60 minutes**, then wait for **Recording redacted events**.
Recording stops on request, at the duration or at **1 MiB**. It never resumes or
uploads automatically. Closing the WebUI does not stop a timed recording.

- **APK:** use Diagnostics and approve its root/notification requests. Stop
  from the app or notification. Export uses Android’s file picker.
- **ZIP:** use WebUI Start/Stop/Review/Export/Delete. Export writes to owner-user
  Downloads using the exact filename you enter, including extension. Existing
  files are never replaced. Export and explicitly delete an old report before
  starting another.

Reports include versions, saved setting labels, readiness and known error/result
codes. They exclude destination identities/fingerprints, credentials, QR/session
material, tokens, signed data, APDU bodies and arbitrary exception text. Unknown
messages are omitted. A missing `capture_end` means interruption, not success.
No recorder changes log levels, clears buffers, restarts apps or starts a transfer.
Review a report before sharing; other software may log sensitive data separately.

The APK keeps reports in private, backup-excluded storage; uninstall removes them.
The ZIP keeps settings/reports root-only under `/data/adb/flexible-esim-transfer`
and **retains them on uninstall**. Forget the destination and delete reports
explicitly if desired. Exported copies remain wherever you saved them. EIDs are
masked by default. APK screenshots are disabled; WebUI screenshot policy belongs
to the manager, so keep identifiers masked when sharing a screen.

Optional read-only host tools, with an explicit ADB serial:

```sh
python tools/diagnostics.py --serial YOUR_ADB_SERIAL --root --output diagnostics.json
python tools/capture_logs.py --serial YOUR_ADB_SERIAL --root --seconds 1800 --output capture.jsonl
```

UI text uses English Android resources (APK) and `webroot/strings.mjs` (WebUI)
as source and fallback. Additional locales have not been selected; there is no
separate language selector. EIDs, port input and exported content are not translated.

The status tool’s framework checks describe the Vector route. Both tools create
new output files only, redact known events and never upload anything.
