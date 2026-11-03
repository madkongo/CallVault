# Settings per page: plan (2026-10-20)

📐 PLANNED. Nothing built, and no date set (maintainer, 2026-10-20: "plan for this properly, I'm not sure
when we are going to do this"). This plan replaces the section-grouping question on the earlier Settings
options canvas (https://claude.ai/artifact/2xGqeTEG3aUU1rqrn2VyHN). Re-check the code references below
before starting. They were read on integration/next at 19e722a0.

## Decisions (confirmed by the maintainer, 2026-10-20)

1. **Settings opened from a page shows only that page's settings.** Recordings shows recording settings,
   Transcripts shows transcription settings, Summaries shows summary settings. Opening it from the main
   screen shows everything.
2. **The Experimental section goes.** Its 7 switches move to where they belong. **Their defaults do not
   change**: they all stay off unless the user turns them on. Only the "Experimental" label and its wording
   go. Reasons to keep the defaults:
   - **Recording away from Wi-Fi** opens a debugging port on the phone. Shipping it as a warned opt-in was a
     decision ([[loopback-tcpip-offwifi]]).
   - **App calls**: the "far side can't hear me" report is still open, waiting on logs
     ([[voip-mic-capture-mutes-user]]).
   - **Resilient recording** could become on by default later, but as its own change with its own testing.
3. **The Telegram pill shows only on the main screen,** so each page's title row has room for its settings
   icon. **The ♥ Support pill stays as it is**, on every page.

## Chosen design (maintainer, 2026-10-21)

**Option C, folded groups**, with **option B's "Shizuku mode" switch row** at the top (the same row as today),
on the canvas https://claude.ai/artifact/Bt8twpoGHXX7hW85UFFsxH (artboard "C"). Each group is a card with a
one-line summary of its state ("Reliability · 2 of 5 on"); tapping it opens its switches. "Share a debug report"
and "All settings" are links at the bottom. Transcripts and Summaries get the same treatment (the canvas draws them
in A's sheet style; build them as C).

## Round 2 regrouping (agreed with the maintainer, 2026-10-21: "looks good")

Canvas row 3 ("Round 2: grouped by what you want to do"). Supersedes the "What each page shows" table below for
the NEW screen; the classic screen keeps its old layout.

- **Recordings** (Shizuku mode row on top): Calls to record (phone + app calls merged: which calls, which apps,
  incoming/outgoing auto-record, start when they answer, app calls without asking, vibrate on start/stop) · Calls to
  skip (contact lists in/out, anonymous, other countries, apply skip list to app calls, skip calls shorter than) ·
  Sound and file names (source, codec, bit rate, file name) · Where recordings go (save to, folders, Drive, upload
  schedule, hide from other apps, details .json, transcript .md) · Cleaning up (retention, space limit, keep
  originals after merge) · Connection (USB debugging, record away from Wi-Fi, keep recording if the helper stops,
  keep Wireless debugging on, approve new Wi-Fi networks, USB when screen unlocks, pair again).
- **Transcripts:** Model and language · When to transcribe. **Summaries:** unchanged (language read-only, shared).
- **Main screen:** all of the above + App (language, theme, dynamic colour, toasts, unlock, screenshots, updates,
  backup) + Help and about (debug, classic switch, version, licences, §7 attribution, support).
- **ⓘ instead of descriptions** on the new screen: a row shows its name and value; ⓘ opens a sheet with the
  explanation. Warnings stay on the row (unavailable-in-this-mode, debug logging on).
- No "Reliability" group and no "App calls" group any more. Every setting keeps its preference key and value.
- 🧪 Built 2026-10-21 on `feat/settings-regroup` (110e5df9, 929ea64b, c0582834), merged into integration/next.
  2058 tests. Classic screen re-screenshotted on the OP9: identical to the step-2 build (only the log size and a
  6 px scroll offset differ). New screen seen by Claude on the OP9: Calls to record and the ⓘ dialog work.
  Open: the card summary line is cut off ("Phone and app calls · incoming a…"); free-standing hint paragraphs
  (VoIP names, audio source, retention explanation, …) are still inline; Calls to skip shows phone rules even
  when auto-record is off. Not yet tried by the maintainer.
- 🧪 Round 3 (maintainer feedback 2026-10-21: no upload schedule found, Drive folder shown for device-only, rename,
  still long text): `feat/settings-round3` (4f0e8123), merged. Group renamed "Save and sync"; Drive folder and
  "When to upload to Drive" only when the target is not device-only (`SaveAndSyncRows`); every explanatory hint
  moved behind a row ⓘ or a card ⓘ, only live status/warnings inline; summaries wrap to 2 lines. 2068 tests.
  Classic screen identical on the OP9 (log size + scroll offset only). Seen by Claude on the OP9.
- 🧪 Round 4 (maintainer 2026-10-21): group renamed "Recording"; body split into Phone calls / App calls / All
  calls; the VoIP auto-start switch reads "Record app calls automatically" on the new screen only.
  `feat/settings-round4` (d2f2a2b1), merged. 2070 tests; classic identical on the OP9. Maintainer: "besides that
  i think its all good" (design accepted; still 🧪 until used day to day).
- 🧪 2026-10-21 `fix/title-row-telegram-pill` (9218be60, 7da85b87, ac9bf7c6, dcd2aa5c, 39b1211a), merged:
  - Telegram pill on the main screen only; the title row lets the title ellipsise so the settings icon is never
    pushed off screen (maintainer report). Seen on the OP9.
  - "Experimental" wording and one-screen Settings paths removed from 10 live strings (15 locales): notifications
    and errors now say "CallVault's settings".
  - The classic/new switch left Debug / Help and about. It is now ONE card under the Shizuku row on both screens
    (maintainer's pick, canvas row 4 "E"): "Try the new Settings layout" on classic, "Switch to the classic view"
    on the new one. Seen on the OP9 both ways. Classic layout changes by design here (card + Debug row gone).

## Rollback (maintainer requirement, 2026-10-21: "a big UI change … make sure we have a simple rollback")

Three layers, so a problem can be undone at whatever level it shows up:

1. **In the app, without an update.** The new screen is added **beside** today's, not in place of it. One setting
   chooses which opens: Debug ▸ "Use the classic Settings screen" (shown in both screens, so it is always
   reachable). A user hit by a bug switches back in one tap; support can tell them to. Default: the new screen
   only once it is verified on both phones; until then the classic one stays the default.
2. **No data changes.** Both screens read and write the **same preference keys**; nothing is renamed or migrated,
   and the Experimental switches keep their keys. Switching screens, or downgrading the app, loses nothing. The
   new screen's "which group was open" memory uses new keys and leaves the old ones alone.
3. **In git.** Every step is its own branch, merged with `--no-ff`, so one `git revert -m 1 <merge>` takes out
   one step. The file split (step 1) has no behaviour change and is checked by before/after screenshots, so
   it can stay even if the new screen is reverted.

The classic screen is deleted only in a **later release**, after the new one has shipped and drawn no reports.
🧪 BUILT 2026-10-31 on `chore/remove-classic-settings` (one release after 2.4.7): classic screen, Experimental
section and the layout switch card removed, `classic_settings_screen` key kept. Rollback:
`rollback/classic-settings-removal.md`.
Text that points users to a place in Settings (the notifications that say "Settings ▸ Experimental") must name a
path that exists in **both** screens: rename the classic screen's Experimental section to "Reliability" as well.

## What each page shows

Today's sections: the mode switch (at the top, outside every section), then Recording, Storage (with Retention
and Merge), Audio, Transcription & summaries, General (Visual, Privacy, Experimental, Updates), Debug, About,
and export/import at the very bottom (`SettingsScreen.kt:530-665`).

| Setting (today's place) | Recordings | Transcripts | Summaries | Main screen (all) |
|---|---|---|---|---|
| Recording mode: built-in or Shizuku (top) | ✔ first | | | ✔ first |
| Recording: phone calls, incoming/outgoing, ignored contacts, record-only-selected, start when they answer, file-name template | ✔ | | | ✔ |
| **App calls** (from Experimental ▸ VoIP): record app calls, which apps, start automatically, skip ignored contacts | ✔ new subsection "App calls" | | | ✔ |
| **Reliability** (from Experimental ▸ Resilience): resilient recording, recording away from Wi-Fi, USB debugging, keep Wireless debugging on, USB mode on unlock, pair again | ✔ new subsection "Reliability" | | | ✔ |
| Audio: source, codec, bit rate | ✔ | | | ✔ |
| Storage: where to save, folders, Drive and its schedule, details file, Hide Recordings, retention / storage cap, merge | ✔ | | | ✔ |
| "Save transcript and notes beside the recording" (Storage today) | ✔ (it is a file in the folder) | link ▸ | | ✔ |
| Transcription: model, language, run at, only while charging, ask language, ask before transcribing | | ✔ | | ✔ |
| Summaries: model, the summaries dialog switch | | | ✔ | ✔ |
| **Language** (shared on purpose, see below) | | ✔ editable | shown read-only, with a link ▸ | ✔ |
| General: look, toasts, vibration | | | | ✔ |
| Privacy: app lock, screenshots | | | | ✔ |
| Updates | | | | ✔ |
| Debug (logging, share log) | footer link | footer link | footer link | ✔ |
| About, support, licences, fork attribution | | | | ✔ (§7 attribution must stay reachable) |
| Export / import settings | | | | ✔ |

Every page view ends with **"All settings ›"**, which opens the full list. That is the way out for someone
looking in the wrong place.

## Rules the split must keep

- **One home per setting.** No setting is duplicated across pages. Where another page needs it, show a
  one-line link to it ("Backed up with the recording: Drive settings ›").
- **Transcription and summaries share one language setting.** Today's code says why: two separate language
  settings once produced transcripts in the wrong language (`SettingsScreen.kt:591-595`). The Summaries page
  must not get a language picker of its own.
- **The §7 fork attribution stays reachable** (GPLv3 terms, [[upstream-license-section7-attribution]]).
- **The wizard cannot be re-run** ([[new-features-consider-onboarding]]). Every setting the wizard sets
  still needs a home. `SettingDecisionsTest`, `ModeAwareUiTest` and `tools/device-checks/mode-switch-check.sh`
  enforce this. Keep them green and update them; don't weaken them.
- **Settings that change with the mode** (greyed out in Shizuku mode, and so on) must keep updating live
  on every page view. This was fixed in `bca782f5`.

## How to build it

- **One list, tagged by page; not four copies.** Each section or row gets a page tag:
  `SettingsScope { RECORDINGS, TRANSCRIPTS, SUMMARIES, ALL }`. Opening Settings passes the scope, and the list
  filters on it. Filtering one list keeps the guard tests meaningful and stops the pages drifting apart.
- **Entry points.** Every page's settings icon opens the same drawer today (`AppNavigationScreen.kt:291`;
  `onOpenSettings` in `HomeScreen`, `TranscriptsScreen`, `SummariesScreen`, `HubScreen`). Each will pass its
  own scope; the Hub passes ALL.
- **The open section is remembered per scope**, so Recordings doesn't reopen on a Transcription section.
  Saved section keys from today still need to map somewhere: today `SUB_EXPERIMENTAL` opens General, and
  it should open Recordings ▸ Reliability.
- **Split `SettingsScreen.kt` first** (3,461 lines; the coding rule is 800 at most) into one file per area:
  recording, storage, audio, transcription, summaries, general, about/debug. Do this as a **separate commit
  with no change in behaviour**, so the scope change lands on readable code and can be reverted on its own.

## Experimental removal: what has to change

- **The section:** `ExperimentalSubSection` (`SettingsScreen.kt:2274`) and its entry in `GeneralSection`.
  The rows move as listed above.
- **Text that points users to "Settings ▸ Experimental"**, in all 15 languages:
  - the notifications `notif_usb_lock_warning` and `notif_usb_check_unavailable`, and `notif_wd_required`
  - `offline_failed_needs_usb`
  - `settings_voip_recording_description`, which calls the feature experimental
  - `voip_recording_warning_message`, `settings_persistent_server_description` and
    `settings_handoff_persist_description`: check each one's wording
  - the old `whatsnew_146/147/150_where` strings. They are no longer shown, so there's no need to retranslate them.
- **The wizard step** `wizard_experimental_title` / `_subtitle` ("A feature still being proven"), and
  `WizardStep.EXPERIMENTAL`. That is the internal name, used in `SettingDecisionsTest:107`.
- **`tools/device-checks/mode-switch-check.sh`** navigates Settings ▸ GENERAL ▸ Experimental (lines 6, 26-29).
  It needs the new path.
- **The README** says "Opt-in, experimental" (line 30) and points to *Settings ▸ Experimental* (line 177). The
  README is held, so log these in `readme-impact-log` ([[readme-impact-log]]).

## Telegram pill

- Today the title row on every page shows the Support pill, and the Telegram pill too once the card has been tucked
  away (`HomeScreen.kt:900-925`, `CommunityPill`). Show the Telegram pill only on the Hub. Long-press to
  restore keeps working there.
- The transcription-progress pill keeps its place on every page. It takes the slot while a transcription is running.

## Order of work

0. ✅ **Design canvas** — picked 2026-10-21: option C with B's Shizuku row. Made 2026-10-21: https://claude.ai/artifact/Bt8twpoGHXX7hW85UFFsxH (A sheet, B page tabs, C folded groups; Transcripts, Summaries, title row). Waiting on the maintainer's pick. Two or three looks for a page's settings view: a header naming the page, the
   "All settings" footer, the Reliability and App-calls subsections, and the cleaner title row. The maintainer
   picks before any code ([[offer-design-options]]).
1. ✅ done 2026-10-21 (`refactor/split-settings-screen`): split by script into nine files, all 75 pieces
   byte-identical (29 private→internal); 29 before/after screenshots on the OP9 match. Split `SettingsScreen.kt` into files, with no change in behaviour. Run all tests, and compare before/after
   screenshots on the OP9.
2. 🧪 built 2026-10-21 (`feat/settings-per-page-screen`, merged into integration/next): new screen beside the classic
   one; Debug ▸ "Use the classic Settings screen", default ON. Classic screen re-screenshotted on the OP9: unchanged
   apart from the new Debug row. New screen seen by Claude on the OP9 from all four places (Shizuku mode). Single-group
   pages open their group. Not yet seen by the maintainer; built-in mode and the OP12 not tried. Add the new screen beside the classic one, behind Debug ▸ "Use the classic Settings screen" (classic is the
   default until verified), with the scope, the per-page entry points and the "All settings" link.
3. Remove Experimental: move the rows, change the strings in 15 languages, change the wizard text, update the
   tests and mode-switch-check.
4. Make the Telegram pill Hub-only.
5. On the phone:
   - Open Settings from each of the four places, in both built-in and Shizuku mode.
   - Run mode-switch-check.
   - Check that a saved old section key lands on the right page.
   - Write the release-note lines.

Each step is its own branch and commit ([[always-keep-rollback-available]]).

## Open questions (for when we start)

- **Debug footer:** should "Share a debug report" sit in every page's footer? I suggest yes. It is what
  support asks every reporter for, and it is easy to miss under All settings.
- **Search:** a search box over every setting. Probably later; "All settings" covers it at first.
- **Resilient recording on by default:** a separate decision, not part of this.
