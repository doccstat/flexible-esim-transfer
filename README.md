# Flexible eSIM Transfer

Choose which real eUICC receives Android’s native eSIM transfers and new Google
SIM Manager downloads—including removable SIM-form-factor eUICCs. Includes a
card/port picker, optional session preservation, and private diagnostic export.

## Before you use it

**You can lose access to your eSIM and cellular service.** A transfer may
deactivate the source profile or consume an activation code even if the download
fails. Recovery may require your carrier to reissue the eSIM and may incur fees;
this module cannot guarantee recovery.

**Test with a recoverable, nonessential line first.** Arrange another way to
communicate and access your carrier account. You are responsible for your testing,
backups, recovery arrangements and decision to use this software. It is provided
**“AS IS”, without warranty**, under the [MIT License](LICENSE), including its
limitation of liability to the extent permitted by law.

## Choose one version

Download from [Releases](https://github.com/doccstat/flexible-esim-transfer/releases).
Every release includes both alternatives at the same version, with SHA-256 checksums.
Neither requires the other.

| Version | Requirements | Configuration |
| --- | --- | --- |
| **APK** | Root + Vector or another libxposed API 101 framework | Included app |
| **Zygisk ZIP** | KernelSU Manager + a Zygisk API 5 provider | Module WebUI |

Both require **Android 13+**, Google SIM Manager/Play services, and a real eUICC
recognized by Android on an active modem port. Carrier and device transfer support
still apply. **Enable only one routing backend.** Settings are independent.

## Quick start

1. **APK:** install and enable it for owner-user **SIM Manager** and **Google Play
   services**. Add **Phone** scope if using session preservation.
   **ZIP:** install through KernelSU Manager, then open its WebUI.
2. Activate using your device’s supported restart procedure. Preserve temporary
   root where applicable; restarting Phone interrupts cellular service.
3. Refresh cards, choose the destination and active port, then confirm. Manual
   EID entry is also available. Enable **Preserve card session** if your card
   needs a continuous authentication channel.
4. Restart affected processes after changing settings. Start a fresh transfer
   or download through Android’s normal SIM settings and verify service afterward.
5. To stop routing, choose **Off / forget destination** and restart again.

Routing affects **all new SIM Manager downloads**, including QR downloads.
A port index is not a physical slot number. See the [usage guide](docs/usage.md)
for activation, card selection and switching backends.

## Compatibility and support

Incoming transfers and transfers back to an iPhone have been reported working
with the Vector route. The standalone Zygisk route still needs device validation.
**Play Integrity interference remains unresolved; no integrity verdict is promised.**

Both interfaces provide **Start / Stop / Review / Export / Delete** diagnostics.
Recording is opt-in, redacted and bounded; nothing uploads automatically. Review
exports before attaching them to a [GitHub issue](https://github.com/doccstat/flexible-esim-transfer/issues).
Do not share EIDs, activation codes, QR codes or account credentials.

The interface currently uses English. [Build/release instructions](docs/releasing.md)
and [dependency licenses](https://github.com/doccstat/flexible-esim-transfer/blob/main/zygisk/VENDORED.md)
are available separately. Third-party components retain their own licenses.
