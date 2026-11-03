# Rollback: line the far side up with its echo (VoIP)

✅ VERIFIED 2026-11-02 on Евгений's OnePlus CPH2747 (Android 17, Shizuku), build 2.4.8-echofix2, reported via the maintainer: the echo goes after the first few seconds; he can live with what is left at the start. Other phones untested; the start-of-call echo stays open (parked).

**Field result 2026-11-01 (Евгений, via the maintainer, build 2.4.8-echofix):** "There is a bit of an echo at the
beginning of the conversation, then it gets good, without an echo." The log shows a lock-on in both calls: Telegram
73.6 ms at about 7.5 s, WhatsApp 81.2 ms at about 13 s. In all three of his files the echo is measurable from second
3 to second 7, then gone. Still 🧪: the echo at the start is open. The maintainer turned down "start from the last
call's delay". Later the same day Евгений put it at "1-3 seconds of echo, then the echo goes away". The maintainer:
"echofix2 might be good enough". Start-echo work is **parked**, not built.

**Parked research (2026-11-01, so it is not redone):**
- **Faster lock-on alone barely helps.** Python on the two stereo probe calls (scratchpad `echo-fast/proto3.py`):
  checking every 0.5 s with peak ≥ x10 and a clear-winner rule (best match ≥ 2.5x the runner-up outside ±3 ms) locked
  at 8.6 s / 7.6 s (today: 8.0 / 10.1), with 0 false locks in 160 mismatched pairs. The correct delay is first
  visible at about 7.5 s / 6.5 s, so no online method can do much better: the limit is when the other person speaks.
- **Aggressive settings lock onto the wrong delay.** Per-frame (0.25-0.5 s) accumulated GCC-PHAT, or 0.25 s steps with
  3 agreeing, locked on wrong delays: the connecting tone gave 24 ms or 164 ms (u4+), and 3-8 of 20 mismatched pairs
  locked falsely. The connecting tone seems to reach the mic by its own path with its own delay.
- **If it comes back:** hold the first ~20 s of each app call in memory (about 4 MB), write it once the delay is
  found, then run live as today. That fixes the opening with hindsight. ShizuCallRecorder has no alignment: its
  "echo cancellation" is the VOICE_COMMUNICATION mic source, which is zero-filled for us during an app call (see
  VoipCaptureSession).

🧪 VERIFYING — merged 2026-11-01. 2353 unit tests pass, and the logic was checked on the two report calls. No phone
has run it yet. To settle: Евгений (OnePlus CPH2747, A17, Shizuku, earpiece) installs `2.4.8-echofix` and makes
one WhatsApp call of at least 30 s.
- After the first ~10 s the echo should be gone.
- The debug log shows `VoIP echo: far voice found in the mic … ms late`.
Then one call on a headset: the recording should sound as before, with no such log line.

## What changed

- `server/echo/` (new):
  - `Fft`.
  - `EchoLagEstimator`: GCC-PHAT, 20–250 ms.
  - `EchoAligner`:
    - Delays the far side once three checks in a row agree within 1 ms, each at least x8 above the search floor.
    - The switch happens while the far side is quiet, or after 10 s at the latest.
    - The search runs on a background thread.
  - `SafeFarAligner`: on any error, the far side stays raw for the rest of the call.
- `VoipCaptureSession.captureLoop`: the R (far) channel fed to the speaker-turn detector and to the mono downmix
  is the lined-up far side. The far-silence check still reads the raw far side.
- App calls only. Carrier calls and the handoff/direct paths are untouched. No pref, database or file-format change.

## Measured (offline, on the Kotlin code)

| Call | Leak found | Settled at | Mismatched near/far pair |
|---|---|---|---|
| WhatsApp 20-42-17 | 75.8 ms | 8.0 s | never settles |
| other app 20-42-08 | 58.7 ms | 10.1 s | |

The first 8–10 s of a call still carry the echo, because the mix is encoded live.

## How to roll back

```
git revert -m 1 f1288975
```

(`f1288975` = the merge of `feat/voip-echo-align` into integration/next.) Nothing to migrate.

The test APK is built from branch `test/voip-echo-align-2.4.8` (v2.4.8 + 68ec7ece, 76dabd1a), not pushed:
`~/Downloads/CallVault-2.4.8-echofix/CallVault-2.4.8-echofix.apk`, versionCode 20480,
sha256 174e5d77…7a1e.
