# Flexible eSIM Transfer 0.5.4

Fix standalone application startup when another hook engine deoptimizes
`Instrumentation.newApplication`. Bootstrap now hooks `Application.attach` once,
passes the real context directly to the shared router, and starts Phone inventory
only after native attachment succeeds. Application-construction callers are
deoptimized to prevent inlining from bypassing attachment. Native exceptions
remain unchanged. The companion-owned settings transport and fail-closed guards
from 0.5.3 are retained. Settings, profiles, scopes and restart behavior are
unchanged.

Two independent alternatives, included together in every release:

- **`FlexibleEsimTransfer.apk`** — existing Vector/libxposed route, with its own
  settings, card picker and private diagnostic recorder.
- **`FlexibleEsimTransfer-Zygisk.zip`** — standalone KernelSU WebUI and native
  Zygisk/LSPlant backend. No Flexible eSIM Transfer APK or Vector required.
- Both use versionCode **12**, version **0.5.4**, and have individual SHA-256 files.
- Concise setup documentation and visible eSIM-loss warnings in both interfaces.
  Test a recoverable line first; recovery and use are your responsibility under
  the existing MIT warranty/liability terms.

Both routes share real destination/port selection, fail-closed download guards,
optional native card-session preservation and bounded redacted diagnostics.
The ZIP targets owner-user Phone, SIM Manager and Play services main/UI only;
other processes are rejected before Java/ART initialization. Its native bootstrap
still passes through the Zygisk provider before rejected processes unload it.

Use **one backend at a time**. New versions detect both implementations in the
same process and block routing; older APKs lack this guard. Settings remain
independent. No installer or UI silently disables modules, changes scope, migrates
preferences, restarts Android, or operates on SIM profiles.

Requires Android 13+, Google SIM Manager/Play services and a real Android-visible
active eUICC. The APK needs a libxposed API 101 framework. The ZIP needs KernelSU
Manager and a Zygisk API 5 provider. ZIP settings/diagnostics are root-only;
uninstall retains them. Export/delete/forget are explicit choices.

The startup correction has host/build checks; **device activation, inventory,
carrier-transfer and integrity validation remain pending**. Play Integrity
interference remains unresolved; no DEVICE/STRONG result is promised. Native authorization, signed data, card errors
and carrier eligibility are not bypassed. Activation requires the device’s
supported restart procedure; preserve temporary root where applicable.
