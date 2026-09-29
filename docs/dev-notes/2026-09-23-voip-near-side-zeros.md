# VoIP near side: the "zeros" are mostly not lost voice — our re-take is the defect

**Status:** 📐 researched 2026-09-23 from logs, `dumpsys audio` and AOSP source. Nothing built, nothing
heard. To settle it: one WhatsApp call on the OP9 with the diagnostic build proposed below, then the
reporter's log from the same build.

**Corrects** the "Still open" paragraph of `2026-09-22-voip-sync-instrumentation.md` and point 2 of its
reporter-log reading: "half of the reporter's own voice is digital silence" and "the mic re-take and
WhatsApp's own capture restart fight for the mic" were both inferred from our own log line, which
says *"near capture silenced by the platform"* whenever it sees 15 zero chunks. It never asked the
platform. On the OP9 the platform says otherwise.

## In plain words

1. On the OP9, Android never silenced our microphone and WhatsApp never restarted its own during the
   whole call. So there was no fight.
2. The all-zero stretches are most likely the phone's call audio processing writing exact silence
   while you are not talking (or while the other side talks, on speaker).
3. Our code reads those stretches as "we were silenced", closes the mic and opens a new one. Each
   re-open loses 20–120 ms of whatever the mic had. It does this every ~0.4 s through every pause,
   so the first syllable after a pause is sometimes clipped. **That** is the real loss, and it is ours.
4. Fix: re-open the mic only when Android itself says we were silenced. Android has an API for exactly
   that (`isClientSilenced`, API 29; our minSdk is 30).

## Evidence

### How Android shares the mic (AOSP `AudioPolicyService::updateUidStates_l`, 14/15/16)

- Among ordinary captures, **one uid wins**: the one whose app is on top, else the latest starter.
  Everyone else is "silenced" — they keep getting buffers, full of zeros. This is the July S24 FE case.
- A **privacy-sensitive** capture (`VOICE_COMMUNICATION` is one by default) is ranked separately. When
  both kinds run, the ordinary winner keeps the mic too **if it holds `CAPTURE_AUDIO_OUTPUT`** (A16:
  `canBypassConcurrentPolicy`, which still counts that permission).
- `com.android.shell` holds `CAPTURE_AUDIO_OUTPUT` on both our phones (checked with `dumpsys package`).

So what WhatsApp opens decides everything:

| WhatsApp captures with | Our shell `MIC` capture |
|---|---|
| `VOICE_COMMUNICATION` (sensitive) | both get audio, no contest |
| `MIC` (ordinary) | one of us at a time, latest starter wins → a real fight |

### What the phones actually did (`dumpsys audio`, recording-activity history)

- **OP9, 2026-09-23 10:36 call (the slot-pairing test):** WhatsApp opened `VOICE_COMMUNICATION` once
  (riid 1551) at 10:36:06 and kept it to 10:36:47. No WhatsApp restart. **Not one `silenced` event**
  for anyone. Our 20 re-takes all show up as new shell `MIC` records, each `not silenced`.
- **OP12, same morning:** WhatsApp `VOICE_COMMUNICATION`, our `MIC` opened once per call and never
  re-taken. Records cleanly — this is why.
- **S24 FE, July** (`2026-07-30-voip-near-party-silenced-on-one-ui.md`): WhatsApp captured with
  **`src:MIC`**, and there the platform did silence us. The real fight exists — on that phone, with
  that source.

### The zeros do not behave like silencing

Silencing is a state: it lasts until someone starts or stops a capture. The logs show otherwise:

- **Short zero runs on a live record.** OP9: 595 zero chunks, only 300 of them the 15-chunk runs that
  trigger a re-take; **295 (5.9 s) were shorter runs that ended by themselves** with no re-take and no
  capture start/stop anywhere. Reporter: 3094 zero chunks, 155 × 15 trigger runs, **769 (15 s) in
  shorter runs**. A silenced record cannot recover on its own; a gate between words can.
- **Chains.** A fresh record keeps coming up zero: OP9 re-takes #3–#10 every ~0.4 s, reporter 116 of
  155 cycles exactly 15 zeros at ~0.32 s. That is the pause continuing, not WhatsApp restarting its
  capture three times a second.
- **Share of zeros.** Reporter (speaker from 5 s): 51 %. OP9 (earpiece): 29 %. A normal call is roughly
  half listening, and speakerphone echo control mutes the near mic harder. 📐 The 10-s windows do **not**
  show it cleanly: zero share runs 46–71 % where the far side peaks loud and 12–69 % where it is quiet —
  a 10-s peak is too coarse to say who was talking. Checked, not a finding.

📐 **Not established:** that our `MIC` record receives the call's processed signal (so its zeros are
exactly what WhatsApp sent the other side). It fits every number above; it is not proven. The reporter's
S21 Ultra is also not proven to be free of the real fight — his log has no `dumpsys` in it.

## What the re-take costs today

| | re-takes | gap each | total gap | per call-minute |
|---|---|---|---|---|
| OP9, 40 s | 20 | ~113 ms | 2.3 s | 3.4 s |
| Reporter, 120 s | 155 | ~46 ms | 7.2 s | 3.6 s |

Re-takes fire only after 300 ms of zeros, so most gaps land in a pause. But a chain re-opens every
~0.4 s through the whole pause, so a word that starts inside a gap loses its start: roughly 1 in 4
onsets on the OP9 (113 / 400 ms), 1 in 7 on the Galaxy (46 / 320 ms) 📐. Plus the slot pairer fills
each gap with silence (OP9 `sub=103`, 2 s), which is correct but is still audio the file does not have.

## Proposed fix (not built — needs the maintainer's word)

1. **Ask before re-taking.** On 15 zero chunks, check `nearRecord.activeRecordingConfiguration
   ?.isClientSilenced`. `false` → it is silence, keep the record. `true` → re-take as today.
   `null` (config unavailable) → today's heuristic, so no phone gets worse.
2. **React to real silencing at once.** `AudioRecord.registerAudioRecordingCallback` fires on the
   silenced transition; re-take then instead of 300 ms later. On an S24-FE-style phone that cuts each
   loss from ≥300 ms + gap to the gap.
3. **Make the log tell the truth.** Replace "near capture silenced by the platform" with the platform's
   answer; count `zero` (quiet) and `silenced` (lost) separately in the sync line; add a
   `DiagnosticDumps` key for the `rec start/stop/update` history so the next reporter log shows
   WhatsApp's source and every silenced event.
4. Tests: a pure `decide(zeroRun, silenced: Boolean?)` in the style of `VoipAnswerHold`, and the
   existing ledger tests extended for the new counter.

Test plan: one WhatsApp call on the OP9 (earpiece, then speaker) — expect `retake=0` and `silenced=0`
with `dumpsys` agreeing; listen for clipped word starts. Then the reporter's log from an rc: his
`silenced` count decides whether the S21 Ultra has the real fight too. Only if it does is point 2 the
part that matters.

## Dead end, recorded so nobody tries it

Marking our capture privacy-sensitive (`AudioRecord.Builder.setPrivacySensitive(true)`) to "win" the
mic: on a phone where WhatsApp captures with plain `MIC`, AOSP then silences **WhatsApp** for as long as
we record — the other side would stop hearing the user. Never.

## 🧪 Step 1 evidence — OP9, 2026-09-29 (2.4.4-rc16)

Built the recording-only half first (`ccbc0ebf`): each re-take now logs Android's own answer
(`activeRecordingConfiguration.isClientSilenced`) and the sync summary counts it. No behaviour change.

One answered WhatsApp call, 49 s, earpiece, normal talk with pauses:

`retake=31/3459ms(silenced=0 quiet=31 unknown=0)`

- **31 re-takes, Android said "not silenced" for every one.** On the OP9 every re-take is a pause, as predicted.
- **3.46 s of audio thrown away in 49 s (~7 %)**, ~100–120 ms per re-take, in chains every ~0.4 s through
  each pause (a short unanswered call: 1 re-take, also `silenced=false`).
- Step 2 (skip the re-take when Android says `false`) is now supported by evidence **for the OP9**. Still
  missing: a Samsung/One UI log — the phone the re-take was added for (v1.5.5) — to confirm Samsung reports
  real silencing as `true`. Until then step 2 stays unbuilt, per the maintainer.
