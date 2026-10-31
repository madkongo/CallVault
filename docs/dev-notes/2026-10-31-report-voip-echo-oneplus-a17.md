# Report: "the other person is recorded with an echo" — OnePlus CPH2747, Android 17 (2026-10-31)

📐 Investigation in progress. Nothing fixed, nothing built.

**Reporter:** Евгений (Telegram). OnePlus CPH2747, Android 17 (CPH2747_17.0.0.102), Shizuku mode, 2.4.8.
Phone held to the ear (earpiece). Every messenger: Max, WhatsApp, Telegram.

## What the logs show (debug report 21:04–21:07)
- Both app calls recorded; sync offset stays within ±5 ms; no drops; `farPartyHeard=true`.
- Route: earpiece, speakerphone=false. Near side = `MIC`, bottom mic.
- Near peak ≈ 31 000 in every 10 s window (also when far is silent). Far peaks 0–11 000.
- The earlier report (17:25) lost two calls to an unwritable folder AND showed no warning:
  `CallEvidence.looksLikeACall` saw no communication-source capture on this phone, so the issue-#29 gate
  hid the "not recorded" warning. Reporter re-picked the folder; recording works now. The gate gap is open.

## What the two recordings show (45.6 s WhatsApp, 60.5 s second app; mono Opus 48 kHz)
- File max −2.4 dBFS: loud, not clipped.
- No steady delayed copy found: whitened autocorrelation 20–600 ms and onset-envelope correlation to 2 s
  give only noise-level peaks (z≈3–4, r≈0.06) at changing lags.
- WhatsApp ringback (5.7–7.8 s, 440+480 Hz, beats every 25 ms — that is the tone itself) stops dead:
  no tail after it ends. If the earpiece leaked into the hot mic with output latency, a delayed copy of
  the tone would show as a tail. It does not. This weakens the "earpiece → mic" theory.
- Scripts: scratchpad `echo-tools/{acorr,phat,env}.py` (not committed).

## Open
- Need a timestamp from the reporter where the echo is heard, and whose voice it is.
- Mono files cannot separate the sides. A two-channel debug capture (near L / far R) would settle it.

## Maintainer listened (2026-10-31)
OP9 app calls do NOT sound like this. Евгений's files: the far party sounds **reverberant** whenever they speak,
throughout the call. Fits a short (<20 ms) second copy of the far voice via the hot mic; unproven from mono.

## Probe build sent to the reporter (not tested on our phones, by the maintainer's choice)
`2.4.8-echoprobe` (versionCode 20480), branch `probe/voip-stereo-echo` off v2.4.8, commit on that branch only.
App calls are saved as stereo: LEFT = near (MIC), RIGHT = far (policy submix); Opus bitrate doubled. Carrier
calls unchanged. APK sha256 566238ad94b5d26e769ec9c4d275821983462d34b0c522ef0c67d53cdfed2311.
What to read from his file: far voice audible in LEFT → mic picks up the earpiece (fix = echo cancelling with
RIGHT as reference). RIGHT itself reverberant → the doubling is upstream of us.
