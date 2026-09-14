# How the two debugging switches actually work — 2026-09-14

🧪 VERIFYING — every row marked ✅/❌ below was **measured** on the emulator (Android 16, AOSP) or the
OP9 (OnePlus, Android 14) on 2026-09-14; rows marked 📐 are read from source and not yet measured. The
maintainer has not confirmed any of it on the OP12. This is the reference the app copy, the onboarding and
the README must agree with. Triggered by #23, #24, #39.

## The one-paragraph answer

The two switches do **different jobs**, and they do not both have to be on.

- **Wireless debugging is how CallVault gets in.** Its embedded ADB client speaks TCP, so it reaches
  Android's debugging service (`adbd`) over Wireless debugging — to pair at setup, and whenever the recorder
  has to be started again (after a reboot, an update, or Android reaping it) and off-Wi-Fi recording is not
  armed. It needs Wi-Fi: Android refuses to run it without a Wi-Fi connection, asks the user to trust each
  new network, and turns it off by itself when Wi-Fi drops.
- **USB debugging is what keeps `adbd` running without Wi-Fi.** No cable involved. With it on, the recorder
  survives leaving Wi-Fi, off-Wi-Fi recording is possible, and CallVault can switch Wireless debugging back
  off after using it.

**Best setup: USB debugging on, off-Wi-Fi recording on, Wireless debugging off** — CallVault switches
Wireless debugging on for a few seconds when it needs it (on Wi-Fi, after a reboot) and off again.

## The rules, with where each comes from

| # | Rule | Source | Status |
|---|---|---|---|
| R1 | `adbd` runs while USB debugging **or** Wireless debugging is on; with both off it is stopped | AOSP `AdbService.stopAdbd()` | ✅ emulator T6a |
| R2 | **Turning USB debugging off stops `adbd` even when Wireless debugging is on**, and nothing restarts it | AOSP `init.usb.configfs.rc`: `on property:sys.usb.config=none … stop adbd` — unconditional | ✅ emulator T7, ✅ OP9 T9 |
| R3 | Switching Wireless debugging off and on again after that brings `adbd` back, and USB debugging stays off | — | ✅ OP9 (by hand) |
| R4 | Wireless debugging will not run without Wi-Fi; Android writes the switch back to off | AOSP `AdbDebuggingManager`, `MSG_ADBDWIFI_ENABLE` → `getCurrentWifiApInfo()==null` | ✅ emulator (app log: "switched back off") |
| R5 | On a network not yet trusted, Android writes it back to off and shows "Allow wireless debugging on this network?" | same, `verifyWifiNetwork()` | ✅ emulator |
| R6 | Losing Wi-Fi turns Wireless debugging off | same, the `NETWORK_STATE_CHANGED` receiver | 📐 source only |
| R7 | The off-Wi-Fi (loopback) listener lives inside `adbd`, so it dies whenever `adbd` stops | R1 + R2 | ✅ emulator T6a/T7 (port unreachable) |
| R8 | An app can read `init.svc.adbd` (running/stopped) but **not** `service.adb.tls.port` | on-device `plat_sepolicy.cil`: `allow domain init_service_status_prop`; `adbd_prop` only adbd + system_server | ✅ OP12 + emulator |
| R9 | CallVault writing Wireless debugging on within milliseconds of USB debugging going off loses a race: `init` starts a new `adbd`, then the in-flight USB change stops it | init log, emulator 10:11:44 | ✅ emulator T6a |

R2 is the one that explains #39: the reporter turned USB debugging off and was left with Wireless debugging
reading **on** and nothing listening — exactly what R2 + R9 produce, and what his report header said.

## Every combination

"Recorder restart" means launching CallVault's recorder process again, which needs a way in.

| Setup | Recording on Wi-Fi | Recording off Wi-Fi | Recorder restart on Wi-Fi | Recorder restart off Wi-Fi | Measured |
|---|---|---|---|---|---|
| **USB on + off-Wi-Fi recording** | ✅ | ✅ | ✅ (app flips Wireless debugging on/off) | ✅ over the loopback, <1 s | T2 ✅, T5b ✅ |
| USB on, no off-Wi-Fi recording | ✅ | ✅ while the recorder stays alive | ✅ 4 s | ❌ until Wi-Fi returns, then ✅ by itself (36 s) | T2 ✅, T3 ❌, T4 ✅ |
| USB off, Wireless debugging on | ✅ | ❌ Android turns Wireless debugging off → `adbd` stops | ✅ | ❌ | OP9 ✅ on Wi-Fi |
| Both off | ❌ | ❌ | app can switch Wireless debugging on (trusted Wi-Fi) | ❌ | T6a ❌ in 2.3.0 (R9) |
| Off-Wi-Fi recording with USB off | — | ❌ always (R7) | — | — | by rule |

**Transitions:**

| The user… | What happens | Measured |
|---|---|---|
| turns Wireless debugging off, USB on | nothing; `adbd` pid unchanged, recorder alive | T1 ✅ |
| turns USB debugging off (any state) | `adbd` stops, recorder dies (R2) | T7 ✅, T9 ✅ |
| turns USB debugging off, Wi-Fi available | 2.3.0 does **not** recover (it only reacts when both are off, and then loses R9) | T6a ❌, T7 ❌ |
| cycles Wireless debugging by hand afterwards | recovers | OP9 ✅ |
| loses Wi-Fi, USB on, off-Wi-Fi recording armed | recorder keeps running and restarts over the loopback | T5b ✅ |

## What the app must do (fix list, in progress on `fix/adb-transport-dead-ends`)

1. After USB debugging turns off, wait for the USB change to settle, then check `init.svc.adbd`. If it is not
   running and Wi-Fi is up: switch Wireless debugging on (if off) or off-and-on (if on). R2, R3, R9.
2. Never write Wireless debugging on without Wi-Fi (built, R4). Read the write back (built, R5).
3. The notification may only say "starting up" while a restart is possible: no Wi-Fi and no armed loopback
   means it is not (T3 gap in the first build).
4. Warn before USB debugging is turned off from our own settings (built; dialog verified on the emulator).

## Things found on the way, not yet fixed

- After the user accepts Android's "trust this network" prompt, CallVault treats Wireless debugging as the
  user's and never switches it back off, even with USB debugging on. First setup can leave it on for good.
- The launcher retries three times in two seconds after a refusal, re-raising the trust prompt each time.
- The in-app USB-debugging switch does not follow the real setting when it changes elsewhere.
- Onboarding step 4 says "Opus at 16 kbps is recommended"; the recommended setting is 24 kbps.
- The new dialog's USB icon renders coral (the M3-default colour trap).

## Limits of these tests

- The emulator has `ro.adb.secure=0`, so pairing is never exercised there; Android's Wi-Fi trust prompt still is.
- Only one OEM phone (OP9, Android 14). #24's "USB debugging turned itself on" came from an OP12 on
  Android 16 and was **not** reproduced on the OP9. Samsung is untested.
- No real call was recorded in any of these tests; "recorder alive" is the process and its binder.
