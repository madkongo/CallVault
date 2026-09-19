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

## Open follow-ups (as of 2026-09-14 12:40)

- 🧪 Build `op12-probe-plus-fix` (branch `build/op12-probe-plus-transport-fix` = fix branch + stereo probe) installed on
  the OP12 at 12:37; waiting on the maintainer's regression check. The OP9 runs the fix build without the probe.
- After an install, the readiness notice says "starting up" for up to ~50 s while the recorder is already connected
  (OP12: binder 12:37:42, notice corrected 12:38:34). Also in 2.3.0. Not fixed.
- Not fixed: Wireless debugging left on for good after the user accepts Android's trust prompt at first setup; the
  launcher retries three times in 2 s after a refusal (re-raising the prompt); onboarding step 4 recommends 16 kbps
  where the default is 24.
- Not re-tested on a device: clearing the Shizuku warning on a mode switch (`81ec6e7`).
- Nothing pushed; `fix/adb-transport-dead-ends` is unmerged. Samsung and a real call during a switch change are untested.
- Separate thread still open: the per-channel transcription design (16 kHz two-channel sidecar vs stereo main file) —
  `docs/dev-notes/2026-09-12-stereo-separation-probe.md`.


## Does built-in mode break Shizuku for OTHER apps? — OP9, 2026-09-18

🧪 VERIFYING — measured on the OP9 (LE2121, Android 14, OxygenOS) with CallVault in **built-in
(standalone) mode** on `feat/home-hub-and-import`. Shizuku 13.6.0 (`moe.shizuku.privileged.api`) started
over adb with its own starter:

```
adb shell /data/app/~~<hash>/moe.shizuku.privileged.api-<hash>/lib/arm64/libshizuku.so
```

(that binary *is* the starter; there is no `start.sh` on sdcard in v13). Every row watches `pidof adbd`,
`pidof shizuku_server` and CallVault's `app_process` daemon together, so "survived" means the pid never
changed. The maintainer has confirmed none of it on the OP12.

| # | Configuration | Action | adbd | Shizuku | Verdict |
|---|---|---|---|---|---|
| A1 | USB on, WD on, loopback off | kill the daemon → keep-alive relaunch (0.9 s) | pid unchanged | **alive** | ✅ survives |
| A2 | USB on, WD on | write `adb_wifi_enabled` 1→0→1 (what CallVault's WD policy does) | pid unchanged | **alive** | ✅ survives |
| A3 | USB on, WD on | **arm off-Wi-Fi recording** (`tcpip:`) | 7921 → 15968 | **killed, never returns** | ❌ |
| A4 | USB on, WD on, off-Wi-Fi opt-in ON, listener unarmed | app start → launcher **re-arms by itself** | 19509 → 20486 | **killed** | ❌ (no user action at all) |
| B1 | USB **off**, WD on (reporter's setup), adbd already revived | kill the daemon → keep-alive relaunch | pid unchanged | **alive** | ✅ survives |
| B2 | USB off, WD on | off-Wi-Fi recording | refused before any ADB work (`NEEDS_USB_DEBUGGING`) | untouched | ✅ by rule |
| U1 | — | the **user** turns USB debugging off | stops (R2) | killed | user's own action |
| U2 | — | the **user** turns USB debugging on | 16554 → 19509 | killed | user's own action |

**So the answer is: built-in mode does not make Shizuku unusable.** Its routine work — relaunching the
daemon, and writing the Wireless-debugging switch — leaves `adbd`'s pid alone and a Shizuku server runs
straight through it (A1, A2, B1). Two things kill it:

1. **Arming (or closing) the off-Wi-Fi listener.** `tcpip:`/`usb:` restart `adbd` by design. A3 is
   deliberate and one-time; **A4 is the one that deserved a fix** — with the opt-in already on, the
   launcher re-arms by itself whenever the listener is missing (every reboot clears it), so a user who
   turned off-Wi-Fi recording on months ago loses Shizuku silently and repeatedly.
2. **A Default USB configuration change** (`svc usb setScreenUnlockedFunctions`) — already known, already
   guarded against running mid-call.

**`reviveAdbdIfStopped` cannot kill a live Shizuku**, because it only acts when `adbd` reads *stopped* —
by which time a server hosted by it is already dead. The one hazard there is a **stale reading**: a
Shizuku server that answers is proof `adbd` is up, and cycling Wireless debugging on the older reading
restarts a live `adbd` and kills the server that had just been started. That is the reporter's "when it
starts it disables automatically within a second and wireless debugging seems to restart". Now guarded.

### "…which then also turns USB debugging back on"

**Nothing in CallVault can do this.** `adb_enabled` is written in exactly one place in the whole app —
`SettingsScreen`'s own USB-debugging switch, which the user taps. Every automatic path writes only
`adb_wifi_enabled`. Two other explanations remain, both outside our code, and they are not mutually
exclusive:

- **Shizuku's own starter.** Its `AdbStartWorker` writes `ADB_ENABLED=1` and
  `adb_allowed_connection_time=0`, arms `tcpip:`, then writes `adb_wifi_enabled=0` — i.e. it turns USB
  debugging on and Wireless debugging off, on every start and on boot. That is the reported sequence
  almost word for word, from the app the reporter was starting at the time.
- **The OEM.** `WirelessDebuggingEnableGate` already records that on OxygenOS/One UI an attempt to write
  `adb_wifi_enabled` with no Wi-Fi *also* turned USB debugging on (#24). Not re-measured here: forcing it
  needs both switches off, which on a phone reached only over adb is a lock-out.

### What changed in the app

- `ShizukuChurnPolicy` (pure) — decides whether an `adbd` restart owes the user a warning first, a
  notification after, or nothing. It can never cancel the restart: **recording wins**, because a missed
  call is unrecoverable and a Shizuku server is two taps.
- `AdbdChurnNotice.around(…)` wraps the three restarting operations, samples Shizuku *before* (afterwards
  the evidence is gone) and posts `notif_health_shizuku_stopped_*` when it stopped a running server.
  Verified on the OP9: log line `CV:AdbdChurn: arming off-Wi-Fi recording restarted adbd, which stopped
  the Shizuku server that was running`, and notification id 4718 present in `dumpsys notification`.
- `AdbdRevivalPolicy.decide` takes `shizukuServerRunning`; a server that answers means `adbd` is up, so
  the switches are left alone.
- The off-Wi-Fi warning dialog gains a Shizuku paragraph, shown only when one is running.

### Checked and deliberately left alone

- **"Never write `adb_wifi_enabled` when the recorder is already reachable."** It already holds
  everywhere: `connectViaWirelessDebugging` returns early on `isConnected`; `RESTORE_WIRELESS_DEBUGGING`
  and `REBUILD_CONNECTION` only run with the daemon down; `reviveAdbdIfStopped` only with `adbd` stopped;
  `releaseWirelessDebugging` is gated on ownership and on the last-transport rule. No change needed.
- The `tcpip:` arm is **not** skipped when Shizuku is running. Skipping it would leave off-Wi-Fi calls
  unrecorded to protect another app.
- `reviveAdbdIfStopped`'s WD cycle is not suppressed for Shizuku beyond the stale-reading guard: when
  `adbd` really is stopped, Shizuku is already gone and recording needs the cycle.

### Two things found on the way, not fixed here

- **The USB-debugging observer's immediate revival is a no-op.** At the instant `adb_enabled` flips,
  `init.svc.adbd` still reads `running`, so `AdbdRevivalPolicy` answers `NOTHING` and returns without the
  2 s settle (only `ENABLE`/`CYCLE` wait). Recovery then falls to the keep-alive's next tick: **48 s and
  93 s** in two runs here, not the ~7 s this note claims further up. The 2 s settle needs to happen
  *before* the first decision on that path, not after it.
- **A host `adb` connected over the same Wireless-debugging TLS port starves the app's embedded client** —
  `waitForShellReady` failed 14 probes in a row for six minutes, and the daemon came back within seconds
  of the host disconnecting. Not an app bug; a trap for anyone measuring this over Wi-Fi adb.


## CallVault starts Shizuku again — OP9 + emulator, 2026-09-18 (afternoon)

🧪 VERIFYING. Decided by the maintainer the same day: when CallVault restarts `adbd` and that stops a
Shizuku server that was running, CallVault **starts it again automatically** and a notification says so.
Not a prompt, not a setting. This extends the `AdbdChurnNotice.around(…)` seam rather than adding a
second one.

### How the starter is found — never by package name

The package comes from `ShizukuBackend.managerPackage`, which resolves it from the API permission a
manager *declares* (stealth mode renames the package; Sui installs no app at all). From there:

1. `ApplicationInfo.nativeLibraryDir` → `<dir>/libshizuku.so`. This already names the one ABI Android
   installed, which settles the several-ABIs case without guessing.
2. Failing that, `ApplicationInfo.sourceDir` or `pm path <pkg>` over our own shell → `<install
   dir>/lib/<abi>/libshizuku.so` for each ABI in `Build.SUPPORTED_ABIS`.
3. Nothing usable → **no candidates**, which fails into "could not start it". `ShizukuStarterPaths`
   also refuses any path that is not absolute or that contains a quote or newline: the command is built
   as `'<path>'` on a privileged shell, and a quote would close the quoting.

Each candidate is simply run; there is deliberately **no "does this file exist" probe**. See the two
defects below for why.

### The rules the heal obeys

| Rule | Where | Why |
|---|---|---|
| Heal only a server that answered **before** the restart | `AdbdChurnNotice` samples it; `ShizukuHealPolicy.decide` | starting one nobody had running is CallVault launching another app's privileged service uninvited |
| Never start a second one | `ALREADY_BACK` when it answers again | two privileged hosts is the class of bug that cost most of 2026-08-24 |
| Never during a recording | `TELL_ONLY_RECORDING` | ADB work during a capture kills the daemon holding it |
| Never in front of the ADB work | the heal thread takes `AdbShell.heavyOperationLock` first | it can only run *after*, and never delays the operation the user asked for |
| Never claim success without a ping | `waitForPing`, then `pidof shizuku_server` as a weaker second opinion | `drive-health-false-positive` |

### Measured

| # | Device | Action | Result | Status |
|---|---|---|---|---|
| H1 | OP9 | arm off-Wi-Fi recording, Shizuku running | churn seen, starter run, **Shizuku answered again 630 ms after the starter**; adbd 21619 → new, shizuku 23611 → 28097. Total downtime ≈ **2.7 s** | ✅ |
| H2 | emulator | same | **answered again 300 ms after the starter**, downtime ≈ **2.3 s** | ✅ |
| H3 | emulator | `AdbdChurnNotice.around { }` with the server still answering | `ALREADY_BACK`, "nothing to tell the user", `pidof shizuku_server` **10143 before and after** — no second server | ✅ |
| H4 | emulator | same with no server running | **zero** `CV:AdbdChurn`/`CV:ShizukuHeal` lines, no server started, no notification | ✅ |
| H5 | emulator | heal with the transport genuinely gone | "No ADB connection to start Shizuku through" → notification id 4718 *"Shizuku was stopped / … It tried to start Shizuku again and could not — open Shizuku and start it yourself."* | ✅ |
| H6 | emulator | successful heal | notification id 4718 *"Shizuku was started again / … so it started Shizuku again for you. There is nothing to do."* | ✅ |
| H7 | OP9 + emulator | close the listener (`usb:`) | the decision to start is reached on both (`After closing the off-Wi-Fi listener: START`), **completion never observed** — settled 2026-09-19, see below | ❌ NOT WORKING 2026-09-19 |

H7 was measured on the re-paired OP9 on 2026-09-19 and **it does not heal**. It is not a harness limit
after all: closing the listener is by design the release of the *last* ADB user, so by the time the heal
runs there is no endpoint left to run the starter through.

```
10:31:00.162 W CV:AdbdChurn:  closing the off-Wi-Fi listener restarted adbd, which stopped the Shizuku server that was running
10:31:00.173 I CV:ShizukuHeal: After closing the off-Wi-Fi listener: START
10:31:00.183 W CV:ShizukuHeal: No ADB connection to start Shizuku through
10:32:00.325 I CV:ShizukuHealTest: after closing the off-Wi-Fi listener, Shizuku answering = false (waited 60157ms)
```

`ShizukuRestarter.startAndVerify` (`:118`) fails its `AdbShell.ensureConnected` guard 10 ms in. It then
does the honest thing — notification id 4718 was posted, with the "open Shizuku and start it yourself"
text — so the user is told rather than left guessing. **Not fixed.** The real fix is either to start
Shizuku *before* the disarm restarts adbd, or to accept the notification as the answer for this one
direction; that is a decision, not an oversight to patch quietly.

The automatic re-arm after a reboot (A4 above, the case that matters most) runs the *same* code as H1
with the same `underDialog = false`; it was not exercised as an actual reboot.

### Two defects the devices found, both now fixed

- **The starter's output was being read as the verdict.** It forks the server and exits, and on the
  emulator that closed the stream mid-read ("Stream closed.") while the server came up perfectly — so
  the first build posted *"could not start it"* over a Shizuku that was already running. What the
  starter prints is now a log line; only the ping decides.
- **A shell round trip that fails is not an answer of "no".** The first build probed `[ -x <path> ]`
  before running the starter; right after `usb:` that probe failed on a connection that still called
  itself connected, and a starter plainly present was reported missing. The probe is gone, and the
  read-back commands (`pm path`, `pidof`) retry once through a forced reconnect. The starter command
  itself deliberately does **not** retry: the request reaches `adbd` before the stream dies, so it has
  already run — retrying it spent 12 s on a reconnect that could not succeed while the server it had
  just started was answering all along.

### What it cost

The OP9 run also proved a harness hazard worth recording: `./gradlew connectedDebugAndroidTest` against
a test that restarts `adbd` **kills the instrumentation** (the `am instrument` client is a child of
`adbd`), and AGP then **uninstalls the app**, which takes its ADB pairing with it. Drive these tests
with `am instrument` against manually installed APKs instead. The OP9 was left needing to be paired
again.

### 2026-09-19 — re-run on the re-paired OP9

The phone was paired again by the maintainer, so the whole heal file was driven through `am instrument`
against manually installed APKs (never Gradle — see "What it cost" above). Branch build installed over
release 2.3.0; `WRITE_SECURE_SETTINGS` re-granted; `persist.sys.permission.enable=false`, so ColorOS
allowed the grant.

| # | Test | Result |
|---|---|---|
| H8 | a server that survived is not started twice | ✅ `pidof shizuku_server` **24594 before and after** |
| H9 | a server stopped from outside is started again | ✅ dead → answering again as pid 25764 |
| H10 | a phone with no Shizuku gets none started | ✅ nothing started |
| H11 | **arm** off-Wi-Fi recording, Shizuku running | ✅ *"Shizuku answered again 648 ms after the starter ran"*, ~3.0 s end to end. Reproduced twice. |
| H12 | **close** the off-Wi-Fi listener | ❌ see H7 above — no endpoint left, heal declines, notification posted |

Two things the earlier session could not have seen:

- **A Shizuku server CallVault started dies when CallVault's process dies.** Force-stopping the app —
  which is what `am instrument` does on every run — takes the server with it, because the starter ran
  over CallVault's own ADB shell stream and `adbd` reaps that stream's process group when the connection
  drops. A server started from a *host* `adb shell` survives its shell exiting. Not a bug we have a
  report for, but it means the heal's result is only as durable as the app process, and it silently
  ruins any test that force-stops the app between arranging and asserting.
- **`am instrument` must be run under `setsid`** for the two tests that restart adbd, or the restart
  kills the instrumentation and the app with it and the test never reaches its assertion. The first two
  attempts at H12 both died that way and looked like a 150 s hang.

Also green on the OP9 that day: `ShizukuDetectionDeviceTest` (2), `TranscriptMigrationInstrumentedTest`
(11), `TranscribeOnlyAudioDeviceTest` (4), `MergeRoundTripTest` (6), `CallEvidenceDeviceTest` (1). Unit
suite 1591/0.
