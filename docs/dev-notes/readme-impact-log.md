# README impact log

## 2026-09-14 (later) — switching Wireless debugging off yourself; Shizuku mode

🧪 VERIFYING. Added to "The two debugging switches": CallVault leaves a Wireless-debugging switch you turned off
alone unless *Keep Wireless debugging on for recording* is on; and a Shizuku-mode paragraph (what stops Shizuku,
that CallVault warns and reconnects, that Shizuku started after USB debugging is off keeps running). Measured as
E3/E4 (emulator) and S4/S5/R11 (OP9). Only true once `fix/adb-transport-dead-ends` ships.

## 2026-09-14 — the two debugging switches (#23, #24, #39)

🧪 VERIFYING. New section "The two debugging switches" under Install, and the away-from-Wi-Fi note now says it
needs USB debugging. Every claim comes from measurements in
`docs/dev-notes/2026-09-14-debugging-switches-model.md` (emulator + OP9): turning USB debugging off stops
adbd even with Wireless debugging on; Wireless debugging needs Wi-Fi; recording away from Wi-Fi needs USB
debugging; with the `fix/adb-transport-dead-ends` build CallVault restarts adbd on Wi-Fi within seconds.
The "within a few seconds" claim is only true once that branch ships — it is false for 2.3.0.

## ✅ THE README IS PUBLISHED — 2026-08-30

`README.md` on `main` is the rewrite (`3a2cb00`), 3,439 words → 1,832. The artifact
(`30a4b18b-128a-4717-bbfd-d042a501f3b3`) is kept in step with it and is no longer a preview of
something unpublished.

**This file's job has changed.** It is no longer a queue of things waiting to go in; it is the record
of what is claimed publicly and what is deliberately not. Keep logging claim-affecting changes here —
the README can now be *wrong* rather than merely incomplete, which is a higher bar.

**Two further corrections found only while converting to Markdown**, after the artifact had already
been reviewed twice:

- *"Transcribing long calls — anything over 15 minutes is refused today"* survived as a **roadmap
  item** even after the same claim was fixed in the body. It was promising work that shipped in
  2.2.0. Lesson: fixing a stale claim once is not enough — grep the whole document for it.
- The summary model is **2.6 GB**, not 3.5 GB, since the quantisation-aware build landed in 2.2.0.
  Appeared three times.

**Still deliberately NOT claimed:** that an app call interrupted by a phone call stays one recording
(R30). It shipped in 2.2.0 untested.


The README rewrite is **approved and held** — 1,450 words down from 3,439, screenshots staged, not to
be published until the maintainer gives the go-ahead on a release. Work has continued since it was
approved, so this file tracks what would need to change in it *before* it goes up.

**Rule: every change that alters what the README claims gets a line here, in the same turn it is made.**
An approved document that quietly goes stale is worse than one that was never written, because nobody
re-reads it before publishing.

Status markers follow the global convention: 🧪 VERIFYING · ✅ VERIFIED · ❌ NOT WORKING · 📐 CALCULATED.

---

## Needs a README change

| # | Change | What the README needs | Status |
|---|---|---|---|
| R1 | **Licence-server death at paid rivals** — a paid recorder's domain lapsed and *retroactively* capped "lifetime" users to 30-second recordings; three of five paid incumbents are now 404 on Play US. | A line in the positioning. This is the strongest FOSS argument found in the whole research pass and the README currently does not make it. It reframes free software from a *preference* (privacy, price) into avoiding a *loss*: "I paid, I complied, and I lost it anyway." | ✅ evidence verified from reviews |
| R2 | **Setup wall is a trust failure, not a difficulty failure.** Complaints about rivals are about *disclosure order* — "they don't tell you until after you subscribed", "all my UPI apps stopped working". | The setup section should state consequences **before** the steps, including any effect on other apps. A reader who decides against it there is a success, not a lost install. | ✅ evidence verified |
| R3 | **We are the only FOSS recorder that is stock-unrooted, needs no companion app, records both sides of VoIP, and transcribes on-device.** Not "best at" — only. | The comparison table already exists; this claim should be stated plainly rather than left for the reader to infer. BCR lists unrooted stock support as a *non-feature*. | ✅ verified against their docs |
| R4 | **Off-Wi-Fi recording shipped in v1.4.0**, not parked. | If the README describes Wi-Fi as a requirement anywhere, that is wrong. Note the real limit instead: tcpip clears on reboot and re-arming needs Wi-Fi once. | ✅ verified in source and in the v1.5.8 tag |
| R9 | **Works with self-hosted sync** — recordings are staged privately and only appear in the folder once complete, so Syncthing, FolderSync and Nextcloud can never pick up a truncated or 0-byte file. | Worth stating plainly: this is a concrete, checkable advantage over recorders that mux straight into the destination, and it needs no network code of ours. | ✅ VERIFIED 2026-08-27 |
| R10 | **App calls now carry speaker labels**, not just carrier calls. | If the README describes speaker attribution, it should not imply carrier-only. | ✅ VERIFIED 2026-08-27 |
| R11 | **Transcription limit is 60 minutes**, not 15 and no longer 20. Chunked passes bound peak memory by one ≈ 6-minute chunk, so length stopped driving the heap. | Any stated limit must match. Write **60**. The earlier 20 in this row was correct on 2026-08-27 and was superseded the next day — noted so nobody trusts a stale row. | 🧪 60 is 📐 CALCULATED, not measured — hold until a real long call completes |
| R5 | **Bluetooth headsets work** — field-proven by the maintainer's daily AirPods use. LE Audio/LC3 is untested. | Worth stating, since "does it work with my headphones" is an obvious pre-install question. Do not claim LE Audio. | ✅ VERIFIED by daily use / LE Audio 🧪 untested |

| R14 | **Search covers summaries and notes**, not just the spoken transcript. A call is findable by the words its summary used for the outcome — which are often not words anyone said aloud — and by the note the user typed themselves. | If the README describes search, it must not say "search your transcripts"; it searches transcripts, summaries and notes. Worth stating, because the summary is where a decision is recorded in plain language. | ✅ VERIFIED 2026-08-29 — searching the maintainer's own library finds calls by their summary text |

| R15 | **Transcripts export as TXT, Markdown, SRT, VTT or JSON.** Subtitle formats mean a recording and its transcript can be opened together in a player or editor; Markdown carries the summary and the note with it. | If the README lists what you can do with a transcript, it currently implies copy and share only. Worth stating the subtitle formats specifically — no other FOSS recorder in the survey exports them. | ✅ VERIFIED 2026-08-29 — SRT exported and opened successfully. 🧪 Markdown and JSON now also carry the note and the tags (2026-08-29, after the first version silently omitted the note), which is unconfirmed. Markdown, VTT and JSON are still only unit-tested; SRT is the strictest of the five, so this is good evidence rather than proof for the rest |

| R16 | **Optional app lock.** The device's own unlock is required before recordings and transcripts are shown, and the content is kept out of screenshots and the app switcher. Off by default; refuses to turn on when the phone has no screen lock. | Worth a line in whatever the README says about privacy, and it should say the lock is a **door, not encryption** — the audio stays readable by a file manager and by whatever syncs it. Overstating this would be the worst kind of README error. | ✅ VERIFIED 2026-08-29 — used on the OP12; lock and recents blanking both behave |

| R17 | **Tags.** Recordings can be labelled and the list filtered by a label. | Worth stating, and worth stating *why*: a contact name cannot find a call with a number that is in no address book, which is a large share of the calls people most want to find again. The comparison table should note it — `bcr-gui` has an open request for exactly this. | ✅ VERIFIED 2026-08-29 — tagging and filtering used on the maintainer's own library. 🧪 Rename-everywhere and delete-everywhere added the same day, unconfirmed. Tags also travel in Markdown and JSON exports |

| R18 | **The transcript has one Share button**, offering plain text or a file format, and **long-pressing a line copies that sentence** with the speaker's name. | Minor, but if the README walks through the transcript screen it should not describe a separate Copy button. | ✅ VERIFIED 2026-08-29 |

| R19 | **The off-Wi-Fi reboot limitation is real and permanent.** Off-Wi-Fi recording works, but `adb tcpip` clears on reboot and cannot be re-armed without reaching a Wi-Fi network once. | State it plainly in the setup section rather than letting a user discover it by missing a call. Four escapes were tested and all are shut — including on AOSP — so this is a platform limit, not a gap we intend to close. Saying so is more honest than silence, and the research says disclosure order is what this audience actually punishes. | ✅ VERIFIED 2026-08-29 by spike + a hand test |

| R12 | **CallVault says when a recording contains no audio.** | Safe to state, but state it *narrowly*: it catches a file with no audio samples, **not** a full-length recording of silence. An over-broad claim here would manufacture exactly the false confidence the fix exists to remove. | ✅ VERIFIED 2026-08-27 |

| R20 | **A recording can be starred**, and Home has a "Starred" filter chip that appears once anything is. | Small feature, but it is load-bearing for R21 and R22: a starred recording is exempt from both automatic deletes. If the README describes either of those, it must say the star is the escape hatch. | ✅ VERIFIED 2026-08-30 — star, filter chip and the Settings rows confirmed on the OP12 by the maintainer |

| R21 | **Recordings shorter than a chosen length can be discarded automatically** (off by default). | Safe to describe once confirmed, but state the default plainly: it deletes nothing unless the user turns it on. Applies to carrier and app calls alike. | ✅ VERIFIED 2026-08-30 — setting present and working on the OP12. Note: no *short call* has been through it yet, so the README may describe the setting, not yet claim a measured discard |

| R22 | **A size cap on how much of the phone recordings may fill** (off by default), oldest deleted first, starred never taken, Drive copies untouched. | Three qualifications the README must not drop: off by default, device-only, and starred recordings are never deleted even if the cap cannot be met. Any of them omitted turns an opt-in tidy-up into an unexpected data-loss claim. | ✅ VERIFIED 2026-08-30 — setting present and working on the OP12. Note: no cap sweep has actually *fired* yet, so the oldest-first and starred-exempt behaviours remain proven by unit test only |

| R23 | **CallVault now says when it has stopped working**: a notification after a reboot when the recorder could not come up, and one when recordings have stopped reaching Drive. Both self-clearing, both mode-aware. | This changes what R19 has to say. The off-Wi-Fi limitation is still real and permanent, but it is no longer *silent* — the README should describe the limitation **and** the warning together, or it understates the app. | 🧪 VERIFYING — built and unit-tested 2026-08-30; needs a reboot off Wi-Fi to fire for real |

| R24 | **The recovery wording is corrected everywhere it appears**: joining a Wi-Fi network for a few seconds, not "having internet". Stated in Settings up front for anyone with offline recording on. | R19 must not be written using the old framing. The gate is a Wi-Fi *association*; any access point does, with no internet at all. Describing it as an internet requirement turns a ten-second fix into a trip home and makes the limitation sound far worse than it is. | ✅ VERIFIED 2026-08-29 by the E3 spike; the copy change itself is 🧪 VERIFYING |

| R25 | **The call notification offers Stop as well as Pause/Resume.** | Minor, but the README should not describe the notification as pause-only. Pause and Resume were always there; Stop is new. | 🧪 VERIFYING — built 2026-08-30 |

| R26 | **Optional BCR-compatible `.json` details file beside each recording** (off by default). | This upgrades an existing claim. `docs/SUPPORT.md` already says we replicate BCR's *filename* format; with this we also write their metadata file, so tools like `bcr-gui` get the number, contact and direction rather than only a file listing. State it as opt-in. | 🧪 VERIFYING — schema copied from BCR's README and unit-tested against the literal key names 2026-08-30; not yet read by an actual bcr-gui install |

| R27 | **Mark a moment mid-call**, from the notification, on carrier and app calls alike; marks appear on the playback screen as chips that seek. | New capability worth stating. Note the marks are positions in the saved audio, so they stay correct across pauses. | 🧪 VERIFYING — built and unit-tested 2026-08-30 |

| R28 | **App calls now have an ongoing notification with Stop and Mark.** | This closes a gap the README should not have to admit later: before this, a VoIP recording in progress had no controls at all. Worth stating positively rather than as a fix. | 🧪 VERIFYING — built 2026-08-30, needs a real WhatsApp call |

| R29 | **Per-app choice of which apps' calls are recorded** (all on by default). | Directly relevant to the consent/legal paragraph: it is the answer to "I want this for some apps but not others", and it is worth saying that the default records everything so nobody assumes an opt-in they did not make. | 🧪 VERIFYING — built and unit-tested 2026-08-30 |

| R30 | **An app call interrupted by a phone call stays ONE recording.** The app-call capture is held open across the phone call and continues into the same file. | Worth stating plainly — it is the kind of detail that separates a call recorder that has been used from one that has been written. State the limit honestly too: the held stretch is absent from the file rather than recorded, because the microphone genuinely has to be released for the phone call's own recording to be correct. | 🧪 VERIFYING — built and the state machine unit-tested 2026-08-30; needs a real switched call, listened to |

## Folded into the README — 2026-08-30

The artifact was updated on 2026-08-30 (`30a4b18b`). Folded in: **R5** (Bluetooth), **R9**
(self-hosted sync), **R14** (search covers summaries and notes), **R15** (export), **R16** (app
lock), **R17** (tags) plus **R20** (stars), **R21**/**R22** (housekeeping: age, size cap, minimum
length), **R19**+**R24** (the off-Wi-Fi reboot limit, in the corrected "any network, no internet"
wording), **R23** (says when it has stopped working), **R25**/**R27**/**R28** (in-call controls and
marking a moment), **R26** (BCR metadata file), **R29** (per-app choice).

**Two corrections made in the same pass:**

- The README said *"Calls over 15 minutes can't be transcribed yet."* The shipped code has
  `TranscriptionLengthLimit.MAX_MINUTES = 60`, so that understated the app fourfold. Replaced with a
  non-numeric sentence — the 60-minute figure itself stays held under R11/R13, so no number is
  published in either direction.
- An earlier session's note that D2 ("record private, publish complete") was unimplemented was
  **wrong**. `SafHelper.createAudioFile` is explicit: *"ALWAYS staged, and the destination file is
  NOT created yet."* R9 was right all along; the doc was not.

**Shizuku was promoted.** It had appeared only as a column of ❌ in a comparison, which read as a
lesser fallback. It is now a named mode under "Two ways to run it", with the table relabelled
*Built-in mode / Shizuku mode*. A claim that speaker labels "work the same" in Shizuku mode was
caught and removed before publishing — the table says ❌ for that row, because speaker labels come
from the capture channels and are genuinely mode-dependent.

**Published but NOT yet device-verified**, so worth pulling if any of it proves wrong: R23, R26,
R29. Each is a visible setting or a warning the user can check; none was invented.

## Folded into the README — 2026-08-30, second pass: the ColorOS setup wall

**R31 — OPPO, OnePlus and Realme phones need one Developer-options switch before CallVault can work
at all.** Published as a new `### On OPPO, OnePlus and Realme phones` subsection under *Install*,
plus a pointer from the Requirements callout. Deliberately published **ahead of any code fix**, at
the maintainer's instruction, because a user hitting this today has no way to know what is wrong.

What the README now says, and why each part is there:

- **It affects Shizuku mode too.** Verified: the block is on the shell uid, and Shizuku *is* shell.
  Omitting this would send OPPO users to Shizuku as a workaround that cannot work.
- **Both names for the switch** — *Disable system optimization* (current) and *Disable permission
  monitoring* (older builds). Searching for the wrong one finds nothing.
- **The switch on our own OP9 Pro is confirmed working** (2026-08-24, and re-measured 2026-08-30:
  with it on, `appops set`, `pm grant` and `WRITE_SECURE_SETTINGS` all succeed).
- **The English-language trap** — the item is hidden in some translations. This is 🧪 field-reported
  from `RikkaApps/Shizuku` #374 and #2149, **not** reproduced by us; two independent reporters,
  including one whose "ColorOS 16.0.7 removed it" bug closed when they found it in English. Published
  because the cost of a wrong instruction here is a user switching their language back and forth for
  nothing, while the cost of silence is a user concluding the app is broken.

**Not published, on purpose:** the underlying property (`persist.sys.permission.enable`). It is an
implementation detail, its polarity is inferred rather than proven, and it is unwritable from shell —
so telling a user about it offers them nothing they can act on. See the memory
`coloros-toggle-renamed-and-hidden` for the measurements.

## Blocked on verification — do not write these into the README yet

| # | Change | Why it is held |
|---|---|---|
| R30 | **An app call interrupted by a phone call stays ONE recording.** | Deliberately NOT published on 2026-08-30. It is the riskiest change in the release — it edits the daemon's capture loop — and the maintainer could not test the switch scenario. Publish only after a real switched call has been listened to in both directions. |

| R13 | **Long calls can be transcribed** — chunked passes removed the length ceiling that used to refuse anything over 15 minutes. | The regression that killed the first attempt is confirmed gone, but on **one** call, by **one** user, in **one** language (Hebrew, 26:41, 2026-08-28). Agreed with the maintainer that this is not enough to publish a claim on. Needs the French tester on a call that crosses a chunk seam, plus one call over 20 minutes from someone other than the maintainer. See `2026-08-27-decode-memory-and-silent-failure.md`. |
| R11 | The **60-minute** figure itself. | Same hold as R13, and additionally the number is arithmetic rather than a measurement — no call anywhere near 60 minutes has been transcribed. Publishing a limit we have never reached invites exactly the bug report it would cause. |

## Explicitly NOT going in the README

- **Consent beeps / recording announcements.** No public API at any privilege level we can reach — the
  platform dialer uses a HAL path unavailable even at shell uid. If the README mentions legal
  compliance, it should say we cannot inject a beep rather than implying we might.
- **Wear OS, launcher-icon hiding.** Zero demand and provably non-functional respectively.
- **Any hardware-acceleration claim.** NNAPI, Hexagon and Vulkan are all ruled out for our stack.


## 2026-09-02 — merging calls (2.3.0)

✅ **Folded in.** "What it does" gains a merge bullet, above Transcripts:

> 🔗 **Merge calls that were one conversation** — a call drops and you ring back; join them into one
> recording, in the order you choose. Lossless, and un-mergeable afterwards.

Three claims in that line, each deliberate and each checked:

- **"Lossless"** is literal, not marketing. The join copies encoded frames without re-encoding, and
  the round trip was measured — decoded audio matched the original in 0 of 240,640 samples once the
  encoder priming offset was accounted for. If merging ever grows a re-encode path for mismatched
  formats, this word has to change.
- **"in the order you choose"** rather than "in order" — the order is the order you tick, not
  chronological, and that is a deliberate design decision the README should not paper over.
- **"un-mergeable afterwards"** is the whole reason merging may delete the originals. It stays true
  only while `MergeRoundTripTest` passes.

⚠️ **Not claimed, on purpose:** that a merged recording's Drive copy or transcript is preserved. Both
are, but the bullet is already dense and those are the sort of specifics that age badly.

📷 **Screenshots unaffected** — merging is reached from a row's ⋮ menu, so no pictured screen changed.

## 2026-09-05 — the USB advice was incomplete, and the README was edited directly

**Written into the README already** (not pending), because the existing line was actively harmful to
one class of user: the Charging-only advice at `README.md:129` now carries a warning that changing the
Default USB configuration stops Shizuku, and must not be done during a call.

**Why it was not just a wording tidy.** Measured on the OP9 (ColorOS 14) on 2026-09-05: one change to
the USB configuration restarted `adbd` — new pid — and `shizuku_server` died with it and did not come
back. A plain detached shell script survived the same event, so this is specific to how Shizuku's
server is hosted, not a blanket kill. Issue #28's reporter described exactly this and our README, our
Settings picker and our one-tap fix all recommended it anyway.

⚠️ **Not claimed:** that Samsung/One UI behaves identically. The mechanism is measured on ColorOS and
reported by a Samsung user; the README words it as what the change does (restarts the debugging
service), which holds either way.

📷 **Screenshots:** the Settings USB row gained a Shizuku-specific hint and no longer labels "Charging
only" as recommended in Shizuku mode. If a Settings screenshot is ever added, it must not be taken in
Shizuku mode.

## Review checklist before publishing

1. Re-read this file top to bottom; fold in every ✅ row.
2. Check nothing in the 🧪 section has silently been written in anyway.
3. Confirm the screenshots still match the current UI — the Home status card gained a new failure
   message in `892509e`.
4. Confirm the GPLv3 §7 attribution is present: an in-UI "fork of ShizuCallRecorder" notice plus a repo
   link. That obligation is unchanged and is not optional.

## 2026-09-11 — Shizuku support made visible (maintainer)

The banner pill read **"No Shizuku"** and the GitHub description said **"no root/Shizuku/PC"**, which reads
as "does not support Shizuku" — the opposite of the truth since Shizuku mode shipped. Changed:

- Banner pill → **"Built-in or Shizuku"** (`docs/screenshots/banner.svg`).
- GitHub description → "FOSS call recorder for Android, no root or PC — records both sides of a call on
  its own over built-in ADB, or through Shizuku if you already use it. Fork of ShizuCallRecorder."
  Topics added: `shizuku`, `call-recorder`, `android`, `adb`.
- README: a "Works with Shizuku" badge; two side-by-side mode boxes under the tagline (built-in first,
  marked default); the fork note no longer says "without requiring Shizuku"; "Two ways to run it" now
  opens with built-in, and its table lists what both modes do before where they differ.

**Still claimed, unchanged:** every ✅/❌ in the comparison table. The only new row — "Nothing to pair, and no
wireless debugging of CallVault's own" — restates the paragraph that was already there.
