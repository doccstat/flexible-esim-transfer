# Flexible eSIM Transfer 0.4.1

- Choose a real built-in or removable eUICC and an active port for incoming
  Android eSIM transfers and all new Google SIM Manager downloads.
- Discover cards through the protected inventory bridge, or enter an EID
  manually. No destination is selected automatically.
- Optional **Preserve card session** maintains a real native channel lease across
  metadata authentication and the following download. Defaults off; requires
  owner-user Phone scope in addition to SIM Manager and Play services.
- Record bounded, private diagnostics on the phone, then review, export or delete
  them. No automatic recording or upload.
- A native incoming transfer completed on Android 17 with a removable eUICC and
  session preservation enabled.

Requires Android 13+, root, a modern libxposed API 101 framework, Google SIM
Manager and Play services, and an Android-visible active eUICC. Carrier
eligibility and cryptographic authorization remain native.

**Play Integrity:** this version injects into Play services through package-wide
framework scope and can interfere with integrity checks. The interference is not
yet resolved. Turning routing off does not unload the framework; framework
activation changes require a supported restart, preserving temporary root where
applicable.

The UI uses English Android resources and fallback. See the README for card/port
selection, activation and private diagnostic export, and `docs/releasing.md` for
signing-key compatibility when moving from a debug APK.
