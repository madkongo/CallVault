# "Start when they answer" — recording an outgoing call from the pickup, not the dial

🧪 VERIFYING as of 2026-09-22 — built, 18 new unit tests pass, not yet on a phone. To settle it: on the
OP12 with the toggle on, place one outgoing call that is answered and one that is not; the first file
should begin at the pickup (no ringing), the second should not exist. The log line to look for is
`Sending start INTENT for OUTGOING call after N polls: call answered`.

Requested by a user (2026-09-22, "Please add an option to start recording only when the other party
answers"). Feasibility in `2026-09-22-three-requests-feasibility.md`; the maintainer chose the
start-late design over recording from the dial and trimming the head.

## What it does

A toggle in Settings → Recording → Outgoing calls, **off by default**, no onboarding step. With it on, an
automatically recorded outgoing phone call is started at the first moment the phone reports the call
ACTIVE rather than at OFFHOOK (the dial). Phone calls only; the setting says so.

## How

- **Signal.** `PreciseCallState` (DIALING=3, ALERTING=4, ACTIVE=1) is behind `READ_PRECISE_PHONE_STATE`,
  `signature|privileged`, so the app cannot listen for it. The recorder host is the shell user on both
  paths and `dumpsys telephony.registry` prints `mForegroundCallState=<n>` — one line per phone.
  Measured 2026-09-22: present on the OP12 (Android 16), the OP9 (Android 14) and the emulator.
  **Measured on the emulator the same day, an outgoing call to 5551234 polled once a second:**
  `mCallState=2` from the dial to the end (OFFHOOK cannot tell), `mForegroundCallState` went
  `3` (DIALING) → `1` (ACTIVE) when the modem answered, `0` after hang-up. That is the transition the
  poll keys on; a real carrier adds ALERTING (4) between the two.
- **The read** is a new fixed key in `DiagnosticDumps`: `call_state` → `dumpsys telephony.registry |
  grep mForegroundCallState`. Fixed, read-only, ignores any argument, like the other keys.
- **Dual SIM.** The SIM not in the call says IDLE throughout, so all lines are returned and
  `AnswerWait.parseState` prefers an ACTIVE/HOLDING line, then any busy line, and says IDLE only when
  every line does. A `-m1` here would have waited out the whole ceiling on a call from SIM 2.
- **The wait** (`AnswerWait`, pure; `CallSessionManager.startWhenAnswered`, the loop): at OFFHOOK for an
  OUTGOING call with the toggle on, `ACTION_STANDBY` is sent instead of the start — the same warm-up
  the Record prompt uses, so the daemon is up before the pickup — and a coroutine polls the host every
  500 ms. ACTIVE or HOLDING → start. IDLE/DIALING/ALERTING → keep waiting. The telephony IDLE broadcast
  cancels the wait, so a call nobody answers records nothing.
- **Nothing may cost the call.** No readable state (an older host that refuses the key, a host not
  connected, a ROM without the line) → start at once, as if the option were off; 120 s without ACTIVE →
  start anyway. Both are logged with the reason.

## First real call — OP9, built-in mode, 2026-09-22 13:23 (🧪 file not yet listened to)

Outgoing carrier call, toggle on, automatic outgoing recording on. From logcat:

```
13:23:51.508  Sending standby INTENT for OUTGOING call; recording starts when it is answered.
13:23:56.440  Sending start INTENT for OUTGOING call after 8 polls: call answered (state=1, 4873ms).
13:23:56.987  Recording pipeline started successfully        (startHandoff returned true in 127ms)
13:24:00.876  Stopping active recording session
```

Rang 4.9 s, answered, recorded ~4 s, stopped at IDLE. **Measured: pipeline up 547 ms after the poll saw
ACTIVE** — up to 500 ms of poll interval on top of that is the most that can be lost of the pickup.
Whether the far party's first word survived is for the maintainer's ears. The unanswered case is still
to be seen.

## What it costs

📐 The file starts up to ~0.5 s (poll) plus the pipeline start after the pickup; the daemon is already
warm from standby. Measure on the phone whether the far party's first word survives; if not, 250 ms.

Known cosmetic point: while waiting, the service is in `Standby`, whose notification reads "Press to
start recording" — pressing it starts early, which is harmless, but the wording says "offer", not
"waiting for an answer". Left for the maintainer to judge on the phone before any notice work.

## Not covered

- App calls: the audio mode flips at call setup, not at pickup, and the far sink is silent until the
  app plays the remote voice; the only hint is the app's notification text, per app and per locale.
- Incoming calls: unaffected by design — OFFHOOK *is* the answer there.
- A call started from the Record prompt (automatic recording off): unaffected; the button is pressed
  after the dial anyway.

## Files

`services/call/AnswerWait.kt` (new), `services/call/CallSessionManager.kt` (`answerJob`,
`startWhenAnswered`, the RECORD branch), `server/DiagnosticDumps.kt` (`call_state`),
`data/AppPreferences.kt` (`RECORD_FROM_ANSWER`), `ui/viewmodels/SettingsViewModel.kt`,
`ui/screens/SettingsScreen.kt`, strings in 11 locales. Tests: `AnswerWaitTest`, `DiagnosticDumpsTest`.
