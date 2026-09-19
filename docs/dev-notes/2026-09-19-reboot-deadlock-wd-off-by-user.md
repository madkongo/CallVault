# 2026-09-19 — after a reboot the phone can stop recording for good: the "user turned Wireless debugging off" flag deadlocks recovery

Status: **✅ VERIFIED FIXED 2026-09-19** by the maintainer, on the OP12 that showed the fault. The bug
itself was reproduced there, diagnosed from live device state and from the app's own setup journal; the
fix (`1f9702cb`, `LoopbackBorrowPolicy`) was installed as APK `49e33c50eb6b6ded` at 16:49 and the phone
was rebooted. His report: *"it shows a good state but i did see WD turning on and off for like 3 times
until it reached 'Ready to record'."*

So the deadlock is gone — the phone recovers on its own, which it could not do before. **One thing is not
explained: three borrow cycles, where one should do.** See "Open: the flicker count" at the end.

The bug is almost certainly in **2.3.0 as shipped**, and the fix is not released.

## What the maintainer saw

Rebooted the OP12 to test post-reboot cold start (check 4 of the list). Got an error notification. Turned
debug logging on and rebooted again so there would be a log.

## What the phone showed, ~5 minutes after the second boot

| | |
|---|---|
| USB debugging | **on** (`adb_enabled=1`) |
| Wireless debugging | off (`adb_wifi_enabled=0`) |
| Off-Wi-Fi listener | **gone** (`service.adb.tcp.port` empty — a reboot always clears it) |
| `adbd` | running |
| Wi-Fi | **connected** (SSID "Baba", 192.168.1.178, RSSI −36) |
| Recorder daemon | **absent** — no `app_process` under the shell uid |
| Notification 4720 | "Calls aren't being recorded — *You turned Wireless debugging off, and nothing else lets CallVault restart the recorder. Turn it back on, or turn on USB debugging.*" |
| Notification 4716 | "CallVault cannot record right now — *Calls will not be recorded until this phone joins a Wi-Fi network once…*" |

Both notifications are **wrong**. The user did not turn Wireless debugging off — CallVault did, as it is
designed to. USB debugging is already on, so "turn on USB debugging" is advice with nothing behind it. And
the phone was on Wi-Fi the whole time.

Watched for **5 minutes: nothing changed.** Then **opened the app and watched another 90 s with
MainActivity focused and the screen awake: still nothing.** This is not slow recovery. It is a stall with
no way out from inside the app.

## The mechanism — corrected 2026-09-19 by the app's own log

⚠️ **An earlier version of this note named `maybeRewarm`'s notice check as the mechanism. That was
incomplete and put the emphasis in the wrong place.** The maintainer's debug report
(`attachments/2026-09-19-op12-reboot-deadlock.txt`) shows the boot path **does** try, six times, and is
refused much deeper down — in `WirelessDebuggingEnableGate`. Three separate gates read the same flag.

### Gate 1 — `WirelessDebuggingEnableGate`, the one that fires first

Straight from the log, at boot:

```
16:12:32.658 [I] CV:RecorderLauncher: Attempt 1: offline mode but no connection — re-arming loopback
16:12:32.672 [I] CV:AdbShell: Not switching Wireless debugging on: the user turned it off (override setting is off)
16:12:32.677 [W] CV:AdbShell: Wireless debugging is off and could not be switched on
16:12:32.677 [I] CV:AdbShell: Cannot arm loopback — no base connection (NEEDS_WIRELESS_DEBUGGING)
```

`AdbShell.enableWirelessDebugging` (`:658-672`) asks the gate with `userTurnedOff = true`,
`enforced = false`, `userRequested = false` and gets `RESPECT_USER`. It returns false, so the loopback
cannot be armed, so there is no connection, so the daemon cannot launch.

**The three "attempts" are worthless here.** `RecorderLauncher` ran its 3-attempt loop twice — six full
`ensureServerRunning` cycles — and the whole thing was over in **107 ms** (16:12:32.658 → 16:12:32.765).
The refusal is a `SharedPreferences` read: no I/O, no timeout, nothing that could come good on a retry. A
retry loop is the wrong shape for a decision that cannot change.

### Gate 2 — `reviveAdbdIfStopped`

`AdbShell.kt:186`: `mayEnable = mayEnable && !userOff`, with `userOff` the same flag. So the revival path
cannot enable it either.

### Gate 3 — `maybeRewarm`, which is why nothing happened for the next five minutes

`ReadinessNotice.of` (`services/recording/ReadinessNotice.kt:78`):

```kotlin
!wirelessDebuggingOn && wirelessDebuggingOffByUser && !enforced && !(usbDebuggingOn && loopbackArmed) -> WD_OFF_BY_USER
```

and `DaemonKeepAliveService.maybeRewarm` (`:423-427`):

```kotlin
val known = ReadinessNoticeText.current(this, ready = false)
if (known == ReadinessNotice.WD_OFF_BY_USER || known == ReadinessNotice.NEEDS_WIFI) {
    updateNotification(false)
    return
}
```

So:

1. `wirelessDebuggingOffByUser` is a **persisted** SharedPreference (`AppPreferences.kt:1217`,
   `Key.WD_TURNED_OFF_BY_USER`). It survives a reboot.
2. A reboot **always** clears `service.adb.tcp.port`, so `loopbackArmed` is false on every boot.
3. Therefore the escape clause `usbDebuggingOn && loopbackArmed` is false, and the notice is
   `WD_OFF_BY_USER`.
4. `maybeRewarm` returns immediately, every tick, forever.
5. The only thing that re-arms the loopback is a relaunch — which is what `maybeRewarm` was about to do.

**The condition that would clear the block is the thing the block prevents.** It is a true deadlock, not a
slow path. Nothing in the app can break it: not the boot service (it tried six times in 107 ms and every
one was refused by gate 1), not the watchdog, not opening the app.

The gate does have an escape — `userRequested`, set when the user asks explicitly. That is why the
notification's "Turn Wireless debugging on" action button works, and it is the only route out from inside
the app. The other way out is the user turning Wireless debugging on themselves in Developer options.

### Proof

Set `adb_wifi_enabled=1` over adb. **Within 10 s**: loopback re-armed on 51392, daemon up, notice back to
"Ready to record calls — The recorder is connected." Nothing else was touched. The flag was the only thing
holding it.

Wireless debugging was then set back to 0 to restore the maintainer's normal configuration; the loopback
stayed armed and the recorder stayed up, so the phone is healthy **until the next reboot**, when the same
thing will happen again.

## Other things the report settles

- The OP12 is **Android 16 (API 36)**, ROM `CPH2581_16.0.10.501(EX01)`. Nothing to do with Android 17.
- Config at the time: STANDALONE, offline recording **on**, resilient recording on, VoIP on, no Shizuku,
  `WRITE_SECURE_SETTINGS` true, OEM shell gate ALLOWED.
- `WD plan: DROP_USB_KEEPS_ADBD` — the app knew USB debugging alone was keeping adbd up, and still could
  not use it.
- "Recorder host lines: none" — the daemon never started, so there is nothing from its side. Expected.

## Why the flag was set at all

`DaemonKeepAliveService.kt:244` sets it when `WirelessDebuggingOffCause.of` returns `USER`. CallVault
switches Wireless debugging off itself after every use, and Android switches it off when Wi-Fi drops; both
have guards (`didWeJustSetWirelessDebugging`, the `WD_OFF_RECHECK_MS` recheck added 2026-09-15). Any one
miss sets a flag that is **never cleared except by Wireless debugging coming back on**
(`DaemonKeepAliveService.kt:215`) — which the deadlock prevents. On this phone it was already set before
the reboot; exactly when is not recoverable now.

That is the deeper problem: **a sticky flag whose only clearer is the event it blocks.**

## Second, smaller defect found alongside

Notification 4716 ("CallVault cannot record right now") **stayed up after the recorder came back**.
`SilentFailureNotifier.clearRecorderUnavailable` is called from exactly one place — the boot service, on
the success branch (`AdbConnectionService.kt:78`). Once boot has failed, nothing clears it for the rest of
the uptime. A user who fixes the problem keeps being told it is broken.

## Fix directions (none written)

0. **Gate 1 is the one to fix first** — a refusal to *transiently* switch Wireless debugging on is not the
   same as respecting a user's setting, when USB debugging is on, Wi-Fi is up, and the switch will be put
   back within seconds. That is the documented "best setup" flow, and the flag turns it off permanently.
1. **The escape clause is wrong.** `usbDebuggingOn && loopbackArmed` should be `usbDebuggingOn` alone for
   the purpose of *deciding whether recovery is hopeless*. With USB debugging on and Wi-Fi up, turning
   Wireless debugging on for a few seconds is exactly what CallVault is for, and is the documented "best
   setup" flow. Respecting the user's switch must not mean refusing to use it transiently.
2. **Clear the flag on boot.** A preference recording a user gesture from a previous uptime is not
   evidence about this one — the same rule as the drive-health false positive. At minimum, clear it when
   the loopback is unarmed and USB debugging is on, because that combination can only be a fresh boot.
3. **`clearRecorderUnavailable` needs a second caller** — wherever the daemon binder is confirmed alive,
   not only at boot.
4. **The notification text should not assert a cause it cannot support.** "You turned Wireless debugging
   off" is a claim about the user's actions derived from a stale flag.

## What to check when fixing

The reproduction is one reboot, so it is cheap. It needs `WD_TURNED_OFF_BY_USER` already true, which is the
normal state for anyone running the recommended setup (USB debugging on, Wireless debugging off). Watch
`service.adb.tcp.port`, `adb_wifi_enabled` and `ps -A | grep app_process`, and read notification 4720's
text — it names the notice branch directly.


## Open: the flicker count (🧪, 2026-09-19)

The fix borrows Wireless debugging, arms the listener and hands the switch back. That is **one** on/off
cycle. The maintainer watched **three** before the notification settled on "Ready to record calls".

Three is not harmless. Each write restarts `adbd`, every restart takes any Shizuku server with it (R10),
and the window it happens in — the first minute after a boot — is exactly when an early call is most
likely to be missed. It also leaves the debugging port open three times instead of once.

**Not diagnosed.** Logcat had already rotated past the boot when the phone was checked (Bluetooth
chatter floods the default buffer within ~3 minutes), and app debug logging was off.

Hypotheses, none tested:

1. `releaseWirelessDebugging` restarts `adbd`, and the recorder daemon is a child of an `adbd` shell —
   so handing the switch back may kill the daemon that was just launched, and the watchdog relaunches.
   If the listener were momentarily unreachable during that restart, `mayBorrow` could read
   `loopbackArmed` false again and borrow a second time.
2. `RecorderLauncher` runs three attempts per `ensureServerRunning`, and the boot path called it twice
   before. If arming fails on a round, that round borrows again.
3. Something else entirely.

### ⚠️ The setup journal does NOT cover this, and saying it did was wrong

I told the maintainer the always-on setup journal would capture the reboot, so *Save log* would settle it
with no preparation. **That is false.** The journal is a **first-run** journal: it seals itself the moment
a recorder first connects, and never reopens.

His 16:58 report proves it. The journal inside it runs 16:11 → 16:21 and ends:

```
2026-09-19 16:21:16.608 [I] CV:RecorderConn: RecorderConnection received daemon binder
=== a recorder connected; setup finished [journal-end] ===
```

16:21 is when the *pre-fix* phone was rescued by hand. The install at 16:49 and the reboot at ~16:50 are
not in it at all, and the report contains **zero** `Borrowing Wireless debugging` lines — not because the
borrow did not happen, but because nothing was recording by then.

The reason the journal *did* hold the pre-fix boot is the opposite of "always on": setup had never
succeeded on that build, so the journal was still open. **It is exactly the wrong tool for a regression
after the app has worked once.** For that, the opt-in debug log has to be on *before* the reboot.

### What the OP9 says: one borrow, not three

Reproduced on the OP9 without a reboot — same state, different route. The fixed build, the flag set by
turning Wireless debugging off by hand, then `adb usb` to clear the listener exactly as a reboot does.

```
16:59:00.418 W CV:DaemonKeepAlive: keep-alive: no TCP endpoint to dial — switching Wireless debugging back on
16:59:01.427 I CV:AdbShell:        Borrowing Wireless debugging to re-arm the off-Wi-Fi listener; it goes back off straight after
16:59:05.247 I CV:AdbShell:        Arming loopback tcpip on :47886 (adbd will restart)…
16:59:07.295 I CV:AdbShell:        Loopback arm result on :47886 = true
16:59:07.710 I CV:RecorderLauncher: Recorder daemon connected on attempt 1; binder available
16:59:07.816 I CV:AdbShell:        Wireless debugging disabled after the daemon launch (DROP_USB_KEEPS_ADBD)
```

**One borrow, 7.4 s end to end**, switch handed back. So the policy itself is right and the triple is
something about **boot specifically**, not about borrowing.

### The refined hypothesis (still untested)

Boot starts three things at once — `AdbConnectionService`, `CallMonitorService` and
`DaemonKeepAliveService` were all seen starting inside the same 20 ms in the pre-fix log — and the
pre-fix log also shows `ensureServerRunning` running its 3-attempt loop **twice**. If several of those
starters reach `enableWirelessDebugging` at moments when the listener is not yet armed, each one borrows.
`WirelessDebuggingLease` should collapse *overlapping* users into one on/off, so the suspicion is
starters that are staggered rather than concurrent — each one borrowing, finishing, and handing the
switch back before the next begins.

**To settle it:** turn the debug log on **before** rebooting, reboot, then *Save log*. Nothing else
captures it.

## The flicker explained — and it was not the borrow (2026-09-19, second reboot)

The maintainer deleted the log (which reopens the setup journal), rebooted, and reported it "didn't go
smoothly at all": Wireless debugging cycling **five or six times**, an error notification that eventually
cleared. The journal caught the whole thing, and my hypothesis about staggered boot starters was **wrong**.

### What actually happens

`armLoopbackIfNeeded` fires `tcpip:<port>`, sleeps a fixed 2 s (`TCPIP_RESTART_WAIT_MS` +
`POST_DISCONNECT_WAIT_MS`), and makes **one** connect attempt. On a booting phone `adbd` has not finished
restarting, so that attempt is refused:

```
17:04:39.651 I Arming loopback tcpip on :51392 (adbd will restart)…
17:04:41.666 D loopback tcpip :51392 unavailable (unarmed/refused): null
17:04:41.672 I Loopback arm result on :51392 = false
```

One refused socket writes off the whole round. What it costs is out of all proportion:

```
17:04:41.677 I Attempt 2: offline mode but no connection — re-arming loopback
17:04:41.708 W Dropped adb-6011b07e at 192.168.1.213:35675 — NOTHING_LISTENING_ON_LOOPBACK
17:04:53.682 W No _adb-tls-connect._tcp service accepted within 12000ms
17:04:53.685 I Cannot arm loopback — no base connection (NO_ADB_SERVICE)
```

The next round has no connection, so it goes back to mDNS — but `adbd` is in tcpip mode now, its TLS
advert is stale, and discovery burns its **full 12 s timeout**. Three of those make one
`ensureServerRunning`, the boot path runs it more than once, and **every round borrows Wireless debugging
again**. Hence the visible flicker.

Full sequence: arm failed at **17:04:41**, **17:04:58** and **17:05:15**, succeeded at **17:05:44**.
**73 seconds** for something that takes 2 s on a settled phone (measured: 17:05:42.940 → 17:05:44.984,
2.04 s; the OP9 is the same). Every one of those restarts `adbd`, and every `adbd` restart takes a running
Shizuku server with it.

So the borrow was never the problem — it was doing its job each time, and the churn was the arm failing
and being retried from scratch.

### Fix — 🧪 VERIFYING (`f72198f9`, `LoopbackArmWait`)

Poll for the listener instead of taking one look, keyed on `service.adb.tcp.port`: the property is set as
part of handling `tcpip:` and survives the restart it triggers, so it answers *"did the arm take?"* long
before a socket will answer *"is it listening yet?"*. Retry while the property says armed (budget 12 s,
1.5 s apart); give up at once when it does not, so a request that never landed costs one attempt rather
than the whole budget. The arm now logs how long the listener took, so the next report says whether the
budget is right instead of leaving it to be inferred.

Installed on the OP12 at 17:1x as APK `35eba9f44458ba92`; daemon back in under 8 s, notice "Ready to
record calls". **Settled when a reboot shows one Wireless-debugging cycle and a `listener took …ms` line
well inside the budget.**

### Two things this leaves open

- **The 12 s mDNS timeout is paid whenever the loopback is unreachable but armed.** The fix should stop
  the app reaching that path on boot, but the path itself is still expensive and still wrong: with the
  port armed, mDNS is the wrong thing to be waiting for.
- **`NOTHING_LISTENING_ON_LOOPBACK` rejects this device's own advert** while `adbd` is mid-restart, so
  discovery drops the one endpoint it should be using. Worth revisiting alongside the note in
  `2026-09-19-android-17-adb-detection-issue-40.md` that the loopback bind probe is unverified on
  Android 17.

## Third boot (17:28): better, and it corrected my premise

Two Wireless-debugging cycles instead of five or six, and 41 s instead of 73 s (17:28:12 → 17:28:53).
Real progress, but it should have been one cycle — and the new `listener took …ms` line said why
immediately:

```
17:28:17.236 I Arming loopback tcpip on :51392 (adbd will restart)…
17:28:19.244 I Loopback arm result on :51392 = false (listener took 4ms)
...
17:28:35.824 I Loopback arm result on :51392 = false (listener took 9ms)
...
17:28:53.272 I Loopback arm result on :51392 = true  (listener took 38ms)
```

**4 ms and 9 ms.** The wait never engaged: `LoopbackArmWait` gave up at once because
`service.adb.tcp.port` was still unset, which the first version treated as proof the arm had never
landed.

⚠️ **That premise was wrong**, and the KDoc asserting it was wrong too. `adbd` writes the property when it
*restarts and binds*, not when it accepts `tcpip:` — and on a booting phone that takes longer than the
2 s of fixed sleeps. One of the abandoned arms had plainly landed, because a later round found the
listener up without arming again. `getSystemProperty` is in-process reflection on
`android.os.SystemProperties`, so the reading was honest; only the meaning I gave it was not.

### Corrected (`LOOPBACK_ARM_GRACE_MS`)

The property is a **positive** signal only. Inside a 5 s grace an unset value means "`adbd` has not
restarted yet" and the wait continues; after the grace it means the request never landed, and the caller
is released rather than spending the whole budget; once it reads armed, the full 12 s is available.

APK `ebaadb297f74c51b` on the OP12; daemon back in under 8 s, "Ready to record calls".
**🧪 Settled when a reboot shows one Wireless-debugging cycle and a `listener took` figure in the
seconds, not milliseconds.**

### A lesson worth keeping

Both wrong turns in this note were the same mistake: **treating the absence of a signal as evidence**.
First `WD_TURNED_OFF_BY_USER` surviving a reboot was read as a statement about now; then an unset
`service.adb.tcp.port` was read as proof of failure. Neither absence meant what it was taken to mean.
The project already has the idiom for this — `WifiState.UNKNOWN`, `AdbdState.UNKNOWN` — and both bugs
are what it looks like when a third state is collapsed into "no".

## Fourth boot (17:35): one cycle, 11.4 s — ✅ VERIFIED 2026-09-19 by the maintainer ("this looks good")

APK `ebaadb297f74c51b`. The whole boot, with nothing left out:

```
17:35:19.020 I BootReceiver:     Boot completed; starting ADB connection service + post-boot call monitor
17:35:19.064 I AdbShell:         Borrowing Wireless debugging to re-arm the off-Wi-Fi listener; it goes back off straight after
17:35:20.988 I AdbMdns:          Accepted adb-6011b07e-cDHaSu at 192.168.1.178:41605
17:35:23.566 I AdbShell:         Arming loopback tcpip on :51392 (adbd will restart)…
17:35:25.579 I AdbShell:         Loopback arm result on :51392 = true (listener took 8ms)
17:35:29.805 I RecorderLauncher: Attempt 1: launching recorder daemon
17:35:30.406 I RecorderLauncher: Recorder daemon connected on attempt 1; binder available
17:35:30.525 I AdbShell:         Wireless debugging disabled after the daemon launch (DROP_USB_KEEPS_ADBD)
17:35:32.324 I AdbConnectionService: Boot: recorder daemon connected=true
```

**One borrow. One arm, successful first time. One daemon launch, successful first time. No mDNS timeout,
no failed round. Boot-complete to recording-ready in 11.4 s**, and the switch handed back 120 ms after
the daemon connected.

Against the three boots before it: 17:04 — 5–6 cycles, 73 s, three failed arms. 17:28 — 2 cycles, 41 s,
two failed arms. 17:35 — 1 cycle, 11.4 s, none.

### The honest caveat

`listener took 8ms` means the **first** connect attempt succeeded, so the grace path added in
`LOOPBACK_ARM_GRACE_MS` never had to engage. This boot proves the path works when `adbd` is quick; it does
**not** prove the grace, which only matters when `adbd` is slow — which is precisely what 17:04 and 17:28
were. The same 2 s of fixed sleeps that failed twice at 17:28 succeeded here, so the difference is boot
load, and the grace remains insurance whose value has not yet been observed firing.

Worth watching for in a future report: a `listener took` figure in the **seconds**. That is the grace
doing its job, and it should still end in `= true`.

### Two small things this boot also showed

- `Recent boot (uptime < 90000ms); skipping killStaleDaemons — reboot already cleared any stale daemon`
  — working as intended.
- `Clearing 1 other recorder process(es): [18735] (I am 18739)` — a daemon left by the install before the
  reboot, cleared correctly by the one-recorder-host rule.
