# Report alex_812: recorder stops mid-day, nobody is told

📐 CALCULATED 2026-10-29: read from the report and the code. Nothing reproduced, nothing built.
Status: ⏸️ PARKED by the maintainer 2026-10-29 ("no need at the moment"). Kept as is.

## What the user said

The app sometimes stops working and doesn't recover until a reboot. When that happens there are two
notifications in the shade: one that usually says everything is OK but shows the status as stopped, and
one saying recording cannot work. This happened a couple of times in one week. They found out only
because a whole day had no recordings. They rely on the "recording started" vibration and don't check
the app, so they asked for an alert when the recorder stops working.

## What the report shows

File: `callvault_report_2026-10-29_17-19.txt` (maintainer's Downloads).

- OnePlus 12, CPH2581, Android 16, ROM CPH2581_16.0.10.501(EX01), app 2.4.7 (20470).
- Standalone mode; USB debugging ON; Wireless debugging OFF; offline recording (loopback) ON.
- `WRITE_SECURE_SETTINGS: false`; `Shell grants (OEM gate): BLOCKED (OPPO)`.
- `Binder connected: true`, so the report was taken **after** recovery.
- **Debug logging was off**: the report has only the setup journal (2026-10-12) and no recorder host
  lines. **The cause of the failure is not in the report.**

## Findings (from code)

1. **The start vibration doesn't lie.** `RecordingNotificationHelper.handleStateChangeToasts` vibrates
   only on the move to `Active`, which `RecordingForegroundService.startNewRecordingSession` sets after
   `startPipeline` succeeds. When the recorder is down there is no buzz at all, and a missing buzz is
   easy to miss.
2. **A runtime outage is silent.** When the recorder dies while the phone is running,
   `DaemonKeepAliveService.updateNotification(false)` changes the standing notice to "Calls aren't being
   recorded" (`notif_readiness_down_title`). That notice is on an `IMPORTANCE_MIN` channel: no sound, no
   vibration, collapsed.
3. **The loud warning only fires after a reboot.** `SilentFailureNotifier.warnRecorderUnavailable`
   ("CallVault cannot record right now", `health_warnings` channel, DEFAULT importance) is posted only
   from `AdbConnectionService` (boot) and `ShizukuLifecycleWatcher`. In standalone mode a mid-day death
   never reaches it.
4. **Likely reason it doesn't recover on its own (hypothesis, unproven):** OPPO/OnePlus blocks shell
   grants, so the app has no `WRITE_SECURE_SETTINGS` and cannot switch Wireless debugging on to
   relaunch. Recovery then depends on the loopback `:48400` listener surviving. Anything that restarts
   adbd (USB mode change, screen-off adbd kill; see memory `screen-off-adbd-kill-and-mitigations`)
   drops that listener, and the keep-alive stays in a not-ready state until the user acts.
5. **"Two notifications, OK but stopped + cannot record":** probably two standing notices disagreeing
   (keep-alive vs another service's ready notice, or the readiness notice next to the health warning).
   Not identified. A screenshot is needed.

## Suggestions (not built)

- **A. Recorder-down alert.** If the keep-alive has been not-ready for more than about 2 minutes and no
  call is in progress, post `warnRecorderUnavailable` once (vibrates by default). Clear it when the
  recorder comes back (`updateNotification(true)` already clears it). Wording must match the mode and
  the cause (Wi-Fi vs pairing vs Shizuku). Notification only; it doesn't touch capture. Main risk is
  noise from short normal restarts, which the delay covers.
- **B. Find the two contradicting notices** once a screenshot arrives, and make sure only one standing
  notice shows the recorder state.
- **C. Root cause** needs a debug log taken while it is broken, before any restart.

## Reply drafted for the reporter (maintainer posts)

Asked them to turn on debug logging, and next time, before restarting anything, take a screenshot of
both notifications and send the report. Told them the silent-alert gap is acknowledged.
