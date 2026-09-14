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
| Both off | ❌ | ❌ | app switches Wireless debugging on after a 2 s settle | ❌ | 2.3.0 T6a ❌ · fix T6a ✅ |
| Off-Wi-Fi recording with USB off | — | ❌ always (R7) | — | — | by rule |

**Transitions:**

| The user… | What happens | Measured |
|---|---|---|
| turns Wireless debugging off, USB on | nothing; `adbd` pid unchanged, recorder alive | T1 ✅ |
| turns USB debugging off (any state) | `adbd` stops, recorder dies (R2) | T7 ✅, T9 ✅ |
| turns USB debugging off, Wi-Fi available | 2.3.0 does **not** recover (it only reacts when both are off, and then loses R9). **Fix build: back in 4.6 s (WD on) / 5 s (both off), USB debugging stays off** | 2.3.0: T6a ❌, T7 ❌, OP9 ❌ · fix: T7 ✅, T6a ✅ |
| cycles Wireless debugging by hand afterwards | recovers | OP9 ✅ |
| loses Wi-Fi, USB on, off-Wi-Fi recording armed | recorder keeps running and restarts over the loopback | T5b ✅ |

## What the app does now (`fix/adb-transport-dead-ends`, 🧪 not on any user's phone yet)

1. After USB debugging turns off, wait for the USB change to settle, then check `init.svc.adbd`. If it is not
   running and Wi-Fi is up: switch Wireless debugging on (if off) or off-and-on (if on). R2, R3, R9.
2. Never write Wireless debugging on without Wi-Fi (built, R4). Read the write back (built, R5).
3. The notification may only say "starting up" while a restart is possible. ✅ emulator: with USB off and no
   Wi-Fi it read "Calls aren't being recorded — USB debugging is off and there's no Wi-Fi. Connect to Wi-Fi, or
   turn USB debugging on." (The collapsed line was first hidden by the screen-lock tip; fixed, not re-shot.)
4. Warn before USB debugging is turned off from our own settings (built; dialog verified on the emulator).

## Things found on the way, not yet fixed

- After the user accepts Android's "trust this network" prompt, CallVault treats Wireless debugging as the
  user's and never switches it back off, even with USB debugging on. First setup can leave it on for good.
- The launcher retries three times in two seconds after a refusal, re-raising the trust prompt each time.
- Onboarding step 4 says "Opus at 16 kbps is recommended"; the recommended setting is 24 kbps.

Fixed since first written: the in-app USB-debugging switch now follows the real setting (✅ emulator, it read
off while USB debugging was off); the warning dialog's icon is tinted explicitly (not re-shot).


## OP9 run — manual toggles and Shizuku (2026-09-14, fix build on the OP9)

All switch changes in this section were made **by hand in Developer options** by the maintainer, except where
noted. Watched over Wi-Fi TLS adb.

| # | Setup | Action | Result | Status |
|---|---|---|---|---|
| S1a | Built-in mode, Shizuku server running (started over adb) | arm off-Wi-Fi recording from CallVault | **Shizuku's server killed** — arming opens `tcpip:`, which restarts adbd. #39's second complaint | ✅ reproduced |
| S1b | Built-in mode, Shizuku running, USB on, WD on | USB debugging off (no confirmation dialog on OxygenOS) | adbd, recorder and Shizuku died; CallVault cycled WD and the recorder was back in **10.5 s**; USB stayed off; **Shizuku stayed dead** | ✅ |
| S1c | Built-in mode, USB off, WD on, USB mode MTP | screen locked for 60 s (adb) | adbd kept running, recorder alive, USB mode stayed `mtp` — no kill on this phone | ✅ one data point |
| S2 | Built-in mode, USB off, WD on | Wireless debugging off | **CallVault switched it back on 50 ms later**, both taps — the user cannot turn it off. Older keep-alive step `RESTORE_WIRELESS_DEBUGGING`, also in 2.3.0 | ✅ measured |
| S3 | **Shizuku mode**, Shizuku running, USB on, WD on | USB debugging off | adbd, Shizuku and CallVault's recorder died; no switch touched (correct); home screen said "Shizuku is not ready"; **no notification at all** | ✅ measured |

What these add to the rules:

- **R10** Shizuku's server lives inside adbd, so everything that stops adbd stops Shizuku: USB debugging off
  (R2), both switches off, arming off-Wi-Fi recording (`tcpip:`), a Default USB configuration change. Nothing
  restarts Shizuku; its user must start it again. ✅ S1a, S1b, S3.
- **R11** Once adbd has been started *after* USB debugging went off, it runs stably with USB off and Wireless
  debugging on (recorder ran 20 min in S1b). So Shizuku started over Wireless debugging with USB debugging
  already off should survive too. 📐 not measured with Shizuku itself.
- **R12** After R2, Wireless debugging still reads on with adbd stopped, so Shizuku's "Start via Wireless
  debugging" should fail until WD is switched off and on. 📐 Shizuku is not paired on the OP9; not tried.
- CallVault in Shizuku mode has **no runtime signal** that Shizuku died: no `addBinderDeadListener`, and the
  "cannot record" notification only posts from the boot path.
- Resilient recording has **no USB-debugging dependency** in code (`HandoffPolicy`: pref, audio source, not
  Shizuku mode). It is what keeps a call recording when adbd dies mid-call. Not exercised on a call this run.

Decided by the maintainer 2026-09-14: CallVault turning a Wireless-debugging switch back on after the user
turned it off must be an **opt-in setting**; and the two Shizuku gaps (no notification, no way to restart
Shizuku after R2) are to be fixed.


## Round 2 — the new behaviour, measured (2026-09-14 afternoon)

Emulator (E) and OP9 (S). All 🧪 until the maintainer confirms on the OP12.

| # | Setup | Action | Result | Status |
|---|---|---|---|---|
| E1 | Settings | open Experimental | "Keep Wireless debugging on for recording" shows under USB debugging, off by default | ✅ |
| E2 | built-in, off-Wi-Fi recording on, USB on | USB debugging off | recorder back in 7 s; notice "Ready to record calls — Off-Wi-Fi recording is paused: it needs USB debugging. Calls on Wi-Fi still record." | ✅ |
| E3 | USB off, WD on (CallVault's) | WD off (as the user) | logged "switched off by hand; CallVault will leave it off"; the old 50 ms override refused; WD stayed off 75 s+; notice "Calls aren't being recorded — You turned Wireless debugging off…" with a **Turn Wireless debugging on** button; tapping it → recorder back in 4 s | ✅ (button verified twice) |
| E4 | same, override setting **on** | WD off (as the user) | "the override setting is on" → WD back on 2 s later, recorder back in 6 s | ✅ |
| S4 | OP9, **Shizuku mode**, Shizuku running, USB on, WD on | USB debugging off (by hand) | warning "CallVault cannot record right now" posted; adbd running again with USB off within ~9 s (WD cycle); Shizuku stayed dead as expected | ✅ |
| S4b | same | Shizuku restarted (adb over Wi-Fi) | warning cleared, but **the recorder never came back** — "Already bound" to the dead Shizuku. Pre-existing bug; fixed | ❌ → fixed |
| S5 | fix installed, Shizuku mode, USB **off**, WD on | kill Shizuku, then restart it | warning within 1 s; on restart "Shizuku is running" and the recorder service bound again 0.3 s later; warning cleared; app never opened | ✅ |
| R11 | Shizuku started **after** USB debugging went off | left running | Shizuku and CallVault's recorder ran normally with USB debugging off | ✅ now measured with Shizuku |

About the maintainer's "Wireless debugging notification keeps coming back" during S4: adbd's pid stayed the
same for the whole window, so it was not a restart loop. The likeliest cause is the test watcher reconnecting
over Wi-Fi every 2 s, which makes Android re-post "Wireless debugging connected". 📐 not confirmed.

Also fixed after S4: leaving Shizuku mode did not clear Shizuku's "cannot record" warning, so it stayed up over
a working standalone recorder (seen once; the fix is not yet re-tested on a device).

## Limits of these tests

- The emulator has `ro.adb.secure=0`, so pairing is never exercised there; Android's Wi-Fi trust prompt still is.
- Only one OEM phone (OP9, Android 14). #24's "USB debugging turned itself on" came from an OP12 on
  Android 16 and was **not** reproduced on the OP9. Samsung is untested.
- No real call was recorded in any of these tests; "recorder alive" is the process and its binder.
