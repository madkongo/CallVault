# 2026-09-20 — a transcript line shared by both speakers gets no label; split it where the speaker changes

Status: **🧪 VERIFYING** — design measured on the desktop, implementation in progress on
`feat/split-lines-at-speaker-change`. Nothing here is confirmed on a phone yet.

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
