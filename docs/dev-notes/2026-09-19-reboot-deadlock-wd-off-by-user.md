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
