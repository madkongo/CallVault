# Three requests, researched before any code — 2026-09-22

📐 CALCULATED throughout: everything here comes from reading the code, the notes and the two issues.
Nothing below was measured on a phone today except one thing, marked as such. No code was written.

Asked by the maintainer: for each, is it possible, and how hard.

| # | Request | Possible? | Effort | Why |
|---|---------|-----------|--------|-----|
| 1 | Issue #38 — identify callers on Shizuku recordings | **Already done**, unreleased | **Easy** | On `main` since `9b825b6d`, ✅ verified by the maintainer on the OP9 2026-09-20. Ships with 2.4.1. One gap left (below). |
| 2 | Issue #41 — VoIP far party ahead of the near party | **Yes**, cause in our own code | **Medium–hard** | The mux pairs the two captures by arrival with silence stand-ins; every mic stall shifts the far side earlier for the rest of the call. Fix is contained; proving it needs a Samsung. |
| 3 | Record only once the other party answers | **Carrier calls: yes. App calls: no reliable signal** | **Medium** | The daemon can read `mForegroundCallState` from `dumpsys telephony.registry` (DIALING/ALERTING → ACTIVE). Nothing equivalent exists for WhatsApp & co. |

## 1. Issue #38 — Shizuku speaker labels

**State:** implemented and merged (`114b5644` … `8d79713e`, merge `9b825b6d`; row shaping `55d91fea`),
✅ VERIFIED 2026-09-20 by the maintainer on the OP9 in Shizuku mode ("the labels are perfect"), and
in the `[2.4.1] — unreleased` section of the CHANGELOG. The latest tag is still `v2.4.0`, so the
reporter has not seen it.

**The gap the reporter would notice:** the labels come from the file (`OfflineSpeakerLabeller` during the
transcription decode), but *which* side is the user is a per-device fact the app learns only from live
built-in-mode outgoing calls (`SpeakerTurnsRepository.collectAfterCall` → `FirstSpeakerHeuristic`).
`storeFromRecording` (`SpeakerTurnsRepository.kt:131-155`) stores `outgoing = false, observedMap = UNKNOWN`
on purpose, so a phone that has only ever recorded in Shizuku mode shows **Speaker A / Speaker B**,
never "You" and the contact's name — unless the user sets the mapping by hand (the override in
`HomeScreen.kt`, which already exists).

**Closing it is easy:** the recording knows its direction (`_out_` / `_in_` in the file name,
`RecordingItem.direction`), so `storeFromRecording` can take `outgoing` and store
`FirstSpeakerHeuristic.observe(turns)` exactly as the live path does; `trustedMap` then corroborates
across calls as today. One parameter, one test, no new UI. Risk to weigh: if scrcpy's channel order
differed from the direct VOICE_CALL order on some OEM, mixed observations would decay the trusted map
to UNKNOWN — which is the designed safe answer, not a wrong label.

Still 🧪 from the earlier work: real double-talk, a second Shizuku phone, whether the channel bleed
seen on the OP9 was acoustic.

## 2. Issue #41 — far party earlier than the near party on WhatsApp (Samsung S21 Ultra, Android 15, 2.4.0)

The reporter hears the answer before the question. Carrier calls are fine. Resilient recording is on.

**How the VoIP file is built** (`server/VoipCaptureSession.kt`): two free-running `AudioRecord`s at
48 kHz — far = the `AudioPolicy` loopback sink, near = plain `MIC` — each with its own feeder thread
filling a 400-chunk queue of 20 ms chunks. The mux loop (`:262-263`) polls near for up to 120 ms, else
takes a silence chunk; then polls far for up to 120 ms, else silence; interleaves L=near/R=far, downmixes
to mono, encodes. The output position is the pair index; **no timestamps, no start alignment, no drift
correction** anywhere. The file is mono, so nothing can be re-aligned afterwards.

**Where the shift comes from — and why its sign matches the report:**

1. *Silence stand-ins are lossy.* A side that stalls for 120 ms gets ONE 20 ms silence chunk, then the loop
   moves on. A stalled side loses ~5/6 of every stall from its timeline, so its later audio lands earlier
   in the file. ~~The near side is the one that stalls (below), so far audio ends up earlier relative to
   near — the reported symptom.~~ **❌ WRONG, measured 2026-09-22 14:19 on the OP9:** a near stall makes
   the near audio land earlier, i.e. the FAR party LATER (+96 → +650 ms over five re-takes). The
   reporter's far-EARLY is the other sign; see `2026-09-22-voip-sync-instrumentation.md`.
2. *The near side stalls constantly on One UI.* `2026-07-30-voip-near-party-silenced-on-one-ui.md`: Samsung
   arbitrates the MIC to one client and CallVault re-takes it (`retakeMic`, `:362-378, 394-414`) — **10
   re-takes in a 50 s call** on the reference Galaxy. Each re-take is a window where near produces nothing
   and far keeps streaming. Only the near side has such a mechanism; the far sink is outside the mic
   arbitration.
3. *Queue overflow drops silently.* `q.offer(buf.copyOf())` (`:379`) discards a chunk when a side's queue is
   full — no counter, no log. Two HAL clocks that differ slightly fill one queue over a long call and then
   drop steadily: a slow linear drift on top of the jumps.
4. *Fixed offset.* Far is tapped before the output path, near after the input path; the two records are
   started one after the other (`:132-133`) and again on every resilient-recording resume (`:216-230`).
   Tens to a few hundred ms, constant, in the same direction.

The "sync needs no correction" claim in the class doc (`:46-49`) rests on one 40 s ColorOS call with zero
substitutions (`capture-research-directions.md:346-348, 447`); it was never measured on a Galaxy or on a
long call. The reporter's phone is exactly the case it did not cover. **The claim is wrong for One UI and
the doc should say so.**

**The fix, in outline:** pair by *frames*, not by arrival — on a stall, insert as much silence as the
stall lasted (frame count from the other side, or `AudioRecord.getTimestamp`), so both timelines keep the
same length; count far-side substitutions and dropped offers and log them at the end next to
`substituted`; align the two starts once (discard the head of whichever side started first) and repeat
that on every resume. Roughly one file plus tests.

**Why medium–hard rather than medium:** we cannot reproduce it. Neither OP phone silences the mic, so the
re-take path never fires here and the shift stays at the fixed-offset level (the 2026-09-12 probe found
VoIP calls "in sync" on the OP12). The unit test can prove the pairing arithmetic; only the reporter's
Galaxy can prove the call. Per [[reproduce-before-shipping-to-a-user]], the plan is: instrument first
(counts + magnitude in the log), ask the reporter for one call's log with debug logs on, fix, ship to the
reporter as a test build. The reporter's log will also say whether re-takes are the cause or only the
fixed offset is (which would point at (4), not (1)-(2)).

## 3. "Start recording only when the other party answers"

**Today** an outgoing carrier recording starts at `CALL_STATE_OFFHOOK` (`CallSessionManager.handlePhoneState`
→ `evaluateAndStartService`, `:273-328`), which fires at *dial*. The dialling and ringing phase is in the
file — on the reference phone as silence, because the ringback never reaches `VOICE_CALL`
(`2026-08-17-transcription-device-test-plan.md:31-35`); on other OEMs possibly as audible ringback.

**The signal.** Android's precise call state (`PreciseCallState`: DIALING=3, ALERTING=4, ACTIVE=1) is
behind `READ_PRECISE_PHONE_STATE`, which is `signature|privileged` — not grantable by `pm grant`, no
app-op, so the app cannot register a listener. But the recorder host runs as shell (uid 2000) on both
paths and already spawns `dumpsys` (`VoipCallerName.kt:149`, `VoipAppIdentity.kt:216`), and
`dumpsys telephony.registry` prints `mForegroundCallState=<n>` per phone.
**Measured today:** the field is present and readable from shell on the OP12 (Android 16), the OP9
(Android 14) and the emulator (all `0` while idle). The transition 3/4 → 1 on a real outgoing call is
📐 expected from the AOSP source, not yet watched.

**Shape of the feature** (carrier only): a preference "Record from the moment they answer"; on OFFHOOK
with `direction == OUTGOING` and the preference on, send `ACTION_STANDBY` (which already warms the daemon,
`RecordingForegroundService.kt:343-352`) and have the host poll `mForegroundCallState` every ~500 ms until
ACTIVE (or IDLE = never answered → nothing recorded), then `ACTION_START_RECORDING`. Safe because:

- the file name and clock come from the pipeline start (`AudioRecordingEngine.startPipeline`,
  `RecordingFileNameFormatter.kt:64`), not from OFFHOOK — a late start stamps the answer moment;
- resilient recording / handoff is set up per session inside `startNewRecordingSession`, unaffected;
- `wasRecordingServiceStartIntentSend` (`CallSessionManager.kt:124, 300-303, 327`) must not swallow the
  deferred start.

**Not** as start-then-pause: `AudioRecordingEngine.isPaused` is a no-op in daemon mode (the daemon writes
straight to the fd), so delaying the start is the only real option on the carrier path.

**Cheap alternative the maintainer may prefer:** record from OFFHOOK as now and *trim the head* at the
ACTIVE moment — no risk of missing the first words to a slow daemon start, and the polling result is only a
cut point. Costs a re-encode or a container edit; the answer moment can also just be stored as metadata
and used by playback/transcription. Worth deciding before building.

**App calls:** no answer signal exists. The audio mode flips at call setup, not at answer
(`VoipCallDetector`), and the far sink is silent until the app plays the remote voice; the only hint is the
calling app's notification text changing from "Ringing…" to a duration, which is per-app, per-locale and
not something to build on. If this ships, it ships as a carrier-call option and the setting says so.

**Effort:** medium. A poll loop in the host (or a `DiagnosticDumps` key), a preference, the deferred start
in `CallSessionManager`, tests for the state machine, then one outgoing call on the OP12 to watch
`mForegroundCallState` change — the emulator can also do this (`gsm` console accepts an outgoing call).

**2026-09-22 afternoon:** request 3 built and ✅ (phone and app calls, `docs/dev-notes/2026-09-22-record-from-answer.md`);
#41 step 1 built, `2026-09-22-voip-sync-instrumentation.md` — and its first two calls on the OP9 disproved
this note's guess that the re-take mechanism explains the reporter: re-takes push the far party LATE
(+650 ms here), the reporter hears it EARLY. Output latency to the ear is the new lead; the log now
carries the route.

## Ranking, easiest first

1. **#38** — reply and ship 2.4.1; optionally the one-parameter fix so Shizuku-only phones get names.
2. **Record on answer** — medium; carrier calls only; decide start-late vs trim-head first.
3. **#41** — medium–hard; cause is in our pairing loop, fix is contained, verification is on a phone we
   do not have.
