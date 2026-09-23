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

## The reporter's log — 2026-09-22 20:33, Galaxy S21 Ultra (SM-G998U1), Android 15, WhatsApp Business 2.26.36.72

Two-minute outgoing call, earpiece at the start, **speaker from 5 s**, `ts=hal` on both sides.

```
t=10s  offset=+761ms   near{read=496  zero=336  retake=18/1191ms}  far{q=38}
t=60s  offset=+3422ms  near{read=2996 zero=1698 retake=89/4304ms}  far{q=172}
t=120s offset=+5922ms  near{read=5993 zero=3094 retake=155/7215ms} far{q=320}
summary file=120.7s wall=127.2s offset first=+26ms last=+6418ms  near{sub=7 drop=0} far{sub=0 drop=0}
```

1. **The drift is linear and huge: +6.4 s over two minutes**, ~50 ms per second of call. No drops, seven
   stand-ins: it is the by-arrival pairing consuming the far queue at the near side's pace while the
   near side keeps stalling. The far backlog (q=320 = 6.4 s) is the same number seen from the queue.
2. **155 mic re-takes in 120 s, 310 with the silencing lines, 14.4 s of re-take gaps, and 3094 of 6028
   near chunks all-zero** — half of the reporter's own voice is digital silence. The re-take and
   WhatsApp's own restart ping-pong: silenced → we re-take (~45 ms) → ~300 ms later silenced again.
   The July measurement (10 in 50 s on an S24 FE) was the mild form of this.
3. **Sign.** Measured: the far tap lands LATE (+). Reported: the far party sounds EARLY. Speakerphone
   from 5 s explains it: the mic hears the far party acoustically, in step with the reporter's own
   words, and the tap's copy — full-scale, `peak far=32768`, i.e. clipping — trails by the drift. He
   hears them once in time and once late, and calls the loud late copy "them". Same drift, same fix.
4. Route: earpiece → speaker at 5 s; mixer output thread on the speaker with ave write latency 131 ms
   (the "Start latency" numbers were eaten by the log redactor — they look like phone numbers).

**What 2.4.2 fixes:** the pairing (below). **What it does not:** the re-take storm and the half-silent
near side, which the reporter had in 2.4.0 too and did not report; scoped separately.

## The fix (step 2, 2.4.2)

Pair by content time instead of arrival: walk the file in 20 ms slots of real time, take each side's
chunk for the slot, pad a gap with silence and discard surplus, so start latency, stalls, re-takes and
clock drift are all one case and the two sides cannot drift. Then correct a constant tap-to-ear offset
by delaying the far side by the mixer latency on the active route. Unit-testable with synthetic streams.
