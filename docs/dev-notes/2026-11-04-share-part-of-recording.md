# Share a part of a recording — research

📐 RESEARCHED 2026-11-04, nothing built. Requested by the maintainer 2026-11-04. Design DECIDED the same day (below).

## What the user gets

Pick a start and an end inside a recording, listen to the selection, and share it through the Android share sheet
as a new file. The original is never changed.

## What other apps do (sources in the research hand-back, summarised)

| App | How the range is picked | What is shared |
|---|---|---|
| Google Recorder (Pixel) | Edit ▸ select audio **or highlight transcript text** ▸ Crop / Remove ▸ "Save copy" | Separate menu: audio, transcript .txt, or a captioned video clip (≤5 min) |
| Samsung Voice Recorder | Trim icon ▸ two handles on the waveform ▸ preview ▸ "Save as a new file" / "Trim original" | The saved file |
| Apple Voice Memos | Yellow handles on the waveform, pinch to zoom | Trimmed memo |
| Telegram (11.12) | Drag start/end of a paused voice message before sending | The message |
| Gong (calls) | Scissors ▸ bars on the timeline; the transcript follows the trim | The call |
| Cube ACR, BCR, Fossify | No trimming (Fossify: open request #9) | — |

No call recorder we checked lets you share a clip. Google's "select transcript text" is the pattern that fits
CallVault best, because almost every recording here has timestamped transcript segments.

## How it would work in code

**The cutter already exists.** `data/merge/AudioSplit.kt` (un-merge) copies encoded frames from a `MediaExtractor`
into a `MediaMuxer` without re-encoding: Opus → Ogg (`MUXER_OUTPUT_OGG`, API 29+), AAC → MP4. Lossless, keeps both
stereo channels (ch0 = you, ch1 = the other side), fast enough for an hour-long call.

What a clip adds:

1. **Time → frames.** Opus frames are 20 ms, AAC ~21 ms; every frame is a sync frame, so a range becomes
   `frameStart`/`frameCount` by sample time. A new `AudioSplit.clip(input, startMs, endMs, output)`.
2. **Opus pre-roll.** A decoder starting mid-stream needs ~80 ms to settle (RFC 7845 §4.6); starting exactly on the
   cut gives a click. Start 4 frames early (80 ms extra lead-in). Trimming that exactly via the Ogg pre-skip may not
   be possible through MediaMuxer (it takes pre-skip from csd); untested.
3. **End-of-stream page.** AOSP's Ogg muxer never sets the EOS flag on the last page; some players (Firefox) reject
   such files. BCR fixes the flag and the page CRC after `stop()` (`OggContainer.kt`). ⚠️ Confirmed 2026-11-04 on
   the OP9: our ordinary recordings already end without the EOS flag (last page flags 0x0), so this is a fix for all
   our Ogg output, not only clips.
4. **Where the clip goes.** `cacheDir/clips/`, served through the existing FileProvider, deleted on the next app
   start or after a day — not into the recordings folder or Drive, so it never appears in the library.
5. **Name.** `<recording name> (01-12 – 02-40).ogg` (times inside the call).
6. **Sharing.** `ACTION_SEND` with the clip as `EXTRA_STREAM`; optionally the transcript lines in the range, with
   their times, in `EXTRA_TEXT`. Same `RecordingShare` helpers.
7. **Drive-only recordings** read through their SAF uri like playback does. **Imported MP3/WAV/FLAC**: MediaMuxer
   cannot write them; first version hides the option for those (or re-encodes to Opus later).
8. **Transcript selection** maps lines to `[first.startMs, last.endMs]` from the stored segments.

Size estimate (📐): the cutter + EOS fix + clip file handling ~1 day; the range picker UI 1–2 days depending on the
option; 15 locales for ~8 strings.

## UI options (see the options page)

- **A. Two handles on the waveform** (Samsung/Apple), in a "Share a part" sheet opened from the playback screen,
  with "start here / end here" buttons that use the playing position, and snapping to marked moments.
- **B. Select transcript lines** (Google Recorder): long-press a line, extend the selection, "Share as audio".
- **C. Mark while listening**: two buttons on the player, "Clip starts here" / "Clip ends here", then Share.

**Maintainer 2026-11-04: A looks good** (entry points shown on the options page, awaiting confirmation):
- Recordings list ▸ row ⋮ menu ▸ new "Share a part" under "Share" → screen A with the whole call selected.
- Playback screen ▸ new Share button beside Delete on the call card ▸ "Share recording" / "Share a part" → screen A
  with 30 s selected from the playing position. (The playback screen has no Share button today.)

## Decided — maintainer 2026-11-04

1. **Picker: option A**, reached both ways (list row ⋮ ▸ "Share a part"; playback screen ▸ new Share button ▸
   "Share a part").
2. **Transcript is offered with the clip as an option** (a switch on the share step). It always carries **only the
   clipped part's lines**, never the whole transcript.
   - Call already transcribed: the lines inside the range, with times, go in the share text.
   - Call not transcribed: the switch offers **"Transcribe just this part"** or **"Transcribe the whole call"**.
     - Just this part: transcribe the clip file itself (it already exists for sharing), with the user's current
       engine (on-device or their own cloud key); times offset to the call. Not saved as the call's transcript
       (📐 assumption — confirm when building).
     - Whole call: the normal transcription of the recording (saved as usual); when done, its lines inside the range
       are shared.
     - Either way the share waits with progress on the share step; Cancel shares without text.
3. **A clip is never saved as a recording.** Share-only: no library row, no Drive copy, temporary file deleted later.

## Questions answered (kept for history)

- Which option, or A + B together?
- Include the transcript text with the clip — always, never, or a switch in the sheet?
- Allow saving the clip as a new recording in the library, or share-only? (Share-only is simpler and keeps Drive
  and the catalog untouched.)
