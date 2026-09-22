# 2026-09-20 — a transcript line shared by both speakers gets no label; split it where the speaker changes

Status: **✅ VERIFIED 2026-09-22** by the maintainer on a real ~15-minute Hebrew call on the OP12
(built-in mode, chunked into 4 passes): "i transcribed the last call and it looks good". Earlier ✅ on
2026-09-20 covered two scripted calls on the OP9. Built on `feat/split-lines-at-speaker-change`.
Still unmeasured: how often a real call trips a seam (no log survived — the OP12's USB dropped mid-run).

## The report

Maintainer, OP9, Shizuku mode, 2026-09-20: the 12:25 test call showed two labelled lines, then after the
transcription language was switched from English to Hebrew it showed **one line and no labels** — and
still did on a re-run at 14:45.

## What was measured

- The speaker data is fine: `Speaker channels read as SEPARATED; 25 turn(s)`. From the file: B talks
  4.9–8.7 s, silence 8.7–11 s, A talks 11–15 s.
- The VAD is fine: `VAD kept 2 speech stretches` — one per speaker.
- Whisper returns ONE segment for both: `Produced 1 segments`. Under `lang=en` the same audio gave 2.
  Reproduced on the desktop with the app's flags and `-l he`: one segment, 4.50 → 15.20 s.
- `SpeakerLabeller` rightly refuses a line both people share. So: no label, by design, on the only line.

This is the same weakness the mono/stereo discussion keeps running into — whisper hears one stream and
cuts on its own idea of a sentence, not on who is talking.

## What does not work

- **whisper's own length split** (`-ml 40 -sow`, `-ml 25 -sow`): cuts at 13.32 s and at 7.68 s — mid-way
  through a speaker, nowhere near the change at ~9–11 s. It splits by character count, not by speaker.
- **Decoding each speech stretch separately**: whisper pays for a 30 s encoder window per decode
  (measured on the OP9: 32 s of work for 16 s of audio), so N stretches cost ~N times as long.

## What does

Whisper's **token timestamps are accurate** — but on the VAD's *compressed* timeline, with the silence
removed. Same call, `-ojf`: the first speaker's tokens run 0.01 → 4.74 s, the second's 5.07 → 8.53 s, and
the seam between the two kept stretches sits exactly in that gap.

The app already receives the kept stretches in ORIGINAL time (`WhisperNative.vadSegment*`, built for
issue #25 / `SpeechGapSnap`). So:

1. Native: turn `token_timestamps` on and expose each segment's tokens (raw bytes + t0). It does not
   change the text — `whisper-cli -ojf` sets it and the transcript was identical.
2. Assign each word to the kept stretch its compressed time falls in (stretch k starts at the sum of the
   earlier stretches' lengths plus whisper's 100 ms bridge per seam).
3. **Cut a segment only at a seam between two stretches, and only when `SpeakerLabeller` names a
   different side for the stretch before and the stretch after.** Same speaker on both sides, or either
   side unlabelled → no cut. The pieces then go through the labeller as ordinary segments.

Deliberately NOT per-word speaker labelling: a seam is a pause the VAD already found (≥ 500 ms), which is
what a change of speaker in a real call looks like, and it needs no new threshold. Rapid exchanges with no
pause stay one shared, unlabelled line — exactly as today, so nothing gets worse.

## To settle it

Re-transcribe the 12:25 call on the OP9 in Hebrew: two lines, each labelled. Then a real conversation,
to see it does not shred lines where one person simply paused (same speaker both sides → no cut).

## Measured on the OP9, 2026-09-20 14:55 (🧪 one scripted call, by the assistant — not the maintainer)

Same 12:25 recording, Hebrew pinned, "Transcribe again":

```
VAD kept 2 speech stretches
Produced 1 segments across 1 pass(es)
Speaker channels read as SEPARATED; 25 turn(s)
Cut 1 segment(s) into 2 line(s) where the speaker changed
```

On screen: `0:04 פרוזה — בדיקה, בדיקה, בדיקה זה 1 plus 12,` and `0:11 You — בדיקה, בדיקה, זה 1 plus 19.`
Before the change the same run gave one unlabelled line.

How it is built: `whispercv.cpp` turns `token_timestamps` on and adds `segmentWords()` (words assembled
natively, because a token is bytes, not characters); `TranscriptionEngine.readSegments` — now the single
reader for both the buffer and the chunked path — offers a cut at every VAD seam via
`SpeakerSeamSplit.atSeams` (`TranscriptSegment.parts`, shifted by `ChunkPlan.stitch`); and
`TranscriptionRunner.labelled` settles them with `joinSameSpeaker` once it has the turns.

Not measured: a long real call; a chunked (multi-pass) call; built-in mode, where the turns come from the
live capture rather than the file; what `token_timestamps` costs in time (this run: 31 s for 16 s of
audio, the same as before it, but one run is not a measurement).

## One row per turn (added 2026-09-20 15:08) — ✅ VERIFIED 2026-09-20 on the OP9

✅ The maintainer confirmed the cut itself on the OP9 the same afternoon: "the labels are perfect and the
transcription looks good" (12:25 and 11:52 calls). He then pointed at the opposite problem on the 11:52
call: seven short sentences for two turns — the contact's name three times, "You" twice, and **the last two
rows with no name at all**. "It would have made much more sense that 0:04-0:08 is one line and 0:15-0:22
another."

`SpeakerTurnLines.merge` runs after the cut: rows are joined while no handover sits between them (an
unnamed row goes with the speaker before it), the pause between them is ≤ 1 s, and the joined row stays
≤ 30 s. No speaker data → untouched. The unnamed rows were the morning's voice-bleed problem on short
rows; joined into their turn they are named.

Measured on the OP9, same 11:52 recording: `Produced 7 segments` → `Laid 7 segment(s) out as 2 row(s),
one per turn`. The 1 s and 30 s numbers are judgement, not measurement — a real conversation is what will
show whether they are right.

✅ VERIFIED 2026-09-20 by the maintainer on the OP9, after re-opening the 11:52 and 12:25 calls: "yes it
looks perfect". That covers two scripted test calls in Shizuku mode.

✅ VERIFIED 2026-09-22 by the maintainer on the OP12: a real ~15-minute Hebrew call, built-in mode, chunked
(4 passes) — "it looks good". That closes the three cases the line above listed as open. What it does not
tell us is the numbers (how many seams were cut, how many rows came out of how many segments): the OP12's
transport dropped during the run and its logcat ring had rolled by the time it came back.

## 2026-09-22 — review finding on the row merge (🧪)

A line whose `startMs` is before the previous row's `endMs` was joined to it: `pauseMs <= 1 s` is true of a
negative pause. Within one decode whisper's lines never overlap, so that only happens at a chunk seam —
the next chunk re-transcribes the last 10 s of the one before (`ChunkPlan.OVERLAP_MS`) and the segment
straddling the seam starts inside them. Joined, the repeated seconds sat inside one row with nothing to show
where the repeat began. Fixed in `6a5e7e84`: a negative pause is an overlap and starts a new row, which is
what every chunked call showed before rows existed. The maintainer's 15-minute call read well WITH the
join, so at most three rows change on a call that length; a re-read after the fix would settle it but is not
owed. The underlying repeat itself (a straddling segment kept whole, on purpose, since chunking shipped)
is unchanged.
