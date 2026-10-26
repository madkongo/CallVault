# Play from the Recordings list — 🧪 VERIFYING

Requested by "shine n rise": listen through a run of calls without opening each one. Design: option C
("play in place, drag to seek"), chosen by the maintainer on 2026-10-26 from the canvas
https://claude.ai/artifact/NUiGzMV8e8Q5CseKYuMg1z

## Revision 2026-10-26 (maintainer, after trying it on the OP9)

"I don't want the card to change, just add the bars." The playing card now looks exactly like any other:
no teal outline, no colour change, no progress ring, details line unchanged (only a file that fails to
play replaces it with the error). The only addition is a row under it: elapsed · seek bar · length
(maintainer chose "small times beside the bar"). The play button still turns into pause. The
descriptions below of the ring, outline and "1:12 of 4:23" line are the FIRST version and no longer
apply; "1:12 of 4:23" survives only as the bar's screen-reader state.

## What it does (first version)

- The play button on a row plays that recording **in the row**. A second tap pauses, a third resumes.
  While it plays, the button shows pause with a progress ring, the details line reads "1:12 of 4:23",
  the card gets a teal outline, and a seek bar appears along the card's bottom.
- Seek bar: only on the playing/paused row. 4dp track, but a 44dp touch strip that overhangs the card
  padding (the card grows ~18dp, not 44). Tap = jump there. Drag = the dot grows with a halo, a time
  bubble shows where it will land, the ring and the time line follow; the audio keeps playing and jumps
  on release (the shared `SeekBar` seeks on every move, which is choppy — that is why it is a new
  component, `ListSeekBar`).
- Tapping the rest of the row still opens the page **without** playing (privacy rule unchanged). If that
  row is playing, the page carries on from the same spot; if another row is playing, it stops first.
- Leaving the list (Back, another section, a transcript) stops playback — except leaving for the
  playing recording's own page. Closing the page still stops, as before.
- Right-to-left (Arabic, Persian): the bar fills from the right; touches are mirrored.

Rules live in `ui/common/ListPlayback.kt` (unit-tested, `ListPlaybackTest`, 10 tests).

## Not changed

- Skip ±10 s and speed stay on the page only.
- `INLINE_PLAYER_ENABLED` (the old in-row strip) stays off; untouched.
- No setting, so no onboarding step (decision: none needed — nothing to choose).

## New strings (15 locales)

`home_player_position_of` ("%1$s of %2$s") and `home_player_seek` ("Playback position", screen reader
only). Hungarian, Vietnamese and Chinese use "1:12 / 4:23" rather than a word for "of".

## Evidence (emulator, mymemory-test, 2026-10-26)

- Play in row → 0:02 of 4:22 with ring + bar. Drag to ~68% → bubble showed 2:20 mid-drag, landed 3:00.
- Tap at 20% → 0:54. Pause held 0:55 for 2 s; resume continued.
- Card tap while playing → page at 1:02, still playing. Back → no MediaPlayer started (dumpsys audio).
- Play row 1 then row 2 → only row 2 plays. Open another row's page while one plays → stops.
  Play then Back out of the list → stops.
- Arabic: bar fills right-to-left; tap at 20% from the right → 0:55.

## To settle (maintainer)

On a phone: play two or three recordings from the list, drag the bar on a long one, open the page while
it plays, and press Back. Say whether the drag feels right and whether the list scrolls cleanly while
your thumb is near the bar.
