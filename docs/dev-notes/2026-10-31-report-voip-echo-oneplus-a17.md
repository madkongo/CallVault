# Report: "the other person is recorded with an echo" — OnePlus CPH2747, Android 17 (2026-10-31)

🧪 2026-11-01: the alignment fix (2.4.8-echofix) removes the echo once it locks on, 7–13 s into the call; the
reporter still hears the echo at the start. See `rollback/voip-echo-align.md`. (The original line "Investigation in progress. Nothing fixed, nothing built" is out of date.)

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

## Probe result (2026-10-31, two stereo calls from 2.4.8-echoprobe) — cause found
Files arrived intact (2 ch, sizes equal to the app's own log). L = mic, R = far.
- The far voice is **in the mic channel**, delayed and steady through each call:
  WhatsApp call **≈76 ms** (24 strong windows, 72–82 ms); second app **≈59 ms** (38 windows, 54–63 ms after the
  first seconds). Per-window correlation up to 0.9–0.95.
- Its level there is **as loud or louder than the clean far copy** (least-squares gain 1–5×; in far-talking
  windows the far signal explains up to 93 % of the mic energy). The hot raw `MIC` on this phone (peaks ≈31 000
  every window) amplifies what the earpiece leaks.
- Mixed to mono, the clean far voice + its 60–76 ms copy = the "reverberant" far side the maintainer heard.
  The OP9 does not do this (maintainer listened), so it is device-dependent, but the mechanism is ours: an
  unprocessed `MIC` near side with no echo cancelling.
- ffmpeg's `anlms` (plain NLMS) was tried offline as a quick demo and is not good enough (no double-talk
  handling, eps capped); a real AEC is needed.

## Fix direction (📐 not built; integration to be designed with the maintainer)
Acoustic echo cancelling on the near channel with the far channel as the reference, before the downmix, in
`VoipCaptureSession`. Delay ≈60–100 ms here → tail ≥150 ms. Candidates: speexdsp MDF (BSD, small C) or WebRTC
AEC3 (`webrtc-audio-processing`, BSD, stronger double-talk + residual suppression). Must not touch carrier
calls. Prove offline on these two files first (A/B for the maintainer), then a probe build for Евгений.

## Offline A/B (2026-10-31) — files in ~/Downloads/echo-AB/, tools in scratchpad echo-tools/
Mic channel scored on both calls (far-talk windows: mic level drop = echo removed, includes any double-talk loss;
far-silent windows: own voice change):
| method | far talking | own voice alone |
|---|---|---|
| speexdsp 1.2.1 MDF + residual suppressor, 300 ms tail | −4 dB | ≤0.4 dB |
| WebRTC AEC3 (livekit 1.2.0 APM, 48 kHz, EC only) | **−15 / −30 dB** | ≤0.5 dB |
| own whole-call least-squares (STFT, 150 ms) + mild suppressor | −5 / −7 dB | 0 dB |
Linear methods top out near 5 dB → the echo is largely nonlinear (hot mic). AEC3's suppressor does the work.
📐 Double-talk damage not measured — needs listening.
Research (subagent, verified): LocalVQE (github.com/localai-org/LocalVQE, Apache-2.0, GGML, v1.4-AEC 203K params,
pushed 2026-10-19) is the one newer candidate; not run (building third-party code needs the maintainer's OK).

## 2026-11-01 — the echo comes from the mix, not the mic (🧪 by ear, maintainer)

- The maintainer listened to the three single-channel files:
  - `B_his_mic_only_before.wav` (raw mic alone): "there is no echo at all and both sound very clear".
  - The far-channel-only file and the AEC "after" files did not fix it.
- So the mic channel alone carries both voices cleanly. The echo is made by our mono downmix: it adds the clean far copy to the far voice that has already leaked into the mic 60–76 ms later. Two copies of the same voice that far apart are heard as echo or reverb. That is why echo cancelling (−15 dB on the leak) did not change what was heard.
- Delay per 1 s block (GCC-PHAT): steady through each call, 75.3 ms (WhatsApp) and 58.7 ms (the other app). It is fixed per call, not drifting. The earlier "54–98 ms drift" came from blocks where his own voice dominates.
- Test mixes are in `~/Downloads/echo-AB/mixes/`:
  - `*_1_now_mixed` — as the app mixes today
  - `*_2_fixed_far_delayed` — far channel delayed by the measured lag before mixing
  - `*_3_mic_only`
- After the shift, the far copy in the mic sits at +0.0 ms in both calls (it was at +75.3 and +58.7).
- Fix idea, 📐 not built: per call, measure the lag between the far channel and its copy in the mic. If the copy is strong, delay the far channel by that lag before the downmix. With a headset (no leak) nothing changes.
- Waiting on the maintainer's ear for mix 2 against mix 1.
- 2026-11-01, maintainer by ear: mix 2 (aligned) removes the echo. Mix 3 (mic only) sounds "slightly better than 2 but its a really small difference". Next step: design the in-app fix together (see the options in the chat of that date).
- 2026-11-01: maintainer chose "line up only": never turn the far side down, never use the mic alone. Built and merged as f1288975; see `docs/dev-notes/rollback/voip-echo-align.md`. The test APK `2.4.8-echofix` is waiting for Евгений. Listening file of the app's actual output: `~/Downloads/echo-AB/mixes/*_4_app_fix_as_recorded.wav`.
