# App-call sync instrumentation for issue #41 — what one log now says

✅ VERIFIED 2026-09-22 that the lines are produced and reach both logs (two WhatsApp calls on the OP9);
🧪 whether they explain the reporter's Galaxy is what the reporter's log will show. Step 1 of the plan in
`2026-09-22-three-requests-feasibility.md`; the fix (pairing on content time) is step 2 and is NOT built.

## The problem

Issue #41 (Samsung S21 Ultra, Android 15, 2.4.0): in WhatsApp recordings the far party is heard a beat
**before** the user's own replies. The file is mono, so nothing can be measured from it afterwards, and
we have no Samsung. `VoipCaptureSession` pairs the mic and the far-party sink **by arrival** — one chunk
from each queue, a 20 ms silence stand-in when a side has nothing within 120 ms, a chunk silently dropped
when a side's queue is full — and nothing ever re-aligns them.

## What is logged now (branch `feat/voip-sync-instrumentation`)

Host side (`VoipSyncLedger`, in the daemon's ring — **debug logging must be on before the call**):

- `VoIP sync start:` both records' buffer sizes, chunk/wait/queue constants, SDK, make, model.
- `VoIP sync: first near|far chunk Nms after start` — the two sides' start latency.
- `VoIP sync: re-take #n at Tms took Nms; Z zero chunks so far` — every mic re-take.
- Every 10 s of file: `VoIP sync t=… wall=… offset=±Nms near{read sub stall drop q zero retake ts} far{…} peak near= far=`.
- At the end: `VoIP sync summary file= wall= offset first= last= min= max=` plus both sides' totals.

App side (`VoipRecordingCoordinator`, in the app's log):

- `App-call route at start:` / `at 5s:` — audio mode, communication device, speakerphone, SCO, A2DP, the
  output devices, and the calling app's version.
- `App-call output threads:` — `dumpsys media.audio_flinger` filtered to the output threads, their
  devices and latencies (new fixed key `audio_latency` in `DiagnosticDumps`).
- `App-call capture health:` — the host's summary line, pulled through `captureDiagnostics()` like the
  carrier path, so the report has it even if the ring was not collecting.

**The offset.** Each chunk carries the real time its audio was captured: the HAL's `getTimestamp` fix
applied to the chunk's frame index (`ts=hal`), else the read moment (`ts=read`). For every pair written
the ledger takes near minus far. **Positive = the far audio sits later in the file than it happened (far
party late); negative = far party early, the reported symptom.** The sign was got wrong in the first
draft and corrected by working the arithmetic through (see the class header).

## Measured on the OP9 (Android 14, OnePlus, earpiece, WhatsApp 2.26.36.74), two calls

```
first far chunk 161ms after start / first near chunk 230ms
re-take #1 at 2324ms took 98ms … re-take #5 at 8930ms took 128ms   (five in the first 9 s)
t=10.0s wall=13.5s offset=+548ms near{read=636 sub=4 stall=1 drop=0 q=0 zero=168 retake=5/546ms ts=hal} far{read=666 sub=0 stall=0 drop=0 q=26 ts=hal}
summary file=19.5s wall=23.1s offset first=+96ms last=+650ms min=+95ms max=+650ms
route: mode=3 commDevice=earpiece speakerphone=false sco=false a2dp=false; mixer latency=21.00 ms
```

Three findings we did not have this morning:

1. **The mic re-take is not a Samsung-only event.** Five re-takes and 168 all-zero chunks (3.4 s of
   digitally silenced mic) in a 20 s call on a OnePlus. WhatsApp restarts its own capture roughly every
   400 ms while the call rings.
2. **Each re-take pushes the far party later**, exactly as the arrival pairing predicts: the near side
   loses ~100 ms per re-take, the far queue backs up (q=26 = 520 ms), and the offset grows +96 → +650 ms.
   On our phones the far party is **late**, growing through the call.
3. **The reporter hears the opposite sign.** So on the Galaxy something else dominates. The two
   candidates the log now tells apart: far-side loss (`far{sub drop}` — never counted before), or output
   latency between the far tap and the ear (the route lines: a Bluetooth route adds 100–300 ms the
   sink never sees, so his replies land that much after the far audio in the file).

📐 Not measured: whether +650 ms is audible to the maintainer on the OP9 (the far party late by half a
second at the end of a 20 s call should be); a long call's drift; a Bluetooth route here.

## What the reporter has to do (drafted for #41)

1. Update to 2.4.1.
2. Settings → Debug → turn **Debug logs** on. Before the call, not after.
3. Make one WhatsApp voice call of at least a minute in which both people talk — ask a question, get an
   answer. Say which output was used: earpiece, speaker, wired, or Bluetooth (and which earbuds).
4. Listen to the recording and say two things: is the far party early from the very first exchange, or
   does it get worse as the call goes on; and roughly by how much.
5. Settings → Debug → **Share** the log.

If the `VoIP sync` lines are missing, debug logs were not on when the call happened; the summary line
alone (`App-call capture health`) is still worth having.

## Reading the log when it comes

- `offset first` negative and roughly constant → start-order/latency: the far tap sits ahead of the
  ear. Check the route lines and the mixer latency; a Bluetooth route explains 100–300 ms by itself.
- `offset` negative and growing with `far{sub>0}` or `far{drop>0}` → the far side is losing time.
- `offset` positive (as here) → then his "early" is the ear, not the file: output latency.
- `ts=read` on either side → that phone gives no HAL timestamp; the offset is then read-time based and
  worth less; the counters still hold.

## The fix, when the log has spoken (step 2, not built)

Pair by content time instead of arrival: walk the file in 20 ms slots of real time, take each side's
chunk for the slot, pad a gap with silence and discard surplus, so start latency, stalls, re-takes and
clock drift are all one case and the two sides cannot drift. Then correct a constant tap-to-ear offset
by delaying the far side by the mixer latency on the active route. Unit-testable with synthetic streams.
