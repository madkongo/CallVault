# "Call in progress / Press to start recording" stays up after the call

**Status:** 🧪 VERIFYING as of 2026-09-27 — fix built on `fix/stuck-call-notification` (`2b73eec3`), proven on the emulator with the race forced; not yet on a real phone. To settle: the maintainer runs the build on the OP12 for a few days of normal calls (and ideally the LAVA/Samsung reporters), and no call notification outlives its call.

Original status: cause of the first appearance not found. No logs exist (neither the maintainer's
OP12 nor the reporter's Samsung had debug logging on). The maintainer and the reporter will reproduce
with debug logging on and send logs. **Parked by the maintainer 2026-09-26 in favour of issue #42; must
be picked up again.**

## The report

- Maintainer, OP12, 2026-09-25 15:38: after a call ended the notification "Call in progress — Press to
  start recording" with a **Record** button stayed in the shade. Force-stopping the app cleared it.
- A Samsung user (privately): "If I swipe it it pops up again immediately, and if I tap on record, I get
  a new error notification" — the error is *"An unexpected error occurred during recording…"*
  (`recording_unexpected_error`).
- Maintainer: started with **2.4.1**.

## What the code establishes

- The notification is `RecordingForegroundService` in a standby state (`RecordingNotificationHelper`,
  the `else` branch: `recording_notification_press_to_start` + `ACTION_MANUAL_START`).
- **Record → "unexpected error"** is `ACTION_MANUAL_START` with `currentMeta == null`
  (`RecordingForegroundService.onStartCommand`). So the lingering service instance holds **no call
  metadata**. The call flow never starts it that way: `ACTION_STANDBY` and `ACTION_START_RECORDING` from
  `CallSessionManager` always carry the metadata, and every stop path calls `stopSelf()`.
- The intents that reach the service without metadata and do **not** stop it are its own notification's:
  `ACTION_NOTIFICATION_DISMISSED` (the deleteIntent) re-posts via `updateNotification()`, and
  Pause / Resume / Mark do nothing without a session. A swipe therefore brings the notification straight
  back, forever — the reporter's loop. Force-stop is the only exit.
- The notification shares id 4720 with the keep-alive (`SharedStatusNotice`, since 2.3.0), so a
  recording notification's content — including its deleteIntent and Record action — can be the one the
  keep-alive holds.

## Tested on the emulator (2.4.1, simulated calls via the emulator console)

| Scenario | Result |
|---|---|
| Incoming call, auto-record incoming off (offer → standby), answered, hung up | clean: "Ready" back within ~0.3 s |
| Outgoing call, auto-record outgoing + record-from-answer on, answered, hung up | clean: recorded, "Ready" back |

No reproduction yet.

## Hypotheses not yet tested

1. The notification is dismissed (swipe, or something the ROM does) around the moment the call's service
   stops, so the deleteIntent starts a fresh metadata-less instance that never stops.
2. Record-from-answer (new in 2.4.1) shows this exact standby notification on every outgoing call while
   it rings; something in the answer-wait / hang-up ordering leaves a start or dismiss behind. Fits
   "started in 2.4.1", nothing found in the code.
3. An app call (WhatsApp) that One UI also reports as telephony OFFHOOK, combined with the 2.4.1
   answer-wait (it waits up to 120 s for a carrier ACTIVE that never comes).

## What to collect when it is reproduced

Debug logging on **before** the call ([[debug-logs-only-exist-when-enabled]]). Then: phone or app call,
incoming or outgoing, record-from-answer on/off, whether the notification was swiped during the call,
and the Save-log export right after it appears.

## Fix candidate (not built — root cause first)

Any service command that arrives with no call in progress and no session (dismiss, Pause, Resume,
Mark, Record without metadata) stops the service instead of re-posting; Record without a call does
nothing rather than raising an error. That ends the loop for everyone, but it would hide whatever
creates the first instance, so it waits for the log.

## 2026-09-27 — third report, with logs (LAVA LXX508, Android 14, 2.4.1, standalone)

"After the call ends, it still detects as if there is still a call going on." Debug + system report.
The app log was deleted just before (only 18:51:30 on), but the system report kept ActivityManager lines:

```
18:48:32.950  START_RECORDING starts the service (the call)
18:48:33.0x   startForeground ×3 (the recording's notification updates)
18:50:21.583  startForeground            <- the STOP: onStartCommand posts BEFORE handling the stop
18:50:52.222  PAUSE_RECORDING, uidState TOP   <- user taps Pause on the notification, 30 s after the call
18:51:38.221  service "initialized"; DISMISSED → "reposting"   <- a swipe starts a fresh instance
18:51:41.327  Record → "Start request received without metadata" → error, stop
```

**What this proves:** the call's service stopped at ~18:50:21, yet its **"Recording in progress" notification,
with Pause, was still in the shade 30 s later.** So the shade kept the recording's content after the
service that owned it was gone. Everything after is the known loop (Pause/swipe/Record each start a
metadata-less instance).

**Why the content survives (📐, AOSP android14 `ServiceRecord.postNotification`):** a service's
`startForeground` notification is posted **asynchronously on system_server's AMS handler** ("Do
asynchronous communication with notification manager to avoid deadlocks"). The keep-alive's "Ready"
goes straight to NotificationManager with `notify()`. Both write id 4720. At the stop we do both in a
few milliseconds:

1. `onStartCommand` for STOP calls `startForegroundWithType(...)` first — re-posting the recording
   notification under 4720 (the 18:50:21.583 line);
2. `stopRecordingSessionAndService` → `stopForeground(REMOVE)` (Android does not cancel 4720, the
   keep-alive still holds it) → `SharedStatusNotice.release()` → keep-alive `notify(4720, Ready)`.

If system_server delivers (1) after (2) — easy on a slow phone — the recording's content lands last and
nothing ever replaces it. Fits a budget LAVA, fits "sometimes" on the OP12, and fits every symptom.
The 2.4.1 link is weaker: record-from-answer adds more posts per outgoing call (standby, then start),
widening the window; the race itself dates from the shared notification (2.3.0).

**Also found:** `DaemonKeepAliveService.onStartCommand` sets `SharedStatusNotice.onReleased` only after
the VoIP action branches (`ACTION_VOIP_STOP` / FLAG / PAUSE / RESUME return early). A keep-alive
instance created by one of those never re-posts "Ready" on release. Minor, same family.

## Fix proposal (not built)

1. **STOP posts nothing.** Skip the opening `startForeground` for commands that arrive by
   `startService` (STOP, DISMISSED, Pause, Resume, Mark): only `startForegroundService` carries the 5-s
   rule. Removes the late write at its source.
2. **The keep-alive heals the shade.** On release, and on each watchdog tick while no recording holds
   the notice, check the posted 4720 (`getActiveNotifications`); if it is on the recording channel,
   post "Ready" again. Catches this race and any other way the content goes stale.
3. **No call, no service.** Dismiss / Pause / Resume / Mark / Record-without-metadata with no session
   and no call in progress: stop the service instead of re-posting or raising an error.
4. Set `onReleased` before the VoIP early returns.

Test: unit tests for (3)'s decision; on the emulator, delay the release to force the order and show
the shade heals; then the maintainer and a reporter on real phones.

## 2026-09-27 — built and tested on the emulator (`2b73eec3`)

All four parts of the proposal, as proposed. 1821 unit tests, 0 failures (new: `RecordingCommandPolicyTest`
5, `SharedStatusNoticeTest` +6).

Emulator (Android 16), simulated calls. To force the race, a throw-away build re-posted the recording
notification on id 4720 2 s after each stop (not committed), with the heal delay raised to 20 s so the
leftover could be touched:

| Test | Result |
|---|---|
| Forced leftover, untouched | "Recording in progress" stayed 18 s after the call, then `A leftover call notification (recording_channel_service) was still up after the call; putting "Ready" back` |
| Forced leftover, tap **Pause** (the LAVA sequence) | `'…PAUSE_RECORDING' arrived with no call in progress; ending this service instead of re-posting`; "Ready" back. Before the fix this is what created the immortal "Press to start recording". |
| Real build: outgoing, record-from-answer, hang up | recorded; "Ready" back within 5 s |
| Real build: incoming offered, tap **Record** mid-call, hang up | recording started from the button; "Ready" back |

Note: a first attempt with the late post at 0.4 s landed *before* "Ready" and changed nothing — the
race only bites when the stray post arrives after the keep-alive's, which is what system_server's
asynchronous delivery allows on a slow phone.
