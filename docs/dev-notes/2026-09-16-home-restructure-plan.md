# Home restructure + audio import — plan

Status: 📐 PLAN, awaiting the maintainer's go. No code written. Rollback point taken first: tag
`pre-ui-restructure-2026-09-16`, branch `backup/pre-ui-restructure-2026-09-16`, a source zip and the
currently-installed APK on the Desktop.

## What the maintainer asked for

- Home becomes a **hub of cards** — Recordings / Transcripts / Summaries — and **reopens the last section**
  you were in (cards on a fresh start, back from a section returns to the cards).
- **Transcripts** is its own page, with **import audio** on it (not on the hub) and room for transcription
  tools.
- A **transcript row opens a reading view**: the transcript plus its summary, tap a line to play from there.
- Goals given: transcribe non-call audio; find what has a transcript or summary; somewhere for transcription
  tools; a calmer home.

Import is already GitHub issue #37 (mirror176, 2026-09-10), where it is also noted that a file copied into
the recordings folder does not appear in the list.

## What the two sweeps found (the constraints this plan is built on)

**Navigation is hand-rolled.** No NavHost: one enum and a `when`, plus a single nullable "manual screen".
Settings is a drawer over Home, not a destination. Back is handled in four places. There are **no Compose UI
tests and no navigation tests at all**, so nothing will catch a regression here except us.

**Three silent-regression risks, in order:**

1. **`HomeViewModel.refresh()` is not a list reload.** It also recomputes the status card, runs the
   setup-health sweep and **silently re-grants WRITE_SECURE_SETTINGS** — and it is hooked to ON_RESUME from
   inside the recordings screen. If that screen stops being what you return to, the heal stops running and
   the status card goes stale while still reading READY. An install-over dropping that grant already cost a
   real 13-minute call. **This moves to the app shell before anything is split.**
2. **Notifications have no destination.** "Recording is broken", "update available", the pairing and debug
   notifications all just open the app and rely on Home being the status card. With "reopen where you were",
   they can land on a transcript list with no sign of the problem. **The status card goes on the hub, and
   those notifications gain a route.**
3. **State restoration.** Scroll position, the open recording, filters, selection and ~15 saved fields are
   carefully preserved today, with comments recording the bugs that produced them (issue #27: rotation
   orphaned playing audio). A naive section switch reintroduces all of it, silently.

**Import is a better fit than expected.** The catalog deliberately stores no direction/number columns
("makes importing pre-existing files trivial"), the filename parser already has a branch that yields no
direction and no number (VoIP), the decoder is format-agnostic, and transcription needs only a catalogued
file with a unique name. Everything downstream — transcript, summary, search, tags, export, delete cascade —
is keyed on the filename and works unchanged.

**But the file-deleting sweeps have no idea what an import is:**
- The Drive sync worker uploads **every** file in the recordings folder, and in Drive-only mode **deletes the
  local original**.
- Retention deletes by last-modified — so stamping an import with the *original* recording date would let a
  90-day retention delete a two-year-old voice note on the next nightly sweep.
- The storage cap evicts oldest-first; only a favourite star exempts.
- Delete removes any same-named file in both folders, so an import must never keep its source name.
- AIDashboard ingests anything audio from the Drive folder and would transcribe and summarise it again,
  filing it as a nameless call.

**Decision: imports live in the recordings folder** (so they survive the catalog's destructive re-seed and
need no second folder grant), named `{stamp}_import[_label]{ext}` mirroring the VoIP naming, **excluded from
Drive by default**, and skipped by retention's untracked pass and by merge.

## Plan, in order (each phase its own commit, each revertable alone)

**Phase 0 — make the shell safe (no visible change).**
Move the ON_RESUME refresh and the status/health/heal work out of the recordings screen into the app shell,
so it fires whatever section is open. Add a route to the four notifications and honour it on arrival.
Tests: pure resolver for "which section to open" (stored section × onboarding × invalid section).

**Phase 1 — the sweep gates (no visible change).**
Teach the Drive sweep, the storage router, retention's untracked pass and merge-candidates about the import
token, before any import can exist. Unit tests on each predicate.

**Phase 1 — 🧪 VERIFYING (built 2026-09-16, unit-tested, nothing run on a device).**
Landed: the import name (`ImportedRecording`, marker read by slot position so a contact called
"Important" or an app named "Import" cannot claim the exemption); `CloudCopyPolicy.mayGoToCloud`
asked by both routes to Drive; `RetentionPolicy.isEligible` now takes the name and refuses imports;
`MergeCandidates` out of the view model, with the Merge menu entry hidden for an import. No visible
change — nothing imports anything yet.

Left alone deliberately, and what Phase 4 has to deal with before an import can exist:

- **The Drive health check will cry wolf.** `SilentFailureNotifier.checkSyncHealth` counts every
  catalogued row with a device copy and no Drive copy. An import never has one, by design, so it is
  permanently "unsynced" and will eventually tell the user copying to Drive has stopped — the same
  false positive two users hit on 2.2.0. It needs the same exclusion.
- **The storage cap would destroy an import outright.** The cap evicts device copies oldest-first,
  and for a call that is fine — the Drive copy survives and the row keeps its transcript. An import
  has no Drive copy, so eviction is permanent deletion of the only copy plus its transcript cascade.
  Not changed here: a cap is the user's explicit instruction and the star already exempts what they
  want kept. Decide in Phase 4 whether that is enough.
- **The catalog re-seed drops imports in DRIVE-only mode.** `RecordingsRepository.scanFolders`
  enumerates the Drive folder alone when the target is DRIVE, so a re-seed would lose every import
  from the list while the file sits on the device.
- **`enumerateFolder` knows two extensions** (`.ogg`, `.m4a`) and otherwise leans on the provider's
  MIME type. `.mp3`, `.opus` and `.wav` imports depend on that MIME being reported.
- **`deleteRecording` deletes any same-named file in both folders**, so an import's name must stay
  unique — the stamp carries milliseconds, which two imports in the same second cannot collide on.

**Phase 2 — hub + sections.**
Grow the screen enum into hub/recordings/transcripts/summaries/reading; persist the last section; hoist list
and playback state above the section switch; keep the Settings drawer over every section; keep dialogs
outside each section's scaffold. Hub = status card + three count cards (counts must not create the
transcripts database on a phone that has never transcribed). Explicit colours — M3 defaults render coral here.

**Phase 2 — 🧪 VERIFYING (built 2026-09-16, unit-tested, driven on the emulator; nothing seen on a
phone).** The maintainer confirms it or it is not done.

Landed: `HomeScreen` is now a shell over four sections rather than one screen, with the section chosen
by the router (`AppNavigationScreen`) and persisted on every navigation; `HubScreen` (the app's first
`LazyVerticalGrid`, 2 columns); `LibrarySectionScreen` serving both Transcripts and Summaries as
minimal but real lists; `LibraryCounts` behind `TranscriptDatabase.exists()`; the four notifications
now route to the hub. The status card, the USB advisory and both update banners moved off the
recordings list onto the hub.

The three regression risks, and what was done about each:

1. **State across a section switch.** Every saved field stays in the shell, above the switch, so a
   section change composes and decomposes only the section's own rendering. Each list has its own
   hoisted state (`listState`, `hubGridState`, `transcriptsListState`, `summariesListState`), because
   each leaves composition twice over — when a recording is open, and when another section is showing.
   The merge state is still deliberately non-saveable, untouched.
2. **Dialogs outside the scaffold.** The sheets that were inside the recordings scaffold
   (transcribing, transcript search, bulk delete, Support, What's New) moved out to the shell, beside
   the ones that were already there. A dialog raised anywhere is now drawn whatever section is showing.
3. **The counts and the database.** `LibraryCounts` asks `TranscriptDatabase.exists()` first and
   answers zero without opening anything. Two unit tests, one of them in its own class because
   Robolectric shares a sandbox between classes with the same config. Confirmed on the emulator:
   landing on the hub and visiting all three sections left `recordings.db` on disk and no
   `transcripts.db`.

Verified on the emulator (AOSP 16, `com.baba.callvault.instrtest`): the hub renders with live counts;
each card opens its section; back returns to the hub and from the hub leaves the app; reopening lands
on the last section; the Settings drawer opens from every section; the recordings list keeps its scroll
position across a section switch; an open recording survives a rotation and returns to its own section.
Notification routing checked cold (`am start` with the extra) and warm (`-f 0x14000000`).

Left for Phase 3, deliberately: the Transcripts section is a list of names with the existing transcript
sheet behind it, not the page the plan describes. `TranscriptDatabase.exists()` is **not** a reliable
proxy for "has ever transcribed" on a device with recordings — `RecordingExtrasRepository.precomputeWaveform`
creates the database, unguarded, to cache a waveform. The guard still does its job (the hub creates
nothing), but nothing else should read `exists()` as "has transcripts".

**Phase 3 — Transcripts page + reading view.**
List of everything transcribed (batch status query + in-memory join to the recordings list), reusing the
existing transcript action button, queue sheet and search. The reading view is the existing transcript sheet
moved onto its own screen — same parameters, plus its ~120 lines of data plumbing.

**Phase 3 — 🧪 VERIFYING (built 2026-09-16, unit-tested, driven on the emulator; nothing seen on a
phone).** The maintainer confirms it or it is not done.

Landed: `TranscriptsScreen` — the finished transcripts, with *being transcribed* and *didn't finish*
above them, search raising the existing sheet, and the queue's existing Stop; and the reading view,
which is the same transcript body in a page frame instead of a sheet frame (`TranscriptView`, a
`presentation` parameter, one `TranscriptBody` call site). `LibrarySectionScreen` now serves Summaries
alone.

Decisions worth not re-litigating:

- **Search is the existing sheet raised from the page**, not an inline field. A hit is a moment inside
  a call rather than a transcript, so inline results would replace the list with rows that mean
  something else — and a second implementation would be a second FTS query to keep correct over an
  index whose quoting rules have already produced one crash. Raised from *this* page a hit opens the
  reading view and plays from there; from the recordings list it still only plays, unchanged.
- **Back from the reading view returns to whatever it was opened over**, which is Transcripts, because
  opening it never changes the section. It also **stops the audio**, where dismissing the sheet does
  not: the recordings list keeps the playing row tinted behind the sheet, a list of transcripts shows
  nothing about playback at all (#27).
- **No snippet of the words on a row.** The transcripts table holds no text, so a first line means a
  group-by across every segment of every call, paid on every visit and growing with the library — the
  shape of the list-load regression.
- **A transcript whose recording is gone is listed, not dropped.** Dropping it made the hub say "4
  transcribed" over a page of three, with nothing to explain the missing one: the card counts rows in
  the transcripts database and cannot see the catalog.

Verified on the emulator (AOSP 16, `com.baba.callvault.instrtest`, seeded transcripts): all three
groups render; the page count and the hub card agree at 4 and again at 3 after a delete; a row opens
the reading view; tapping a line plays from that timestamp; rotation keeps the page, the track and the
playing position; back returns to Transcripts and leaves nothing playing; the sheet still opens from
the recordings list and from a recording's screen; deleting from the reading view updates list and
count with no manual refresh; the empty state shows when there is nothing transcribed.

Not exercised on the emulator: a *real* running transcription, because no model is installed there —
the queue's Stop only appears while a worker is actually running, so it was reasoned about and not
seen. The running rows were seeded, so the ring and the heading are confirmed but the percentage
inside the ring is not.

Left for later, deliberately: Summaries still drops a summary whose recording is gone, so its card and
its list can disagree the way Transcripts no longer does. Phase 5 should fix it the same way.

**Phase 4 — import (smallest useful version).**
A SAF audio picker on the Transcripts page → copy into the recordings folder under the import name →
catalogue it → read its duration. No new permission (SAF only, never `READ_MEDIA_AUDIO`), no manifest change,
no database migration. Always ask language and always confirm the estimate for an import. Hide Merge for
imports; add an "imported" badge; make an undated row fall back to its file date.

**Phase 5 — Summaries page**, on a batch summary query (the per-recording state holder would spawn one
observer per row).

**Phase 6 — decisions to make later, not now:** whether transcription settings move out of Settings onto the
Transcripts page (and what that means for the wizard, which cannot be re-run); a share-sheet target
(`ACTION_SEND` audio/\*) via a separate lightweight activity so a share never lands in onboarding; batch
import; a non-call summary prompt, since every prompt currently says "phone call".

## Testing approach

House style is to test the decision, not the rendering, and there is no Compose test stack today. So: pure
unit tests for section resolution, the import name, the parser branch and every sweep predicate; then
on-device checks on the emulator and the OP9 for navigation, state restoration and a real import. Adding the
Compose test dependency is a separate decision — worth raising once the hub exists, since navigation is
exactly what has no safety net.

**Device probe needed before Phase 4 commits to formats:** decode a WhatsApp `.opus`, an `.m4a`, an `.mp3`
and a `.wav` through the app's own decoder. WAV is the one that may fail.

## Not in this plan

Stereo (on hold), the Samsung Auto Blocker probe (parked), and anything that changes how calls are recorded.
