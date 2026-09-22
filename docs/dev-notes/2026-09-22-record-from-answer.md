# "Start when they answer" — recording an outgoing call from the pickup, not the dial

✅ VERIFIED 2026-09-22 by the maintainer on the OP9 (built-in mode): one answered phone call recorded from
the pickup ("sounds good"), one unanswered left no file; one answered WhatsApp call held through the
ringing ("it sounds good"), one unanswered discarded. **Shizuku mode ✅ 2026-09-22 14:49:** the OP9 in
Shizuku mode, 7 polls, start at the answer — the `call_state` read works through the Shizuku host too. Still 🧪: Shizuku mode, a dual-SIM call from
SIM 2, and a Samsung. The log line to look for is `Sending start INTENT for OUTGOING call after N polls`.

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

## First real call — OP9, built-in mode, 2026-09-22 13:23 (✅ maintainer listened: "sounds good")

Outgoing carrier call, toggle on, automatic outgoing recording on. From logcat:

```
13:23:51.508  Sending standby INTENT for OUTGOING call; recording starts when it is answered.
13:23:56.440  Sending start INTENT for OUTGOING call after 8 polls: call answered (state=1, 4873ms).
13:23:56.987  Recording pipeline started successfully        (startHandoff returned true in 127ms)
13:24:00.876  Stopping active recording session
```

Rang 4.9 s, answered, recorded ~4 s, stopped at IDLE. **Measured: pipeline up 547 ms after the poll saw
ACTIVE** — up to 500 ms of poll interval on top of that is the most that can be lost of the pickup.
The file: `20260922_132356.485+0300_out_פרוזה.ogg`, **3.84 s**, stamped at the pickup (13:23:56), not the
dial (13:23:51) — from the dial it would have been ~9 s. The maintainer: "turned the feature on, answered,
said a few words and hanged up." Whether the far party's first word survived is for his ears.

**Second call, 13:25, not answered:** standby at 13:25:19.692, rang 13.5 s, IDLE at 13:25:33 → the wait
was cancelled, no start line, "exiting standby state", and the folder holds no 13:25 file. The
unanswered case is as designed.

## What it costs

📐 The file starts up to ~0.5 s (poll) plus the pipeline start after the pickup; the daemon is already
warm from standby. Measure on the phone whether the far party's first word survives; if not, 250 ms.

Known cosmetic point: while waiting, the service is in `Standby`, whose notification reads "Press to
start recording" — pressing it starts early, which is harmless, but the wording says "offer", not
"waiting for an answer". Left for the maintainer to judge on the phone before any notice work.

## App calls — the pickup IS visible, in the notification (measured 2026-09-22 13:28, OP9, WhatsApp 2.26.36.74)

WhatsApp registers nothing with Telecom (`dumpsys telecom` on both phones lists only Google Meet as a
self-managed account), so there is no call state to poll. `spike-tools/voip-answer-timeline.sh` sampled
the call notification, WhatsApp's audio players and the mic once a second through an outgoing WhatsApp
call from the OP9, answered on the OP12 after 13 s:

| time | `android.text` | `android.showChronometer` | WhatsApp players |
|---|---|---|---|
| 13:28:51 – 13:29:04 | `Ringing…` | **false** | SoundPool idle + OpenSL `started`, both VOICE_COMMUNICATION |
| 13:29:05 – hang-up | `Ongoing voice call` | **true** | unchanged |

**`showChronometer` flips false → true at the answer** — the platform flag behind the call timer,
locale-independent, on the notification the host already reads with `cmd notification get` for the
caller's name. The audio players are no signal: the voice track is started from the dial and carries the
ringback. 🧪 Telegram and Signal not yet looked at.

**Built the same afternoon** (`feat/record-on-answer-voip`, same toggle): the VoIP capture still starts
at the audio-mode flip (arming cannot be retried) but is held **paused** (`setVoipPaused`, drops frames,
never touches the mic) while the host is polled every 500 ms through the new `voipCallAnswered(package)`
— appended LAST in the AIDL, transaction codes are positional; `RecorderTransactionCodesTest` pins it.
Released at ANSWERED. UNKNOWN (no timer flag, no notification, an older host) releases at once; the
same 120 s ceiling applies. The user's Pause outranks the release; their Resume ends the hold.
Direction-free: the timer starts at connect for incoming calls too. The file name keeps the dial time.

**First real call, OP9, 2026-09-22 13:36, WhatsApp to the OP12 (✅ maintainer listened 14:05: "it sounds good"):**

```
13:36:34.062  App-call recording held until the call is answered
13:36:46.845  App-call recording released after 17 polls: call answered (13055ms)
13:36:55.837  VoIP capture finished: 8s, 6 silence-filled chunks, farPartyHeard=true
```

The file is **8.4 s** for a 22 s call; the 13:28 probe call, recorded from the dial by the previous
build, is 21.8 s. Named פרוזה (the late-caller retry ran alongside the hold).

**Unanswered app call, first try (13:39) — ❌ a 6 KB stub was published.** At hang-up WhatsApp removes
its notification BEFORE the audio mode drops, so the poll read "no timer" → released → the last 1.5 s
went into a file. Fixed `b82eef03`: the host tells NO_NOTIFICATION from NO_TIMER; once ringing has been
seen only ANSWERED (or the 120 s ceiling) releases; a notification not posted yet gets 5 s of grace; and
a recording that ends while still held is discarded (`454f5582`).
**Second try (13:56):** held at 13:56:29, rang 14 s, hung up → "App call ended before it was answered;
discarding the held recording"; no file. As designed.

Still 🧪: Telegram and Signal (NO_TIMER → recorded from the
start, which is the safe answer but not the feature), an incoming app call.

## Not covered

- App calls whose notification shows no call timer: recorded from the start, as before the option.
- Incoming calls: unaffected by design — OFFHOOK *is* the answer there.
- A call started from the Record prompt (automatic recording off): unaffected; the button is pressed
  after the dial anyway.

## Files

`services/call/AnswerWait.kt` (new), `services/call/CallSessionManager.kt` (`answerJob`,
`startWhenAnswered`, the RECORD branch), `server/DiagnosticDumps.kt` (`call_state`),
`data/AppPreferences.kt` (`RECORD_FROM_ANSWER`), `ui/viewmodels/SettingsViewModel.kt`,
`ui/screens/SettingsScreen.kt`, strings in 11 locales. Tests: `AnswerWaitTest`, `DiagnosticDumpsTest`.
