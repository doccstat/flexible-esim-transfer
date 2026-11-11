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

For ZIP discovery failures, `bootstrap: SELECTED` means the native process filter
matched; `bootstrap: JAVA_ENTRY` means the Java application hook was installed,
not that routing is ready. `bootstrap: FAILED:<stage>` identifies payload,
companion, DEX loader, ART initialization, DEX trust, Java entry or application
attachment failure. `inventory_bridge: READY` means Phone bound its inventory
socket; a successful Refresh must still read the real cards. These fixed labels
are included in private diagnostics when recording is explicitly started before
the device's supported activation procedure. No diagnostic action restarts it.

The standalone bootstrap owns one `Application.attach` hook and supplies that
context directly to the router. It deoptimizes application-construction callers,
not an already-hooked `newApplication` target. Inventory starts only after native
attachment succeeds; another framework may remain enabled for unrelated modules.

`settings_channel: READY` means the standalone native reader received a bounded
settings response; `UNAVAILABLE` means it could not. The companion supplies a
private socket before specialization instead of reusing the zygote-labelled
connection in app processes. Its two-second read/write timeouts are set by the
root companion before handoff, not by the receiver. No SELinux policy changes or
public socket are used.
When settings or routing are unavailable, inventory keeps the unmodified native
card list and logs the block reason; this does **not** unblock downloads or other
protected operations. Native inventory permission errors still propagate.

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

Choose **5, 15, 30 or 60 minutes**, then wait for the live status line to show
**Recording redacted events**; **Starting reader** is not ready yet.
Recording stops on request, at the duration or at **1 MiB**. It never resumes or
uploads automatically. Closing the WebUI does not stop a timed recording: the
ZIP starts its worker through the ordinary root shell in a separate process
session, independent of the Manager's app lifecycle.

- **APK:** use Diagnostics and approve its root/notification requests. It works
  with the routing module disabled. Stop from the app or notification. Export
  uses Android’s file picker.
- **ZIP:** use WebUI Start/Stop/Review/Export/Delete. Export writes to owner-user
  Downloads using the exact filename you enter, including extension. Existing
  files are never replaced. Export and explicitly delete an old report before
  starting another.

Reports include versions, saved setting labels, readiness, known error/result
codes and allowlisted native handshake frame types/disconnection reasons. A frame
write is not proof of delivery or acceptance; a peer's reason is not a decoded
integrity verdict. Local and remote channel closures are recorded separately.
Reports exclude destination identities/fingerprints, credentials, QR/session
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
