# The note field lost letters — 2026-09-22

✅ VERIFIED 2026-09-23 by the maintainer on the OP12 ("tested the note and its fine"). Reproduced on the
emulator (2.4.1 debug build) the evening before, fixed, measured there first.

## The report

A user on 2.4.1: "you cannot save a note for a call". The maintainer: "it does save but its just not
user friendly — I enter text, close the keyboard and see the text; go back to the main page, come back
to the recording and I see the note."

## What was actually happening

`PlaybackScreen`'s `NoteCard` bound `OutlinedTextField(value = note)` to the Room flow of the stored
note and saved on every keystroke. Each letter therefore went app → Room → invalidation → flow → field
before it was shown. Typed over adb (`input text "hello note"`, fast), the field and the database both
read **"o otehe"**: the IME's composing region was reset under it and letters were dropped or swapped.
A slower typist saw fewer of them; nobody saw a "Saved". The transcript page's dialog had a local
draft already, with a comment naming this exact round-trip — the playback card was the door that did
not.

Not new in 2.4.1: the binding dates from 2.2.0 (`2429213d`). A second, older problem sits under it: on a
phone that has never transcribed anything, `RecordingExtrasRepository.note()` returns a constant `""`
until the database exists, so the first note ever typed could not appear at all — the same fix covers
it, because the field no longer shows the flow's value.

## The fix (`fix/note-draft`, `6d363b91`)

`ui/common/NoteDraft` + `rememberNoteDraft(stored, key, onSave)`: the field shows the draft; a save
runs 600 ms after the last keystroke and at once when the field leaves the screen (or the recording
changes under it); a value that changes in the store while nothing is being typed — a merge — replaces
the draft. `NoteCard` shows "Saving…" / "Saved" beside its title. The transcript dialog uses the same
draft. Four `NoteDraftTest`s on virtual time (`kotlinx-coroutines-test` added).

Measured after the fix, same adb typing: field "hello note from the test", "Saved" shown, DB row
identical.
