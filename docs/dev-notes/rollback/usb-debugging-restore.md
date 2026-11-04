# Rollback: switch USB debugging back on when the phone turns it off, and record why it went off

🧪 VERIFYING — built 2026-11-04 on `fix/usb-debugging-turned-off` (from `v2.4.9`), test build `2.4.9-usbprobe1`
(versionCode 20490). 2403 unit tests pass. Measured on the OP9 only (OnePlus; not a phone that does this by
itself — the "off" was a root script). **Not seen on an Honor or Samsung phone.** To settle: the Honor reporter
(BVL-N59, MagicOS 10 / Android 16, standalone, Russia, via the maintainer on Telegram) installs the build, uses the
phone for a day, then sends a debug report. Look for the "USB debugging switched off (always on)" section.

## The bug

- Report 2026-11-04 (BVL-N59): "I need to enable USB debugging several times a day." Earlier the same on a Samsung
  S24 FE (2026-10-05). CallVault never writes it off; the phone does.
- Honor/Huawei, by design (Honor support, club.honor.com thread 28450978): with Developer options ▸ **"Allow ADB
  debugging in 'Charge only' mode"** (`Settings.Global allow_charging_adb`) off, the framework writes
  `adb_enabled=0` whenever USB is in charge-only mode — i.e. whenever the phone is unplugged, or the USB default is
  Charging only (which our 2.4.x USB warning recommends). 📐 From Huawei's decompiled `UsbDeviceManager` and
  community reports; not read on an Honor build.
- Away from Wi-Fi nothing brought `adbd` back: Wireless debugging needs Wi-Fi.
- And the USB-debugging observer in `DaemonKeepAliveService` never acted on an off at all: at the instant the setting
  changes `adbd` is still running, so `UsbDebuggingPolicy` reads ON (rule R1). Measured on the OP9 2026-11-04 —
  the event file stayed empty; recovery came only from the Wi-Fi path.

## What changed

- Observer: when the raw setting reads 0 but the state is not yet proven off, waits up to 5 s for `adbd` to stop,
  then handles the off (`onUsbDebuggingProvenOff`).
- `UsbDebuggingRestorePolicy` (pure, tested): **RESTORE** when the screen is off or locked (cannot be the user);
  **ASK** when the screen is on and unlocked, or after 3 automatic restores in 10 minutes (no tug-of-war);
  **NONE** outside standalone mode or without WRITE_SECURE_SETTINGS.
- `UsbDebuggingRestorer`: RESTORE waits 1.5 s, writes `adb_enabled=1` (same as the thedjchi Shizuku fork's
  `AdbStartWorker`), waits up to 6 s for `adbd`. Falls back to the old Wireless-debugging revival if not back.
- Readiness notification: "Turn USB debugging on" button (`UsbDebuggingActionReceiver`, not exported) when USB
  debugging is proven off and recording is paused/down. The tap also writes `allow_charging_adb=1` when it reads 0.
  New string `notif_action_turn_usb_on` × 15 locales.
- Always-on record `files/usb_debugging_events.txt` (128 KB cap): each event's screen/lock/power/USB/Wi-Fi/
  `allow_charging_adb` and the decision; after the recorder is back, the debugging/USB log lines from 15 s before to
  10 s after (daemon key `logcat_since`, argument validated, filtered + capped on the device), with pid → process.
  In the debug report after the setup journal. Report header gains "Allow ADB in charge-only mode" and "USB default".

## Measured (OP9, 2026-11-04)

- Screen locked, Wi-Fi off, `adb_enabled=0` written by root: back on in 1.8 s (`setAdbEnabled: true` 1.7 s after
  the off), recorder reconnected on the open :5555 port without Wi-Fi.
- The unfiltered log read failed ("Transaction failed on small parcel") — fixed by filtering on the device.

## Not tested

- The ASK path and the button tap (the OP9 has a secure lock; I could not unlock it).
- Whether Honor accepts an app's `adb_enabled=1` / `allow_charging_adb=1` write, or switches it straight off again
  (the loop guard then stops after 3 and asks). The event file will show it.
- Android 17 redaction: the raw setting always reads 0 there, so every change starts a 5 s look that ends when
  `adbd` stays up. Reasoned, not run.

## How to roll back

```
git revert -m 1 d8274549
```

(`d8274549` = the merge of `fix/usb-debugging-turned-off` into integration/next.) Or per commit: `3c425258` (restore + button + observer timing), `a5db080f` (diagnostics only).

## Report 2 — 2026-11-04 15:46 (BVL-N59, 2.4.9-usbprobe1)

- 13:31: USB debugging went OFF with the screen on and unlocked (most likely Honor's Developer options writing 0 on
  opening, with "Charge only" ADB still off) → ASK, as designed. `allow_charging_adb` was 0 then, 1 by 15:46.
- USB debugging stayed off afterwards (`adb_enabled as shell: 0`), recording ran on Wireless debugging alone, and a
  call at 15:41 was missed; the recorder was started again at 15:46:04. Why it was down is not in the report (debug
  logging off).
- **Our bug:** the app read USB debugging as UNKNOWN (setting 0 + adbd up + WD on = the Android 17 rule), so the
  "paused off Wi-Fi" notice and its button never showed. Fixed in `30b8b0c4` (recorder's shell read is trusted).
- The phone's log: 121k lines, every kept line from the boot on 11-01; the event window read 0 lines. Unknown
  whether Honor's log is stale or only quiet → `54828936` adds log health (oldest/newest, buffer sizes) and an
  always-on transport timeline so the next report shows the outage itself.
- Test build 2.4.9-usbprobe2 (sha256 06d1b85c…) at ~/Downloads/CallVault-2.4.9-usbprobe2/. 🧪 Not run on any phone.

## Testers (maintainer, 2026-11-04)

- **Eugene** (Honor BVL-N49): usbprobe1 only. Says everything is fine. 🧪 no debug report read yet.
- **Igor Pavlovich** (Honor BVL-N59; Reports 1 and 2 are his): usbprobe1, then usbprobe2. He installed **Android 17
  at about the same time as usbprobe2** and said "no problems, we'll see tomorrow" right after. Not evidence yet: two
  changes at once, and only hours. To separate them, read his debug report after 1–2 days:
  - off events followed by restores → the phone still does it; usbprobe2 handles it;
  - no off events, `allow_charging_adb=1` → maybe our write stopped it (or Android 17);
  - no off events, `allow_charging_adb=0` → Android 17 most likely;
  - off events without a restore → our bug; the timeline says where.
- The "2.4.7/2.4.8 + Android 16 had no issue" claim: a mix-up with another user, per the maintainer. Igor confirmed the
  problem existed before. Dropped.
