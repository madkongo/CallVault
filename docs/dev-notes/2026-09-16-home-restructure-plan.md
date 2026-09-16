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

**Phase 4 — 🧪 VERIFYING (built 2026-09-16, unit-tested, driven on the emulator; nothing seen on a
phone).** The maintainer confirms it or it is not done.

**The format probe was run first, and the plan's expectation was wrong.** One second of a 440 Hz tone
in each container, through the app's own `AudioDecoder`, on the emulator (AOSP 16, arm64):

| fixture | container / codec | decodes | note |
|---|---|---|---|
| `.opus` | Ogg / Opus 48 kHz | ✅ | 16216 samples for 1.000 s |
| `.ogg` | Ogg / Opus 48 kHz | ✅ | 16216 samples |
| `.m4a` | MP4 / AAC 44.1 kHz | ✅ | 16346 samples |
| `.mp3` | MP3 44.1 kHz | ✅ | 16000 samples; duration read as 1044 ms |
| `.wav` (PCM s16le) | WAVE | ✅ | **the one the plan expected to fail** |
| `.wav` (PCM f32le) | WAVE | ✅ | the codec converts to 16-bit for us |

WAV decodes: `c2.android.raw.decoder` is present, and PCM/WAVE decoding is a CDD requirement for
handhelds anyway. The float variant decodes too, because MediaCodec's default output encoding is
16-bit and the raw decoder converts rather than handing back floats — so `AudioDecoder`'s
PCM-encoding check never fires on it. Both are asserted by `AudioImportFormatProbe`, so a platform
change to either is one failing test rather than a field report.

**So the gate is in two halves, and that is the design decision worth keeping.** `ImportableAudio`
answers what a name and a MIME type can answer — is this a shape we know how to store. The runtime
decode probe in `AudioImport` answers the rest, because a name is only a claim: a file called `.mp3`
that holds anything else passes every check that can be made without opening it. The probe reads two
seconds of the **copy**, so importing an hour-long recording costs the same as importing a voice note.

Landed: `ImportableAudio` (what we accept and what we store it as); `AudioImport` (metadata → copy →
decode probe → catalogue, in that order, with `planFor` holding the refusals as a pure function); the
SAF picker on the Transcripts page, above the groups and above the empty state, whose hint is reworded
now that importing is a second answer; an import branch in `RecordingsRepository.parseName`; the
"imported" badge in `LibraryNameRow` and on the recordings row; a date fallback for an undated row;
always-ask-language and always-confirm for an import; and the four sweep guards.

Decisions worth not re-litigating:

- **No permission, ever.** `ActivityResultContracts.OpenDocument` filtered to the audio wildcard.
  `READ_MEDIA_AUDIO` would let a call recorder read every audio file on the phone. The filter is the
  wildcard rather than our exact list because a provider is free to report `application/octet-stream`
  for a perfectly good voice note, and naming exact types would hide it from the picker entirely.
- **Copy before decode, always.** A share-sheet or chat-app URI is frequently a non-seekable pipe and
  `MediaExtractor` needs to seek, so probing the source works on files picked from local storage and
  fails on exactly the ones this feature exists for.
- **The import runs in the ViewModel's scope, not the screen's.** A rotation half way through would
  otherwise cancel it between the copy and the catalogue, leaving a file in the user's folder that the
  app has no record of.
- **Retention now exempts an import in the catalogued pass too.** Phase 1 stamped it with the import
  time, which stops it arriving already expired; it does not stop the sweep taking it once the period
  elapses. For a call that delete is the routine loss of a device copy and the Drive copy survives —
  an import has none, so the same delete destroys the only copy plus its transcript. Same argument as
  the storage cap, and the untracked pass had already said it in words.
- **The storage cap exempts imports and says so in its description**, in all eleven locales. They
  still count toward the total, like favourites, so a library of imports cannot sit over the cap while
  calls are deleted instead.

Verified on the emulator (AOSP 16, `com.baba.callvault`, a real `.opus` named the way WhatsApp names
one, and an `.m4a`): the picker offers both with no permission prompt; the import lands in the
recordings folder under `{stamp}_import_{label}{ext}`; the app opens the imported recording's own
screen, which plays it and shows its length; both appear in the recordings list with the "imported"
badge, a date, a duration and a device-only source badge; the Transcripts row shows the badge too;
the row menu offers Share and Delete and **not** Merge, where a call beside it still offers Merge;
deleting an import removes the file; a file called `.mp3` that is not audio was copied, probed,
**deleted again** and refused with a sentence.

Transcription UX confirmed with the "ask before running" setting deliberately turned **off**: the
import still asked the language and still showed the estimate, while a call in the same list went
straight to the queue with no dialogs. The queue itself was confirmed end to end — the scheduler
logged the request, the worker picked it up with the right model — with a **stand-in model file**, so
what could not be run is the transcription itself and therefore the resulting text.

Not exercised: a real Drive account (the exclusion is asserted by `CloudCopyPolicyTest` and both
routes to Drive ask it), a retention sweep on a real clock, and the summary/export paths, which need a
finished transcript and therefore a model.

**Phase 5 — Summaries page**, on a batch summary query (the per-recording state holder would spawn one
observer per row).

**Phase 5 — 🧪 VERIFYING (built 2026-09-16, unit-tested, driven on the emulator; nothing seen on a
phone).** The maintainer confirms it or it is not done.

Landed: `SummariesPage` (the grouping, pure); `SummariesScreen` — the finished summaries, with
*being summarised* and *didn't finish* above them; `WorkProgressRing`, the transcript button's ring
pulled out so both lists draw one; and a fix for a reading page that said "Loading the transcript…"
for ever. `LibrarySectionScreen` had no callers left and is gone; the shared row and empty state it
held stay, in `LibraryRows.kt`.

**The asymmetry that shapes the whole page: a summary has no database row until it succeeds.** A
transcript is QUEUED, RUNNING or FAILED in the database from the moment it is asked for, so
Transcripts reads one table. A summary in flight exists only in WorkManager — so the page reads the
queue's unique-work flow as well, **one observer for the whole list**. `rememberSummaryState` is
per-recording and would have put one WorkManager observer and two database observers behind every
visible row.

Decisions worth not re-litigating:

- **A summary whose recording is gone is listed, not dropped** — the defect Phase 3 left here.
  The hub card is a `COUNT(*)` over the summaries table and cannot see the catalog, so a row
  filtered out is a number the user can catch the app lying about by counting. Drawn from the file's
  own name with no date. `SummariesPage.Groups.ready` *is* the query's result, unfiltered, which is
  how the card and the page agree by construction rather than by two queries that happen to match.
- **A rewrite appears twice**, under *being summarised* and in the list. The old summary is still
  there and still readable while the new one is written; taking it out of the list for those ninety
  seconds would put the page one below the card just tapped. A failed *rewrite*, by the same logic,
  is not listed under "didn't finish" — the earlier summary survived it.
- **A row opens the reading view, not the recording's own screen.** It already carries the summary
  above the words it was written from, which is what anyone checks when a summary looks wrong; it is
  what a Transcripts row opens, so one rule covers every library row; and it is the only one of the
  two that can open a row whose recording is gone — the playback screen finds no catalog row and
  closes itself again, so a tap on an orphan would be swallowed in silence, on the page that exists
  to keep orphans listed. Leaving it stops the audio, as the page frame already does (#27).
- **Stop is on the page; "write it again" is not.** Stopping needs no reading, is always the safe
  direction, and a run somebody wants stopped is exactly the one they may not be able to find.
  Deciding a summary needs *redoing* means having read it and found it wanting — and this list shows
  none of the words, so a redo one mis-tap from a row would spend ninety seconds of full CPU
  replacing something the user never saw was wrong. It stays under the summary it would replace,
  which the page reaches in one tap.
- **The page offers no search.** The existing sheet searches transcripts, summaries and notes
  together and opens a *recording*; wiring it here would mean deciding what a summary hit opens from
  this page, which is the reading view it already opens from Transcripts. Worth doing, not worth
  doing blind — left out rather than half-wired.

**One fix outside the page, forced by it.** Room's flow does not emit until it has queried, so a null
transcript meant both "not read yet" and "there is none", and the reader was always told the
friendlier of the two. Harmless while every way into the reading view was gated on a finished
transcript — but deleting the text leaves the summary behind, and the summary's row then opens a page
that would have read "Loading the transcript…" for ever. The read is wrapped
(`TranscriptRepository.TranscriptRead`) so the two answers are different values.

Verified on the emulator (AOSP 16, `com.baba.callvault`, seeded summaries incl. one orphan, one
import, and seeded queue rows): all three groups render with the right rows; the page's count and the
hub card agree at 4 with an orphan present, and again at 3 after deleting a recording — list and card
both, with no manual refresh; a row opens the reading view with the summary above the transcript, and
"Write it again" is one tap inside it; the orphan's row opens rather than being swallowed, and now
says there is no transcript instead of loading one; an imported row carries its badge; Stop clears
the *being summarised* group live on the visible page; the empty state shows when nothing is
summarised; rotation keeps the section and the page, and reopening the app lands back on Summaries.

Not exercised: a **real** summary run, because the 3.46 GB model is not on the emulator — the queue
rows were seeded, so the headings, the Stop and the grouping are confirmed but the percentage inside
a running row's ring is not (it is the same `WorkProgressRing` the Transcripts page already draws).
`LibraryCounts`' "do not create the database" guard is unchanged and still covered by
`LibraryCountsUntouchedDatabaseTest`; it was not re-checked on a device this round.

**Phase 6 — decisions to make later, not now:** whether transcription settings move out of Settings onto the
Transcripts page (and what that means for the wizard, which cannot be re-run); a share-sheet target
(`ACTION_SEND` audio/\*) via a separate lightweight activity so a share never lands in onboarding; batch
import; a non-call summary prompt, since every prompt currently says "phone call".

**Phase 6 (share target) — 🧪 VERIFYING (built 2026-09-16, unit-tested, driven on the emulator
through a real share sheet; nothing seen on a phone).** The maintainer confirms it or it is not done.

**Why this, and why now.** Phase 4's picker cannot reach the file the feature exists for. A WhatsApp
voice note lives in WhatsApp's app-private storage, where no document provider enumerates it and no
SAF picker can offer it — the maintainer went looking for one and found nothing. Sharing is the only
route to that file.

Landed: `SharedAudio` (what an incoming Intent means, pure); `ShareImportActivity` +
`ShareImportViewModel` + `ShareImportScreen`; the manifest filter; `OpenRecordingRequest` and the
plumbing that lets an Intent land on one recording; and `importRefusalMessage` moved out of
`HomeScreen` so both doors refuse in the same words.

**What the senders actually send, since the plan guessed and guessing was the risk.** Read out of
their own source where it is open: Telegram's voice recorder sets `audio/ogg`; Signal records AAC and
shares `audio/aac` through `Intent.normalizeMimeType`; the platform map has resolved `.opus` to
`audio/ogg` since API 29 (`debian.mime.types`), and two of its answers surprise — `.m4a` is
`audio/mpeg` and `.wav` is `audio/x-wav`. **Measured here**: a `.opus` shared from Files arrives as
`audio/ogg`, and an `.m4a` arrives as `audio/mpeg` — the platform-map trap, live. It imported
correctly anyway, because `ImportableAudio.storedAs` believes the extension before the type.

Decisions worth not re-litigating:

- **`audio/*` plus `application/ogg`, and not `application/octet-stream`.** A wildcard filter is
  compared against only the part of the type before the slash (`IntentFilter.findMimeType`), so
  `audio/*` matches `audio/ogg; codecs=opus` where a concrete `audio/ogg` filter — compared with
  exact string equality — would not. That is the reason to list no concrete audio types at all.
  `application/ogg` is separate because a wildcard on one top-level type never reaches another.
  `octet-stream` is left out: it is the type for bytes Android could not identify, so taking it would
  put a call recorder in the share sheet of every `.bin`, backup and unknown download. **If a real
  WhatsApp share does not offer CallVault, that is the measurement that overturns this, and the
  change is one `<data>` line.**
- **`ACTION_SEND_MULTIPLE` is declined, not half-handled.** Importing several files is a loop but
  reporting on them is not: one copy can fail while another succeeds, and a refusal is a sentence
  about *one* file. Confirmed from outside — selecting two files offers no CallVault at all.
- **The activity label is "Import audio", not "Import to CallVault".** The share sheet draws the app
  label above the activity label in a column about a dozen characters wide; the longer one came out
  as "Import to C…". The line above already says CallVault and draws the icon.
- **A share arriving before setup is finished is told so**, with an Open CallVault button, and
  nothing is copied. Readiness is the router's own two gates (`isComplete()` and `wizardCompleted`),
  asked of the same `OnboardingStatus`, so the two cannot disagree — if they could, a share would be
  accepted and then land the user in the wizard when they tapped Open.
- **The lock stands in front of it, and nothing is read until it is satisfied.** The gate is
  `MainActivity`'s, reproduced rather than referenced, `FLAG_SECURE` included. The cost is two
  prompts when Open is tapped — one for the card, one for the app — which is two windows and
  therefore two doors, and is the right answer for audio out of someone's private messages.
- **Open lands on the imported recording's own screen**, not on the app. Without it, Open opened
  whatever section the user was last in, which on the first run was a page of summaries with no
  mention of the file that had just arrived.
- **Transcription is unchanged and needed no work.** The share produces the same
  `{stamp}_import_{label}{ext}` name, and every rule keys on `ImportedRecording.isImported`.

**One defect found by measurement, and fixed.** Two consecutive shares: the second came back
`START_DELIVERED_TO_TOP` and its Intent was dropped, because `FLAG_ACTIVITY_NEW_TASK` reuses a task
whose root Intent `filterEquals` the incoming one and `Intent.filterEquals` ignores extras — so two
shares of two different files are, to the system, the same Intent. The card now finishes when it
leaves the screen, except while a copy is running.

Verified on the emulator (AOSP 16, `com.baba.callvault`), through a real share sheet from Files
except where noted: the entry reads "CallVault / Import audio"; an `.opus` and an `.m4a` both import,
carry the Imported badge, play, and show their length; Open lands on the recording's own screen and
back returns to Recordings rather than the section the app was last in; a `.mp3` holding no audio is
copied, probed, deleted and refused with its sentence, leaving the folder byte-identical; with the
app lock on, the prompt stands in front of the card and the import runs only after the unlock (30 s
later, in the log); with **both** transcription asks turned off, an import still asked the language
and still showed the estimate while a call beside it went straight to the worker; with the wizard
flag cleared, the share says so and Open CallVault lands on the wizard with nothing copied. By
`am start`: a text-only `SEND`, a `SEND_MULTIPLE` and an unreadable URI each refuse with a sentence
rather than crashing.

Not exercised: **a real WhatsApp share**, which is the whole point and which the emulator has no
WhatsApp to do; a real transcription of a shared file (the model on the emulator is a stand-in); and
Telegram/Signal, whose types are source-proven but not measured here.

Known rough edge, not fixed: a share whose URI has become unreadable (the sender revoked the grant
while the app lock was up) reads as `NOT_AUDIO`, so the user is told CallVault cannot read that
*kind* of file when the truth is that it could not read the file at all. Reproducible with
`am start` and an ungranted URI. A sixth refusal reason would fix it; it is `AudioImport`'s
ordering, shared with the picker, so it is not a share-target change.

**Phase 7 (transcribe only) — 🧪 VERIFYING (built 2026-09-16, unit- and instrumented-tested, driven
on the emulator through a real share sheet; nothing seen on a phone).** The maintainer confirms it
or it is not done.

**What the maintainer said, and what was built.** Sharing a voice note dropped it straight into
Recordings, and "a user might want to import it simply for transcribing — doesn't mean it should be
in the recording page". So the card asks first, in his words: **Transcribe only** and **Import &
transcribe**; and a transcribe-only file loses its audio once the transcript is stored — his choice
too.

Landed: `ImportedRecording.Kind` and the name that carries it; `TranscribeOnlyAudio` (when the audio
may go, and the act of removing it); `RecordingCatalog.forgetName`; `ImportFollowUp` (the order of
the two refusals); the share card's question, language and estimate; `HomeUiState.transcribeOnly`
and `libraryRecordings`; `TranscriptsPage.Groups.waiting` and its rows; two buttons on the
Transcripts import card; and fifteen strings in eleven locales.

Decisions worth not re-litigating:

- **The kind is a token AFTER the import marker** (`{stamp}_import_transcribeonly[_label]{ext}`),
  never a variant of it (`_import-transcribeonly`). A build from before this feature reads the
  marker slot alone: with a variant it would fail to match `import`, conclude the file was not an
  import at all, and hand somebody's private voice note to the Drive upload and the retention sweep.
  As a second token every older build still sees `import` where it looks. A label sanitised down to
  exactly "transcribeonly" is dropped, because it is the one way a user's own file name could reach
  the kind slot and turn an import they asked to keep into one whose audio may be deleted.
- **The delete happens in `TranscriptionRunner.runOne`'s success branch**, after `replaceSegments`
  and after the DONE mark, under `NonCancellable`. Every other way out of that function — a stop, an
  abort, a failure, a refusal for length — has already returned, so none of them can take the audio.
  What may be deleted is a pure function that says no by default, re-reads the database rather than
  trusting the write that has just happened, and counts the stored segments as well as the state: a
  DONE row with no words is a transcript of nothing.
- **Never `RecordingCatalog.removeName`.** It runs `TranscriptCascade`, which deletes the transcript,
  the summary, the note and the tags — the exact text the deletion was made in exchange for. The row
  is dropped by `forgetName`, which drops the row and nothing else.
- **The split is in the ViewModel, once.** `uiState.recordings` is the recordings list and no longer
  holds a transcribe-only import, so the facets, the selection, the merge candidates and the hub's
  count are all correct without any of them being told. What has to work on a file by name regardless
  — the length check and estimate, the player, a dialog's title, the Transcripts join — asks
  `libraryRecordings` instead.
- **A transcribe-only file with no transcript row is listed under *Waiting to be transcribed*.** It
  is kept out of Recordings on purpose, so while a transcript row exists it is visible on the
  Transcripts page and nowhere else — and the row can go away underneath it (a Stop deletes it, so
  does a refusal for length, and a process death between the copy and the enqueue means one was never
  written). Without that group the file would be on disk and in no list anywhere: audio the user
  could not find, play, retry or delete. Its rows offer Transcribe, and the card opens the
  recording's own screen, where playing, sharing and deleting already are.
- **Both doors ask the same question in the same words.** The in-app button asks before the picker
  rather than after, because there is nothing to describe until a file is chosen; the answer is held
  in `rememberSaveable`, since the picker is another app's Activity and a lost answer would default
  to Keep.
- **Both answers now end in a transcription**, and an import still asks the language and still shows
  the estimate whatever the two "don't ask" settings say. Backing out of either keeps the file.

Verified on the emulator (AOSP 16, `com.baba.callvault`), through a real share sheet from Files: the
entry reads "CallVault / Import audio"; the card names the file and its size and offers the two
answers; with no recordings folder the NO_FOLDER refusal is unchanged; Transcribe only stores
`{stamp}_import_transcribeonly_{label}{ext}`, asks the language, shows the first-run estimate, says
"Queued for transcription — the audio is deleted as soon as the transcript is stored", and Open lands
on Transcripts; the file is absent from Recordings and from the hub's count (12 files in the folder,
11 saved) while present on disk; a failed run leaves it under *Didn't finish* with its audio; with the
transcript row removed by hand — what a Stop leaves — it moves to *Waiting to be transcribed*, still
out of Recordings, and its card opens a screen that plays, stars and deletes it; with a DONE
transcript and the audio removed, Transcripts lists it as readable and the reading view opens it. The
in-app card was driven both ways: Import & transcribe landed a plain `_import_` name and opened the
recording's own screen under the language dialog, Transcribe only landed a `_import_transcribeonly_`
name and did **not** open it, and cancelling the language question left it in the waiting group.

The delete itself was run on the device by `TranscribeOnlyAudioDeviceTest` against the real SAF
folder: audio gone, catalog row gone, transcript, segments and note all still there; and the three
keep cases (failed, stopped, kept import) leave the file where it was.

Not exercised: a **real** transcription, because the model on the emulator is a stand-in — so the
DONE-then-delete sequence was run by the instrumented test and by hand, never by whisper finishing;
and a real WhatsApp share.

⚠️ `connectedDebugAndroidTest` without `-PisolateTestApp` **uninstalls** the app afterwards, which
cost this session the emulator's folder grant, catalog and onboarding. The one test that needs the
app's own process says so in its KDoc; nothing of the sort goes near a phone.

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

**OP12 install 2026-09-16 14:55** (🧪): the whole branch (Phases 0–5 + the Telegram card) installed while
idle. Grant survived, recorder reconnected over loopback in 3 s. Everything on it stays 🧪 until the
maintainer's own use settles it — in particular the three things the emulator cannot show: a real
transcription of an imported voice note, a real summary run (ring percentage, Stop mid-generate, the row
moving into the list), and playback stopping when a reading page is left.

**OP12 install 2026-09-16 15:57** (🧪): branch with the share target installed while idle; grant survived,
recorder back in ~2 s. `cmd package query-activities -a android.intent.action.SEND -t audio/ogg` lists
`com.baba.callvault.ShareImportActivity`, so the target is registered on the device. Whether WhatsApp's own
voice-note share reaches it is the open measurement — it depends on the MIME type WhatsApp attaches, which
could not be read on the emulator.
