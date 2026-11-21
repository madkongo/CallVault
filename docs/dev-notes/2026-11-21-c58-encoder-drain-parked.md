# C58: the encoder's end-of-call flush has no deadline — ⏸️ PARKED 2026-11-21 (owner's decision)

**Status:** not fixed in 2.5.2, on purpose. A fix exists on a branch (`21ebf96f`, "the encoder end-of-stream
flush has a deadline on every capture path"), held back because it touches the last step of every recording
and cannot be tested on our phones (no way to freeze a real encoder). Owner, 2026-11-21: "park this and
document so if we get a feedback it correlates".

## What can go wrong

When a call ends, the recorder tells the audio encoder "end of stream" and waits for it to hand back the last
packets before closing the file. That wait has no time limit:

- `server/VoipCaptureSession.kt` (~424–426, ~626): VoIP drain loop.
- `handoff/HandoffEncoder.kt` (~132): handoff feed loop.
- `cpp/audiohandoff.cpp` (~276, ~357–361): the native final flush sleeps 2 ms and retries with no deadline,
  and every ring read is appended to `stage` with no cap while the encoder is stuck
  (📐 CALCULATED ~690 MB/hour of native memory at 48 kHz stereo, in the app process).

If the phone's encoder stalls at that moment, the loop never ends.

## What a user report would look like (match against these)

- A recording that **never finishes saving**: the "recording" notification stays after the call, or the file
  shows up much later or not at all.
- An **unplayable or zero-length file** (AAC/M4A especially: published without its trailer), while earlier
  calls on the same phone are fine.
- The **app growing in memory / being killed** shortly after a call ends; the next call is then missed.
- More likely on **Samsung** (BCR documents `AudioRecord.read` blocking forever at call end on Samsung and reads
  with `READ_NON_BLOCKING`; `RecorderThread.kt:724–730` in BCR).

In the always-on log: a `stopRecording requested` line for the call with **no** following
`Published recording` / `app call outcome` line, or a `capture stopped` line missing after `stopRecording`.

## When it comes up

Take `21ebf96f` (bound the drain to ~1–2 s, retry EOS a few times, always fall through to codec stop +
mux stop; cap the native `stage` buffer at ~30 s and give the native flush a deadline), test with a
debug-injected stalled codec on each path, and get the reporter to confirm. Tracker item: C58.
