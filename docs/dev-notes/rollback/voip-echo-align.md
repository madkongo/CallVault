# Rollback: line the far side up with its echo (VoIP)


**Field result 2026-11-01 (Евгений, via the maintainer, build 2.4.8-echofix):** "There is a bit of an echo at the
beginning of the conversation, then it gets good, without an echo." The log shows a lock-on in both calls: Telegram
73.6 ms at about 7.5 s, WhatsApp 81.2 ms at about 13 s. In all three of his files the echo is measurable from second
3 to second 7, then gone. Still 🧪: the echo at the start is open. The maintainer turned down "start from the last
call's delay"; research (look-ahead mix plus an accumulating estimator) is waiting on a decision.

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
