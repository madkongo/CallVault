# Report: "Restricted by Advanced Protection" popup — Jose, OnePlus CPH2749, Android 17 (2026-10-31)

✅ Report closed 2026-10-31: the maintainer replied to Jose (cause = in-app updater vs Advanced Protection). The updater fix below is not built or scheduled.

**Phone:** OnePlus CPH2749, Android 17 (CPH2749_17.0.0.102), Shizuku mode, **2.4.7** (still — the update never landed).
Log: ~/Downloads/DOC-20261031-WA0049.txt (WhatsApp attachment).

## Cause of the popup: our in-app updater
- 17:30–17:31 (×4) and 18:02:27 / 18:02:50 (×2): `UpdateInstaller: Shizuku mode: no ADB shell for a silent install;
  using the system installer` → `PackageInstaller session committed`. The popup in the screenshot is at 18:02.
- 18:32:12: `Update install failed: status=3 message=INSTALL_FAILED_ABORTED: User rejected permissions`.
- Android's Advanced Protection blocks installs from unknown sources, which is exactly what the updater does.
- The updater keeps offering 2.4.8 and the user keeps hitting the block.

**Fix idea (not built):** on Android 16+, ask `AdvancedProtectionManager.isAdvancedProtectionEnabled()`; when on,
don't start an install — explain that Advanced Protection blocks app updates outside the Play Store and how to
update anyway. Needs the permission `QUERY_ADVANCED_PROTECTION_MODE` (check its protection level before relying on it).
Also worth checking whether Advanced Protection will block Wireless debugging in a future release (Play Services
strings suggest so; not shipped) — that would stop standalone and Shizuku modes entirely.

## Separate finding: recorder down ~21 h with Shizuku stopped
- 2026-10-30 20:33:44 `Shizuku stopped; calls will not be recorded until it is started again`; keep-alive got
  `NOT_RUNNING` every few minutes until 2026-10-31 18:01:37 (`Shizuku is running`).
- App calls missed: 12:27, 14:02, 14:07. Only 14:07 was reported: 12:27 and 14:02 hit the issue-#29 gate
  (`nothing on this phone is capturing for one`) — the same gate gap seen on Евгений's OnePlus Android 17
  (docs/dev-notes/2026-10-31-report-voip-echo-oneplus-a17.md). Two OnePlus A17 phones now; the gate's
  getActiveRecordingConfigurations evidence looks unreliable on this ROM.
- Why Shizuku stopped is not in our log.
