# 2026-09-19 — after a reboot the phone can stop recording for good: the "user turned Wireless debugging off" flag deadlocks recovery

Status: **❌ NOT WORKING 2026-09-19** — reproduced on the OP12 (the maintainer's daily driver), diagnosed
from live device state, and cleared by hand. **Not fixed.** Present in the branch build installed
2026-09-19 15:49, and the logic is old enough that it is almost certainly in **2.3.0 as shipped**.

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
the app.

The only exits are the user turning Wireless debugging on by hand, or the "Turn Wireless debugging on"
action button on the notification.

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
