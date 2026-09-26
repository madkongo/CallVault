# "Call in progress / Press to start recording" stays up after the call

**Status:** 🧪 OPEN — cause of the first appearance not found. No logs exist (neither the maintainer's
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
