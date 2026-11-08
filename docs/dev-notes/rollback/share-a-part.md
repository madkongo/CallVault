# Rollback: Share a part of a recording

🧪 VERIFYING — built 2026-11-04 on `feat/share-part`, merged into integration/next. 2457 unit tests pass. Run on the
OP9 (2.5.0-clip6, Shizuku mode, cloud transcription = Groq). **Not yet tried by the maintainer.** To settle: on the
OP12, share a part of a real call from the list and from the playback screen, with and without the transcript, and
open the received file in WhatsApp/Telegram.

Design and research: `docs/dev-notes/2026-11-04-share-part-of-recording.md` (option A, decided 2026-11-04).

## What it does

- List row ⋮ ▸ **Share a part** (whole call selected) and a new **Share** button on the playback screen ▸
  Share recording / Share a part (30 s from the playing position).
- Waveform with two handles (drag the nearer one, or tap), snapping to marked moments; Start here / End here; preview
  plays the part and stops at its end. Calls over 3 minutes zoom to the part with a strip of the whole call.
- "Include what was said": only the lines inside the part. Untranscribed: "Transcribe just this part" (the clip file,
  user's engine, not stored) or "Transcribe the whole call" (the normal run, saved), then only the part's lines.
- The clip: lossless frame copy (Opus → Ogg, AAC → MP4), 80 ms lead-in, Ogg end-of-stream flag set, named after the
  contact, in `cache/clips/<time>/`, deleted after a day. Never a recording, never Drive.

## Measured on the OP9 (2026-11-04)

- 8.65 s part of a 16 s call → 8.74 s clip (80–100 ms lead-in), decodes cleanly; "just this part" via Groq 0.5 s.
- 30 s part of a 57-minute call → first 24.5 s (Android's Ogg seek landed 5.5 s late) → fixed by stepping back →
  30.08 s. End-of-stream flag present (0x4).
- Whole call path: queued, transcribed (Groq), 101 characters shared. Existing transcript path: 405–446 characters.
- Preview stopped at the end handle. Long-call zoom: handles apart, drag 23:31 → 23:48 worked.

## Not tested

- On-device (whisper) transcription of a part — the OP9 is set to Groq. Busy path (a queued call transcription
  running) not exercised.
- An AAC (.m4a) recording; a Drive-only recording (non-seekable source → copied first); an imported MP3 (should show
  "can't share a part" message).
- TalkBack on the handles (custom actions "one second earlier/later").
- The extra Share icon leaves less room for the name on the playback card ("2026…" on a long imported name).

## How to roll back

```
git revert -m 1 632d13ba
```

Commits on the branch: `d34cc820` (engine choice moved out of the worker — keep if reverting only the feature),
`acb713b4` (clips engine), `ad11512c` (UI), `6edd603f` (14 languages), then the review and OP9 fixes.
