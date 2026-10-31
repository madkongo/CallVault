# Backlog

Agreed work that is **not** started, so it does not get lost between sessions. Ordered by the value it
delivers, not by effort. Anything already researched lives in `capture-research-directions.md`; this
file is for decided product and engineering work.

Status key: 🔵 agreed, not started · 🟡 in progress · ✅ done (moved to the Completed section below)

**A section header is a claim about a release, so verify it against the tags rather than trusting it.**
Every ✅/🟡 below now names the release it shipped in. On 2026-08-14 three sections still read "not yet
on a device" / "awaiting device test" for work that had shipped in **v1.5.5**, two releases earlier —
they were written before that release was cut and never revisited, and reading them cost a session's
worth of wrong conclusions about what was pending. When a release is cut, reconcile this file:

```bash
# does <tag> contain the file that implements <entry>?
git ls-tree -r <tag> --name-only | grep -c '<TheClass>.kt'
```

Use `git ls-tree`, not `git cat-file -e` inside a shell loop — the latter's exit code interacts badly
with `&&`/`||` chains and silently reported the opposite answer while this was being checked.

---

## Current state — 2026-10-01

**Treat this block as the summary of record; the dated blocks lower down (2026-09-11, 2026-08-24, 2026-08-05)
are stale history kept only for context.**

**Public latest: `v2.4.5` (20451)** — PUBLISHED 2026-10-02 (Latest, not a pre-release) after the maintainer
ran it ~2 days on the OP12. 2.4.5 = everything that was in 2.4.4 (published 2026-09-29, pulled the same day
for a false "call was cut off"), plus the cut-off-false-positive fix, **Persian + Arabic** UI locales (first
RTL, shipped unreviewed by decision), the **vivo one-sided-call hint**, and three features (transcript/notes
`.md` sidecar #1, more filename templates #3, export/import settings #4). Next release must exceed 20451.

**Shipped since this file was last reconciled (2.3.0 → 2.4.5), so OFF the backlog:**
import an audio file (#37, 2.4.0), Shizuku speaker labels (#38, 2.4.1), the transcription stack (2.4.1),
record-only-selected-contacts (2.4.3), stuck "Call in progress" fix (2.4.3), and the whole 2.4.4/2.4.5
batch — version-aware settings (#1), pair-again (#43), cut-off rescue + "call was lost" (#4/#5),
regrant-card fix (#6), update popup + faster checks, manual "Check for updates" row, vivo app-call crash
fix, vivo far-side toggle (hint + SUPPORT doc), Samsung Wi-Fi-calling doc (#45).

**Open work — priority set by the maintainer 2026-10-01.**

*🔼 HIGHER priority — new feature requests (detail in the "Higher-priority feature requests" section below). The original #1/#3/#4 shipped in 2.4.5 — see the Completed section; the still-open ones are renumbered:*
1. **Record Button overlay** — a floating on-screen button to start/stop recording, plus an in-call note bubble that attaches a note to the call.
2. **View call history inside the app.**
3. ~~**"Select all" in multi-select mode**~~ — ✅ DONE: verified by the maintainer 2026-10-12, shipped in v2.4.6
   (`ff401b8c`). This line said "open" until 2026-10-31 although the entry below was already ✅; a status
   summary was given from it by mistake.

*🔽 LOWER priority — everything previously queued:*
- *Parked on a user/log:* VoIP mic re-take step 2 (#9 — gate on `isClientSilenced`, needs a Samsung log);
  Android-13 app-call mic fallback (#42 — decision pending).
- *Features:* "Test my setup" path-check; per-app VoIP support checked at runtime; a confirmation when a
  VoIP recording ends; Matroska `.mka` storage (#36); transcript reading design (#27, mirror176) + the
  transcript-sheet auto-height follow-up.
- *Engineering:* `AdbShell.ensureConnected` unbounded handshake on the record-start path; our captures
  don't register with `AudioService` record tracking; targeting Android 17 (API 37) will break mDNS
  discovery until we request it; a setting to stop CallVault managing Wireless debugging (#30 follow-up);
  split `AppPreferences` into per-domain interfaces.
  - **Already-listening adbd loopback probe (free, no Wi-Fi).** 🔵 At startup, detect devices where adbd
    already listens on loopback with NO Wi-Fi/WD — a minority of OEM/TV-derived or eng/userdebug builds
    bake `persist.adb.tcp.port` / `service.adb.listen_addrs` or drop `ro.adb.secure`. Check: `getprop
    persist.adb.tcp.port`, `service.adb.tcp.port`, `service.adb.listen_addrs` (via the existing shell
    path if we have one, else they are app-readable getprops), and attempt a `connect()` to
    `127.0.0.1:5555` (+ the baked port) while Wi-Fi is off; if it answers the adb handshake, use it as a
    zero-Wi-Fi foothold. Most phones return nothing — but it costs nothing and needs no new permission.
    Rationale + source (`daemon/main.cpp` tcp/vsock 5555 fallback, the prop gating) in
    `2026-10-23-wifi-free-adb-bootstrap-research.md`. Does NOT help OnePlus/OP12. Low effort, opportunistic.
- *Bugs:*
  - **Merge with another call fails for Drive-storage users** — a same-contact call that's already on Drive
    is never offered, and the dialog wrongly says "no other calls with this number" (detail in the "Merge
    can't see Drive-only calls" section below). Live in 2.4.3–2.4.5.
  - With the app lock (fingerprint/biometric) on, **screenshots of the app can't be taken** — a side effect
    of `FLAG_SECURE` and unwanted from a user's view (detail in the "App lock blocks screenshots" section below).
  - **Shizuku mode fails on HyperOS 3 (POCO X7 Pro)** — Shizuku READY but never hands back a recorder binder
    (HyperOS kills the user-service fork); standalone works. Two reporters, same device (detail in the
    "Shizuku mode fails on HyperOS 3" section below). Plus a standalone off-Wi-Fi loopback-arming flakiness
    on the same phone, and WD-toggling that looks like a fault to users.
  - **Greek transcribes into English** (#46) — ✅ VERIFIED 2026-10-12 (maintainer) — `el` added as a pinnable
    transcription language (`76c2c232`) + Greek UI locale added (`f881010c`). Ships in the next release; log it
    in [[release-history]] + the README impact log when it does (detail in the "Issue #46" section below).
  - **Samsung One UI turns USB debugging off by itself** (UserX, S24 FE) — breaks off-Wi-Fi recovery; CallVault only
    mirrors the system switch (not a CallVault bug). Suspect Auto Blocker (detail in the section below).
  - **"Active" but the call wasn't recorded** (HyperOS/Shizuku — UserW, UserZ) — OS kills/churns the recorder; the
    app trusts a stale binder and lies "Active". Fix = truthful health-gated status + rebind-at-call (RESEARCHED
    2026-10-07; see the section below + [[hyperos-shizuku-recorder-death-research]]).
- *Quality / housekeeping:* no instrumentation tests at all (`androidTest`); transcript lines whisper
  invents from noise (#32); F-Droid readiness; README stale since 1.5.5 + stale screenshots + dead
  `WD_DISABLE_WHEN_IDLE` pref + deliberately-broken CI signing.

---

## 🔵 Samsung Wi-Fi Calling: detect it and warn, plus the stray app-call file at hang-up — agreed 2026-10-25, not started

Raised by mac's S24 report (SM-S921U, A17, 2.4.7): phone calls over Wi-Fi Calling record only his side. That is
Samsung's #45 limit, not fixable. Detail: `2026-10-25-report-mac-samsung-s24-wifi-calling.md`.

1. **Detect Wi-Fi Calling and warn (Samsung behaviour, not brand).** 📐 The recorder runs as the shell, which
   holds READ_PRIVILEGED_PHONE_STATE and READ_PRECISE_PHONE_STATE (checked on the OP9 2026-10-25). So it can
   ask `ImsMmTelManager` for: `isVoWiFiSettingEnabled()` (on/off), `getVoWiFiModeSetting()` (Wi-Fi preferred,
   Mobile network preferred or Wi-Fi only) and `getRegistrationTransportType()` (registered over Wi-Fi now).
   The OP9's ServiceState already shows "IMS APN connected over WLAN, mIsIwlanPreferred=true". During a call,
   Samsung's AudioFlinger also logs `bearer=WIFI`, which confirms it on Samsung. Dead ends: the `siminfo`
   table (refused for the shell) and OEM keys such as OnePlus `vowifi_call_status1` (not portable).
   Use: a card saying "Wi-Fi calls record only your side; switch to Mobile network preferred", and a mark on
   a recording made over Wi-Fi instead of a silently one-sided file. Only where Wi-Fi calls lose the far
   side: the OP9 records Wi-Fi calls fine. Needs an onboarding decision. Test the three readings on the OP9
   (Wi-Fi Calling on, off, each preference). mac would be the first Samsung confirmation.
2. **Our bug: a stray 1 s `_voip` file + "the other app blocks capture" at every Wi-Fi-call hang-up.** Samsung
   keeps the mode at IN_COMMUNICATION during a Wi-Fi call. Telecom drops the call about 1.5 s before IDLE, so
   `VoipTelephonyGate` lets an app-call start through. With a minimum length of 0 the file is kept. Fix: no
   app-call start while the carrier recorder is still running, or for a few seconds after a carrier call ends.
   Same branch as item 1.
3. Lead, unexplored: Samsung opens a REMOTE_SUBMIX `crwfcmix:<n>` (16 kHz) when our VOICE_CALL capture starts
   during a Wi-Fi call. It may be the hook Samsung's own recorder uses.

---

## 🔵 Android 17 — researched 2026-10-20, not started

Research: `2026-10-20-android-17-call-recording-research.md` (📐 desk research; no Android 17 phone).
Call audio is unchanged; the transport around it is what changed.

| # | Item | Size | Notes |
|---|---|---|---|
| 1 | **Wireless debugging read-back is wrong on 17**: "on" no longer means reachable, so an untrusted network waits the full mDNS timeout again (#39) | S-M | `AdbShell.kt:332-340`, `WirelessDebuggingOffCause`, debugging-switches model. Needs a 17 phone, or a reporter's log. |
| 2 | **vivo fallback `BypassedAudioRecord` breaks on 17** (fields and `native_setup` changed) | M | Use `mChannelMasks` and the new signature at API ≥ 37. Needs a vivo on 17 to test. |
| 3 | Pairing fallback to the resolved host when the loopback probe finds nothing | S | Hedge for one fork's QPR1 report. |
| 4 | Shizuku help text: name a fork that works on 17 | S | Official Shizuku's app list is empty on 17. |
| 5 | targetSdk 37 prep: `ACCESS_LOCAL_NETWORK` + `FLAG_NO_PICKER` on every discovery, `USE_LOOPBACK_INTERFACE`, background playback | M | Merges with "Targeting Android 17" below. |
| 6 | **Developer verification (global 2027)**: register as an open-source developer, or accept the advanced install flow | decision | The maintainer's call. Check whether our shell `pm install` updater counts as exempt. |
| 7 | Watch: adbd loopback restriction proposal (tracker 526109803, 541312863) | watch | Would break on-device ADB for everyone. |
| 8 | Conscrypt 2.5.2 → 2.7.0 (16 KB pages) | S | |

---

## 🧪 Auto-approve "Allow wireless debugging on this network?" — feature request 2026-10-21, BUILT, merged into integration/next; untrusted hotspot ✅ VERIFIED 2026-10-21 (maintainer); restart + Android 17 still 🧪

Dev-note: `2026-10-21-auto-approve-new-wifi-network.md` (📐 read from AOSP 16 + 17; nothing built). The shell uid
holds `MANAGE_DEBUGGING`, so our daemon (or Shizuku's server) can call `IAdbManager.allowWirelessDebugging` for
the current network instead of writing `adb_wifi_enabled=1`. Android then never shows the prompt. No root, no
accessibility service. Works only while a shell process is alive (USB debugging on). Size S-M: reflection for the
16 and 17 signatures, BSSID/SSID read in the daemon. Suggested: opt-in switch in Recordings ▸ Reliability,
allow-once (not "always"), behind the existing `WirelessDebuggingEnableGate`. Of the six repos the requester
suggested, only project-SIMPLE handles this prompt, by clicking it with an accessibility service.

---

## 🔵 Settings per page — PLANNED 2026-10-20, not started, no date

Plan: `2026-10-20-settings-per-page-plan.md`. Confirmed by the maintainer: Settings opened from a page shows
that page's settings (the main screen shows everything); the Experimental section goes and its switches move,
**with their defaults unchanged**; the Telegram pill shows only on the main screen, and Support stays as it is.
Design picked 2026-10-21: option C (folded groups) with B's Shizuku switch row; the new screen ships beside the classic one with a one-tap way back (rollback section of the plan).

---

## 🔵 From the forks review — added 2026-10-20, not started

Source: `2026-10-20-forks-review.md` and the per-fork files beside it. 📐 code read only, nothing built.
**Credit the authors.** When we adopt a fork's code, keep them as the commit author, or add a `Co-authored-by:`
trailer when we rewrite their idea. Name them in CHANGELOG and the release note, e.g. "thanks to @kabasik007".
Their code is GPLv3 like ours, so taking it is allowed.

| # | Item | Credit | Notes |
|---|---|---|---|
| 1 | **Security: `sendBinder` caller check.** Any app can hand us a fake recorder and get new recordings' output fd. Add the shell/system/own-uid check `sendHandoff` already has (`RecorderBinderProvider.handleSendBinder`). | Vas Vakhulenko (@kabasik007), `7211c263` | 🧪 VERIFYING — fixed 2026-10-20 (`3907971e`, merged into integration/next, Co-authored-by credit). Unit test; on the OP9 the daemon's binder was still accepted after install. Not shown: a real other app being refused (no rogue app to test with). |
| 2 | **Direct capture: recover from a dead AudioRecord.** `readLoop` spins silently on `read <= 0`, so the rest of the call is lost. Log, reopen, pad with silence, stop cleanly if reopening fails. | Found while reviewing Luis (@lgarvia), `2f988633` | ⛔ NOT NEEDED (checked 2026-10-20). Direct capture uses a real `AudioRecord`, and Android's native client restores an invalidated record by itself (`restoreRecord_l` in `obtainBuffer`), so a route change does not kill it. The resilient path, which bypasses that client, already re-acquires (`HandoffReceiver`, v2.3.0). What is left is only a silent spin if Android's own restore fails (audioserver gone), which is rare and not worth a change. |
| 3 | **SAF delete that fails keeps the catalog entry.** | Vas Vakhulenko (@kabasik007), `6b341fc1` | 🧪 VERIFYING — checked 2026-10-20: real, and nothing else covers it (the list reads only the catalog; `RecordingsRepository` already guards the user's own Delete the same way, the Drive-only paths did not). His commit cherry-picked as-is (`1a208dd7`, author kept). Unit test only; a refused delete cannot easily be produced on a phone. |
| 4 | **Decode time limit.** Give up after 120 s with no decoder output and save a clear error. | @slman890, `8534f237` | No user report; a guard. |
| 5 | **Import a model from a file**, next to Download. | @sanju0070, `0cfcd092` | Take the per-model writer lock, ask before deleting a partial download, check free space, run in a worker, translate the strings. |
| 6 | **Ukrainian** UI and transcription. | Vas Vakhulenko (@kabasik007), `02e0e691`…`c34d2176` | 🧪 VERIFYING — done 2026-10-20 (`feat/ukrainian`): his 6 commits cherry-picked with authorship, then 11 strings added, 24 shortened ones retranslated, the stale Shizuku text corrected, summary prompt + uk/ru split. Seen rendering on the OP9. Needs a native speaker's look and a real Ukrainian call transcribed and summarised. |
| 7 | **SIM label per call** (dual SIM). | @slman890, `8534f237` | Idea, no user request. Add the account columns to our call-log lookup, not their DB change. |
| 8 | **Phone app / default dialer: an option again.** Dialer mode was dropped in 2026-07; the maintainer now considers it an option (2026-10-20). | @slman890, `4051f48c` (about 1,100 lines, emulator-tested only) | Decide first: whether to do it, and whether the dialpad may be the opening screen (theirs makes it). Use their branch as a reference, and see [[dialer-mode-status]]. |
| 9 | **Android 12: the vendor-constructor bypass cannot work.** `BypassedAudioRecord.kt:123` calls `AttributionSource.myAttributionSource()`, which exists only from API 33; on 31–32 it throws `NoSuchMethodError` (caught by both callers, so no crash), so a phone whose own `AudioRecord` constructor crashes (the vivo case) cannot record on Android 12. Fix: on 31–32 build it with the public `AttributionSource.Builder(uid).setPackageName(…)`, and check the Android 12 `native_setup` signature. | Found by our lint run 2026-10-22; lead from @SMMH1987's CI (`7193a7ed`) | 📐 code read only. Parked 2026-10-22 by the maintainer: no Android 12 phone to verify on. Needs an affected Android 12 user, or at least the Android 12 emulator showing the bypass builds a record. |
| 10 | **Polish and Russian plurals lack `few`/`many`** (8 strings each, lint `MissingQuantity`), so some counts read ungrammatically ("2 месяцев"). Check Ukrainian at the same time. | Our lint run 2026-10-22 | Cosmetic. Parked 2026-10-22 by the maintainer. |

---

## 🔵 KK — SenseVoice for transcription + Qwen/other small GGUF for summaries — requested 2026-10-31, not triaged

User **KK** asked: "Any plans to add SenseVoice as a built-in/catalog ASR option? And for summaries, could the
backend be opened up to Qwen or other smaller GGUF models instead of being locked to Gemma? SenseVoice + Qwen
would be nice."

Nothing decided, nothing researched. Notes for whoever picks it up:
- **SenseVoice** is a different engine, not just another model file. Transcription today is whisper.cpp only
  (`TranscriptionModel` catalog, `WhisperNative`). SenseVoice would need its own runtime (e.g. sherpa-onnx)
  and has no word timings like Whisper's, which our speaker turns, seam splitting and chunking lean on.
  Its language list is narrow (zh/en/yue/ja/ko); **no Hebrew**. Check the language fit before anything.
- **Qwen / other GGUF for summaries:** the runtime is already llama.cpp, so this is mainly the catalog and
  prompt side. `SummaryModel` has one entry (Gemma 4 E2B). Per-model risk: chat template, the GBNF grammar
  (`SummaryGrammar`), and summary quality in Hebrew and other pinned languages. A free "any GGUF" import
  would need a template check. Test candidates first on the desktop harness (memory
  `llama-desktop-harness`).
- Both add visible options, so they need an onboarding decision (see [[new-features-consider-onboarding]]).

## 🔼 Higher-priority feature requests — agreed 2026-10-01, not started

Maintainer-prioritised above the older backlog. Each needs a design pass before code (and, where it adds a
visible option, a decision on onboarding — the wizard can't be re-run, see [[new-features-consider-onboarding]]).

*(The original #1 sidecar, #3 filename templates and #4 export/import shipped in 2.4.5 — see the Completed
section. The still-open requests are renumbered below.)*

1. **Record Button overlay.** A floating, draggable on-screen button (bubble) to start/stop recording
   manually, as an alternative to the automatic call trigger. Needs the draw-over-other-apps permission
   (`SYSTEM_ALERT_WINDOW`); decide when it shows (always / during calls / toggle), and how it maps to the
   capture paths (manual start on the current call). Useful where auto-start can miss or the user wants
   explicit control.

   **Related idea — in-call note bubble (user request, 2026-10-01):** the same floating bubble, shown
   *during a call*, that lets you jot a quick note which is attached to that call's recording. We already
   store a note per recording (`RecordingExtrasRepository.saveNote`, keyed by the recording's displayName)
   and now also mirror it to the `.md` sidecar — so the missing piece is only the in-call overlay UI plus
   knowing which recording the live call will become (the displayName is formed at record-finish, so the
   note would be held against the active call/session and written once the recording is published). Pairs
   naturally with the Record Button overlay — one bubble, two actions (record + note). `SYSTEM_ALERT_WINDOW`
   again. Consider: what if recording is off/failed for that call (keep the note as a standalone jot?).

2. **View call history inside the app.** Show the device call log in-app, ideally marking which calls have a
   recording (we already read the call log via `CallLogReader` for cut-off matching, and hold
   `READ_CALL_LOG`). Decisions: list design, correlation to recordings, and what a tap does.

3. **✅ SHIPPED in v2.4.6 — "Select all" in multi-select mode (user request, 2026-10-04).**
   > **✅ VERIFIED 2026-10-12 by the maintainer on device ("feels fine", select/deselect + delete confirmed).**
   > Merged into `main` 2026-10-12 (`ff401b8c`).
   > First cut was an app-bar select-all⇄clear icon (`40c1395b`); the maintainer found the lone icon
   > unclear and picked **Option D** from the mockup (`claude.ai/artifact/NMc31h1BbJPGgZy8ERZTe6`):
   > redesigned in `4e43c8c6` to a **"Select all" row pinned at the top of each list**, shown only while
   > selecting, with a tri-state box (all / some / none → "Select all" ⇄ "Deselect all") that lines up over
   > the per-row ticks. "All" = the currently filtered/visible *selectable* rows (Recordings =
   > `filteredRecordings`; Transcripts/Summaries = DONE rows via `Groups.selectableNames()`).
   > **Also fixed the reported bug:** deselect/clear no longer drops out of selection mode — the bar stays
   > up at zero picked and only the back arrow leaves. `LibrarySelection` gained an `active` flag (restored
   > with the set); recordings path gained `recordingsSelecting`; shared `LibrarySelectAllRow`; Share/Delete
   > inert at zero picked. No new strings. **Installed on OP9 + OP12 2026-10-12** (combined build; see note at
   > end). Verify on device: enter selection mode on each of the 3 lists → a "Select all" row appears at the
   > top; tap it (fills all), tap again ("Deselect all" → 0 picked, bar stays), back arrow leaves.

   When a library page is in selection
   mode (the "N selected" app bar with share/delete), offer a one-tap *select all* so the user doesn't
   have to tap every row — e.g. for a bulk delete. Small and additive: the selection state is
   `LibrarySelection` (section + `Set<String>` of names, `ui/navigation/LibrarySelection.kt`), so this is a
   new `selectedAll(section, allNames)` on that type plus a select-all (and ideally select-none/toggle)
   action in the selection bar (`ui/screens/LibrarySelectionUi.kt` / `HomeScreen.kt`). The "all names" must
   be the *currently filtered/visible* list for that section, not every recording, or search + select-all
   surprises. A toggle that flips to "clear all" once everything's picked is the usual pattern. Applies to
   every list that has selection mode (recordings, Transcripts, Summaries), so route it through the shared
   bar rather than one page.

---

## 🧪 Merge can't see Drive-only calls — FIXED (Merge-Drive-only), merged into `main` 2026-10-12 — reported 2026-10-02 (user "gene rator")

> **🧪 FIXED and merged into `main` 2026-10-12 (`06bf6f62`; code `1d8eb92f`).** `MergeCandidates.of` no longer
> requires a local file; `MergeService` downloads Drive-only parts to a cache dir, merges, and cleans up.
> Device-tested on both phones; stays 🧪 until a real affected user confirms (this is the "Merge-Drive-only"
> item, shipped on the maintainer's accepted risk). Original report kept below.

**Report:** two recordings with the same contact (one outgoing 09:40, one incoming 09:43, right after
hanging up). "Merge with another call" on one said **"There are no other calls with this number on the
phone."** Screenshots confirm the same contact ("Stephen …") and that **both rows carry the cloud badge**
(stored on Drive). Reporter on **2.4.3 (20430)**, OnePlus CPH2653, Android 16, **Storage target: DRIVE**.

**Root cause (confirmed in code):** the merge-candidate filter `MergeCandidates.of` requires every candidate
to be physically on the phone — `other.localUri != null` (`data/merge/MergeCandidates.kt:44-47`), because the
stream-copy merge reads encoded frames via MediaExtractor on a local file. **In DRIVE mode the local file is
deleted after upload and the catalog's local reference is cleared** (`RecordingCopyWorker.kt:84` deletes the
device copy; `RecordingCatalog.markDrive` → `clearLocal` at `RecordingCatalog.kt:88-89`), so
`RecordingItem.localUri` is null for *every* Drive-synced call (`RecordingsRepository.toItem`, `localUri`
fed straight from the catalog row). Result: with DRIVE storage, all candidates are filtered out regardless
of how well the contact matches. The merge menu still opens because `canMerge` only checks `isImported`, not
`localUri` — so the user reaches a dialog that then reports zero candidates. **Live in 2.4.3 through 2.4.5
(`MergeCandidates` unchanged on `main`).** Two wrongs: the feature doesn't work for Drive users at all, and
the message claims the calls don't exist when they do (just not locally).

**Secondary bug the trace turned up (masked here, bites others):** the key is `contactName ?: number` with
**no number normalization** — `number` is parsed raw from the filename (`RecordingsRepository.parseName`,
~:604-613). An incoming `+35679…` and an outgoing `079…` to the same person only key-match if PhoneLookup
resolves *both* to the same `contactName`; if lookup misses (READ_CONTACTS denied, or one format not stored)
the raw numbers differ and the merge fails **even for two local calls**. No `normalizeNumber`/E.164/last-N
canonicalisation anywhere in the key path, unlike the record-only-selected matcher (last-9-digit).

**Fix direction (needs a design pass + decision):**
- *Make merge work for Drive users:* when a same-contact candidate is Drive-only, **download it to a temp
  local file first** (reuse the existing Drive-restore path — un-merge/restore already pulls files back from
  Drive), run the normal merge, then re-upload the merged result and clean up temps. Needs network + space;
  show progress; handle offline. This is the real fix.
- *At minimum, stop the lie:* if same-contact calls exist only on Drive, say so ("the matching calls are on
  Drive; merging needs them on the phone") instead of "no other calls with this number" — and/or list them
  disabled with a "download to merge" affordance.
- *Separately, normalise the key:* compare on a canonical number (last-N digits / E.164) before falling back,
  so in/out pairs match even when contact lookup misses. Small, helps all storage modes.

Recommend: ship the message fix + number normalisation first (small, honest), then the download-then-merge as
the full fix. A new visible path → onboarding not affected (no wizard step), but the download cost is a UX
decision.

---

## ✅ App lock blocks screenshots — FIXED & VERIFIED 2026-10-12, merged into `main` — reported 2026-10-02

> **✅ VERIFIED 2026-10-12 by the maintainer; merged into `main` (`06bf6f62`; code `6e84d2fe`).** Added an
> "Allow screenshots" sub-setting under the app lock (default off): `FLAG_SECURE` is applied only when the
> lock is on AND screenshots are not allowed, re-applied live on toggle, and honoured by `ShareImportActivity`
> too. Original report kept below.

**Report (user):** when the app lock (fingerprint/biometric) is enabled, screenshots of the app can't be
taken. From a user's perspective there's no reason it shouldn't work — treated as a bug.

**Cause (confirmed in code):** `MainActivity.onResume` applies `FLAG_SECURE` to the window whenever
`AppLock.isEnabled(this)` (`MainActivity.kt:243-244`; cleared at :246). `FLAG_SECURE` is the single flag
that both blanks the Recents thumbnail **and** blocks the OS screenshot — Android gives no way to keep one
without the other. `ShareImportActivity` sets it too (:228), but only for the brief share-import screen.

**Why it's there:** deliberate privacy hardening — a locked-behind-biometric app shouldn't leak its
contents (contacts, call text) to the Recents preview or a casual screenshot. So this is working as coded,
not a regression; the tension is that it also stops the *owner* screenshotting their own app.

**Options for the fix (needs a decision):**
- *Decouple the two concerns:* keep hiding the Recents thumbnail (e.g. `setRecentsScreenshotEnabled(false)`
  on Android 13+ / an exclude-from-recents approach) but drop `FLAG_SECURE` so manual screenshots work.
  Caveat: pre-13 has no clean split, and even on 13+ this changes the privacy posture.
- *Make it a setting:* "Allow screenshots" toggle (default off to preserve today's behaviour), that clears
  `FLAG_SECURE` when on. Simplest, keeps the secure default, puts the choice with the user.
- *Scope it:* only apply `FLAG_SECURE` to screens that actually show sensitive content, not the whole app.

Recommend the setting unless we want to rework per-screen. Small change either way; the work is the
decision + onboarding note (a new visible option — see [[new-features-consider-onboarding]]).

---

## 🐞 Shizuku mode fails on HyperOS 3 (POCO X7 Pro) — reported 2026-10-03 (users "doctor divago" + "voss")

**Two users, same device + ROM:** POCO X7 Pro (`2412DPC0AG` / `rodin`), **HyperOS OS3.0.302.0, Android 16**
(`BP2A.250605.031.A3`), on 2.4.5. Both report Shizuku mode doesn't record; divago: "can't use Shizuku,
when I start a call I get an error — but using CallVault directly (standalone) works fine." voss's
screenshot shows the system notice *"Shizuku se estrelló recientemente"* (Shizuku crashed recently).

**Cause (confirmed in code + logs):** Shizuku is running and CallVault is permitted — report shows
`Privileged mode: SHIZUKU`, `Status: READY` — but Shizuku never hands CallVault a recorder. The log:
`Shizuku did not hand back a recorder binder within 10000ms` (twice), `unbindUserService failed: Permission
Denial: removeUserService from pid=… requires permission`, then `Shizuku cannot serve a recorder right now:
NO_PERMISSION`; `Binder connected: false`, `Host uid: ?`. So `Shizuku.bindUserService` (`ShizukuBackend.kt:206`)
is accepted but the user-service binder never arrives (`RecorderBackend.ensureShizukuRunning` 10 s poll,
`:391`). On HyperOS the forked user-service process (`com.baba.callvault:recorder`) is killed or the
ADB-started Shizuku server can't manage user services for this app. Note `Status: READY` is only
`isRunning && hasPermission` (`ShizukuStatus.of`) — the *runtime* permission doesn't cover user-service
management, so READY can be true while the bind still can't complete. **Not a CallVault capture bug** — it
never gets a recorder. **ShizuCallRecorder is not the cause** — voss uninstalled it and it still failed.

**Workaround (correct outcome for both):** use CallVault's own **Standalone** (built-in ADB) mode — it runs
the daemon over the embedded transport, no Shizuku user-service fork. It's also the fuller mode (Shizuku is
scrcpy-only: no VoIP / offline / resilient / speaker attribution). So nothing is lost by staying standalone.
Possible in-app improvement: when the Shizuku bind times out, say so and point to HyperOS battery/"MIUI
optimization" restrictions or to Standalone, instead of a generic error. 🧪 Told divago to disable battery
restrictions on Shizuku — **awaiting his feedback** (2026-10-03).

**voss also hit a Standalone off-Wi-Fi (loopback) issue — same device.** With Offline recording (loopback)
ON, the launcher armed the loopback listener, failed twice (`NOTHING_LISTENING_ON_LOOPBACK`, ~6 s each),
then **succeeded on attempt 3** (`Connected over loopback tcpip :47964 (works off-WiFi)` → daemon → "a
recorder connected"), ~40 s total. His "wireless debugging keeps stopping" is by design: offline mode
*borrows WD to re-arm the loopback listener then turns it back off* (`Borrowing Wireless debugging … it
goes back off straight after`) — not a fault, but alarming and unexplained to the user. Worth: (a) a note/UI
hint that WD toggling is expected in offline mode; (b) look at why the first loopback arms fail on HyperOS 3
before one sticks. See [[loopback-tcpip-offwifi]], [[private-report-note20-no-wifi]]. If he's mostly on
Wi-Fi, turning Offline recording OFF stops the WD churn.

---

## ✅ Issue #46 — Greek: transcription comes out English + a Greek UI translation offer — VERIFIED 2026-10-12 on `main` (reported 2026-10-04)

> **✅ VERIFIED 2026-10-12 (maintainer):** both parts confirmed. (1) the `el` transcription fix — `el` added to
> `TranscriptionLanguageChoice.SUPPORTED` + a `transcription_language_greek` label in every locale
> (`76c2c232`); (2) the full Greek UI locale `values-el/` — 1007 strings/plurals/arrays, exact key+placeholder
> parity (`f881010c`). Both on `main`, not yet released. **Still to do:** ships in the next release — log it in
> [[release-history]] and the README impact log then; the reporter may still send native wording polish for
> `values-el/`. Detail below is kept for the root cause.

**Two things from issue #46.**

**1. 🐞 Greek audio transcribes into English (romanised), not Greek.** The reporter: "the transcriber does
understand Greek content, but translates it into English." **Not whisper translating** — `whispercv.cpp:246`
hard-sets `params.translate = false`. The real cause: **Greek (`el`) is not a pinnable transcription
language**, so it falls back to auto-detect, and whisper auto-detect on a non-Latin script spells it out in
Latin/English (the app already warns about this for he/ar/ru/zh in `transcription_language_auto_hint`). The
user can't work around it — `el` isn't in the picker. whisper.cpp supports `el`; pinning it (with
translate=false) transcribes in Greek. See [[pin-the-transcription-language]].

*Fix — small, additive, 3 spots:*
- `transcription/TranscriptionLanguageChoice.kt` → add `"el"` to `SUPPORTED`.
- `ui/common/TranscriptionLabels.kt` `languageOf()` → add `"el" -> R.string.transcription_language_greek`.
- Add `transcription_language_greek` ("Greek") to base `values/strings.xml` (+ the other 13 locales).

Same gap applies to any whisper language not in `SUPPORTED`; Greek is the one reported. 🧪 Confirming the
output is correct Greek needs a real Greek call (we don't speak Greek — [[hebrew-cannot-clear-a-quality-change]]).

**2. 🌐 A volunteer offered to translate the UI into Greek.** Create `app/src/main/res/values-el/` mirroring
the 7 base `strings_*.xml` files (~991 translatable strings + 9 plurals + 6 string-arrays; skip the 22
`translatable="false"`; keep names + placeholders). No code change needed — `generateLocaleConfig = true`
(app/build.gradle.kts:282) auto-adds `el` to the language picker and the display name resolves from the
locale. German (`values-de`) is a complete LTR locale to use as the template. Greek plurals are two forms
(`one`/`other`). If this lands, log it in [[release-history]] and the README impact log ([[readme-impact-log]]).

---

## 🐞 Samsung One UI turns USB debugging off by itself — reported 2026-10-05 (user "UserX")

**Report:** on a **Galaxy S24 FE (SM-S721B)**, One UI / Android 16 (Iran), 2.4.5, STANDALONE + Offline(loopback)
+ Resilient, the **USB debugging toggle keeps turning off by itself** and the user re-enables it by hand.
Recording worked all day (3 calls over handoff, 144 in the library); then at **18:41 the daemon binder died**
and the keep-alive couldn't recover — `loopback tcpip :50470 unavailable (unarmed/refused)` ×5; snapshot
`init.svc.adbd: STOPPED`, `USB debugging: OFF`, `Wireless debugging: false`, `Binder connected: false`.

**Cause (confirmed in code + logs):** off-Wi-Fi/loopback recovery needs USB debugging ON (the listener lives in
adbd — [[debugging-switches-model]]); with it off and away from Wi-Fi there's no transport to relaunch the
daemon. The in-app "USB debugging" toggle (Settings ▸ Resilience) is **not internal** — it reads/writes the
SYSTEM `adb_enabled` via WRITE_SECURE_SETTINGS and a `ContentObserver` mirrors the live value
(`SettingsScreen.kt:2583-2614`). **CallVault never writes USB debugging off on its own** (only on a user-confirmed
tap; it manages Wireless debugging, not USB). So the switch flipping off = **One UI reverted `adb_enabled` to 0**
and the observer reflected it. Opposite of #24's unreproduced "turned itself ON". Known One UI transport-kill
family ([[adbd-transport-and-oem-quirks]], [[screen-off-adbd-kill-and-mitigations]]); this is the S24 FE those
memories are about.

**Suspected Samsung causes (🧪, not reproducible on OP9/OP12):** (1) **Auto Blocker** (Settings ▸ Security and
privacy ▸ Auto Blocker) blocking/disabling USB debugging + ADB — top suspect; (2) an app-written `adb_enabled`
may not "stick" on One UI (enable it natively in Developer options); (3) reboot/Secure-startup reset.

**Status:** 🧪 told him to check Auto Blocker 2026-10-05 — **awaiting his reply.** Also advised: Default USB
Configuration → "Charging only"/"Debugging only", disable "adb authorization timeout".

**Possible in-app improvement (not started):** when the observer sees `adb_enabled` revert to 0 while the recorder
needs it, show a Samsung-specific hint ("your phone turned USB debugging off — check Auto Blocker") instead of the
generic readiness notice. Do NOT auto-re-assert `adb_enabled` — that starts a tug-of-war with the OS.

---

## 🧪 Issue #49 — sidecar files (.json details, .md transcript) never reach Drive with "Immediately" sync — MERGED into `main` 2026-10-12, APPROVED for next release (reported 2026-10-10, eduardozacour)

> **🧪 Merged into `main` 2026-10-12 (`06bf6f62`; code `513933e0`); APPROVED for the next release — the
> maintainer accepts the risk ("#49 will be merged and released as part of the new version and if there will
> be an issue we will address it").** Not device-confirmed by us, so it stays 🧪 (not promoted to ✅) — the
> decision is to ship it and fix forward if a user reports trouble, not that it was verified working. On both
> phones. (`main` is still local — nothing pushed.) The "fix direction" below is kept for the rationale; it was
> implemented as described (see commit).

Sharp, code-level report (he read the tree at 9933d92). **All three claims verified in the current code:**

1. **IMMEDIATE sync uploads the audio only.** `StorageRouter.route()` (`StorageRouter.kt:55-67`) enqueues
   `RecordingCopyWorker` with `KEY_SRC = localUri` / `KEY_NAME = displayName` — the audio file. Nothing copies
   the `.json` details sidecar or the `.md` transcript.
2. **The sweep is the only thing that copies the whole folder, and IMMEDIATE cancels it.**
   `SyncScheduler.apply()` (`SyncScheduler.kt:53-56`): `if (LOCAL || IMMEDIATE) cancelUniqueWork`. So in
   IMMEDIATE the sidecars have no path to Drive at all. DAILY/WEEKLY copy them because the sweep walks the folder.
3. **DRIVE-only orphans them on the device.** `KEY_DELETE_LOCAL = (target == DRIVE)` (`StorageRouter.kt:60`)
   deletes the local audio after upload → the `.json`/`.md` are left on the phone with no recording beside them.
4. **Bonus (also verified):** deleting a recording removes the audio + the **transcript** sidecar
   (`TranscriptSidecar.sidecarNameFor`) but NOT the `.json` details file (`RecordingsRepository.kt:368-382`
   matches only `displayName` or the transcript sidecar) → orphan `.json`.

**Fix (implemented in `513933e0`):** in IMMEDIATE mode, route the sidecars too — the `.json` at carrier/VoIP
publish and the `.md` after transcription (which runs post-upload, so it has its own trigger) via
`StorageRouter.routeSidecar()`; `RecordingCopyWorker` gained `KEY_IS_SIDECAR` (skips `markDrive` + the error
nag). The `.json` is now also matched in the delete cascade (4) via `MetadataSidecar.sidecarNameFor`.
🧪 build-reproducible from code; not device-confirmed by us — shipping on the maintainer's accepted risk.

---

## 🧪 Issue #52 — long calls draw no waveform and can't be scrubbed — FIXED, MERGED into `main` 2026-10-13, installed on both phones, device-verified on OP9 (reported 2026-10-12, StateConscious)

> **🧪 FIXED (both parts), MERGED into `main` 2026-10-13 (`3a05cd93` + `57ad1e35`; merge commit on `main`).
> Installed on both phones; recorder confirmed back up after install-over (OP9 `:recorder`, OP12 `app_process`).
> `main` is still local — nothing pushed.** Device-verified on the OP9 by Claude (maintainer-delegated: "proceed
> with the plan" → "merge + install"). Stays 🧪 — the reporter is on an S24 Ultra, so like #49 the real
> confirmation is the reporter's, not ours. Awaiting: reporter confirms on the S24U.

**Report.** S24 Ultra, Android 16, v2.4.5. A VoIP recording shows a flat line instead of a waveform and the
timeline can't be tapped/dragged to seek (only the 10 s skip buttons work); total duration is correct and
playback from the start is fine. A 1-min cellular call is fine. Follow-up from the reporter settled it:
a **19-min Zoom call works, a 41-min one doesn't — "so it seems to be length related,"** and the attached log
showed `CV:RecordingExtras: Could not draw …_voip-MAX_…ogg: Failed to allocate … until OOM`.

**Root cause — not VoIP, it's length.** The waveform was decoded **whole** into memory
(`RecordingExtrasRepository.waveform` → `AudioDecoder.decodeToMono16k`, no length cap) before being reduced to
240 bars. Cost grows with length (~9.6 MB/min of 16 kHz float, more during decode), so a long call throws
`OutOfMemoryError`, which `runCatching` swallowed into an **empty peak list**. Empty peaks give both symptoms:
(1) `WaveformBar` draws its resting line, and (2) **the waveform *is* the scrubber** — the tap/drag gesture was
only attached in the non-empty branch, so with no bars there was nothing to seek with. The 10 s buttons use the
same `MediaPlayer.seekTo`, which is why they still worked and proved the OGG/Opus container is seekable. A
cellular call of the same length fails identically; VoIP vs carrier was a length confound.

**Fix (two commits):**
1. **`3a05cd93` — decouple scrubbing from peaks.** Moved the tap/drag `pointerInput` from the drawn bars to the
   outer `Box` in `WaveformBar.kt`, so the resting line is a scrub target too. A long call is seekable even
   while its shape is still computing, or if it never decodes.
2. **`57ad1e35` — bound the decode.** New `Waveform.PeakAccumulator` places samples into bars **by position in
   time**; `RecordingExtrasRepository.drawShape()` feeds it the same bounded time-windows transcription already
   uses (`ChunkPlan.plan(totalMs)` + `AudioDecoder.decodeRange`). Peak memory now rests on one ~6-min window
   whatever the call length. A container with no declared duration falls back to the one-shot `reduce` (only
   short clips lack a duration). The swallowed error's type is now named in the log. Unit tests added
   (parity with `reduce`, time-placement, overlap-safety, edge cases); full suite green.

**Device verification — OP9, 2026-10-13 (release build install-over, WSS re-granted).** Pushed a generated
**52:22** OGG/Opus (past the reporter's 41-min threshold). (1) **Phase 1:** while the shape was still a resting
line, tapping it at ~60% jumped playback from 0:02 → **31:42** — the flat line scrubs. (2) **Phase 2:** the logs
show the decode running in **5-min windows** ("300013 ms" chunks) through to the tail, **no OOM, no "Could not
draw,"** and the screen then rendered a **full waveform** (bars, played portion filled) for the 52-min call —
the exact length that OOM'd before. Test file + cached shape deleted afterward. *Not installed on the OP12 yet —
it dropped off USB mid-session.*

**Deferred (Phase 3, optional):** distinguish failed-vs-pending waveform state in the UI. Unnecessary now that
the OOM is gone; the clearer log covers diagnosis.

---

## 📋 Field reports 2026-10-10 — abdo kamel, foxcam, alex_812

### 🐞 abdo kamel — short calls save empty recordings (Shizuku on a low-end Redmi) — self-reported as "low RAM"
Redmi `gale` (23106RN0DA), Android 13, MIUI V14, Egypt, 2.4.5, **SHIZUKU** mode, storage BOTH. Log report (12).
NOT a RAM kill (no lmkd/OOM; recorder pid stable). Real cause: **Shizuku can't do direct AudioRecord**, so the
daemon tries direct (`source=4`), it "failed to enter RECORDING state", and falls back to **scrcpy — a ~2 s cold
start** (extract + `app_process` + 56 socket retries). His calls are tiny (the one that recorded was ~3 s after
answer): the call ended ~0.2 s after the muxer started → **98-byte, 0-frame file** that won't play. Other outgoing
calls to short-code `800` entered standby but never started (record-from-answer; likely never truly answered).
**Advice:** switch to **STANDALONE** mode — direct AudioRecord starts instantly, no scrcpy cold-start, so short
calls are captured. Shizuku's scrcpy latency is inherent; on a slow device + short calls it loses the opening.
(Possible in-app: in Shizuku mode skip the doomed direct attempt and/or keep scrcpy warm in standby — not started.)

### ✅ foxcam — one-click GLOBAL ON/OFF (carrier **and** VoIP) — VERIFIED 2026-10-11 (standalone + Shizuku); merged into `main` (`1c800e60`, local-only, unpushed)
The existing master switch (`carrier_recording_enabled`, Settings "Record phone calls") is **carrier-only** — it
does NOT cover VoIP. foxcam wanted a single toggle that stops/starts **everything** (carrier + VoIP) in one tap.

**Shipped shape — a single On/Off master switch** (`recording_state` pref = ACTIVE/STOPPED), layered on top of the
per-type switches so turning it back on restores the user's carrier/VoIP settings, never clobbers them:
- **ACTIVE (on)** — daemon up, recording armed (today's normal).
- **STOPPED (off)** — physical teardown: stop the daemon + `DaemonKeepAliveService` (so the keep-alive notification
  goes away [[keep-alive-notification-cannot-be-hidden]]); in Shizuku mode, unbind the user service; `ensureRunning`
  no-ops so NO proactive path (app start, boot, warmup) resurrects it. Start = cold-start back to ACTIVE
  (standalone ~a few s, up to ~30s right after a reboot). Covers carrier **and** VoIP.

**Pause was tried and dropped (maintainer, 2026-10-11).** A warm PAUSED state was built first (3-state:
ACTIVE/PAUSED/STOPPED), but on-device testing showed entering Pause and Stop are indistinguishable to the user —
both take effect instantly, since the pref flip is what gates recording and the daemon work is background. Pause's
only real difference (instant, notification-kept resume) was too narrow for a third state and keeping a persistent
notification contradicted the "stop the app" ask. Collapsed to On/Off in commit `1105e48e`.

**Surface:** the "Ready to record" home card shows the state + one action (on → **Stop**; off → **Start**). Plus a
**Quick Settings tile** toggling the two from the shade.

**Decisions — maintainer:**
1. **Mid-call = finish current, apply to next.** A recording already running completes normally; Stop takes effect
   from the next call. Non-destructive — never cut a live recording.
2. **Survive reboot = YES.** The persisted `recording_state` wins: `DaemonKeepAliveService.start` AND
   `RecorderBackend.ensureRunning` both honour STOPPED, so boot/launch/update/Settings do not resurrect the daemon
   until the user taps Start (ties to [[reboot-deadlock-wd-off-by-user]]).
3. **QS tile = toggles the two** (On⇄Off).

**Also fixed (same branch), from the maintainer's on-device testing 2026-10-11:**
- A call made while OFF no longer raises a "call not recorded" card/notification — it was a deliberate skip, not a
  miss. Carrier: the status-card sweep ANDs gap detection with the master switch (commit `54fb47b4`, same pattern it
  already used for the per-direction carrier toggle). VoIP: `reportMissed` stays quiet when the switch is off, and
  the VoIP "Ask me" prompt is no longer raised when OFF (commit `fc586ed6`).
- **Start warmed the daemon only on the keep-alive watchdog's next cycle** — 60s poll × 2-down debounce = ~60s
  before the recorder actually came up, so a call right after Start (esp. VoIP, which cannot arm late) was missed
  while the card already read "Ready". Fixed (commit `9a759ec7`): `bringUp` launches the recorder synchronously via
  `ensureRunning` for both backends and re-posts the keep-alive notification once it is up. Measured: daemon + VoIP
  armed ~1s after Start.

**Status — ✅ VERIFIED 2026-10-11 (maintainer, OP9 standalone) for the core On/Off:** Off skips the call (carrier +
VoIP, no false "not recorded"); On returns to ready instantly and a call made quickly afterwards **recorded**. Also
exercised: card shows one button per state, Stop removes notif 4720, STOPPED survives relaunch and reboot.
✅ Shizuku mode VERIFIED 2026-10-11 too (maintainer, real call on the OP9): switched to Shizuku, Stop retires the
`com.baba.callvault:recorder` service, Start re-binds it ~1s, card shows "· Shizuku", and a real call recorded. NB:
VoIP is unsupported in Shizuku by design (scrcpy capture) — carrier only there.

Delivered in this round and VERIFIED on the OP9: Off wording is "CallVault is turned off, calls will not be
recorded." (translated into all 13 locales); the card shows "Starting up…" **only** when warm-up runs past a 3s
grace (reuses the keep-alive's own translated wording), then flips to Ready on connect — a fast ~1s warm-up never
shows it; Start warms the daemon in ~1s instead of ~60s; no false "call not recorded" and no VoIP prompt while Off.

**Merged into `main` 2026-10-12** (no-ff, local only — **not yet pushed**, push held per maintainer). Card wording
and locales are done. One optional open item remains: whether to fold in the honest live-probe status from
[[hyperos-shizuku-recorder-death-research]] (parked on tag `parked/health-gated-recorder-status`) for the residual
sub-3s Start race — the 3s grace already covers the visible case.

### 🅿️ alex_812 (a) — second line / call-waiting records into the first call's file
"When using the second line, everything is recorded for the first call." Call-waiting = both cellular calls share
ONE OFFHOOK session; `CallSessionManager` keys the recording on the first number, and the voice-call stream carries
whichever call is active → A + B land in A's single file; B gets no separate recording. **Distinct from the
app↔phone switch at `fcbff97` (that's VoIP-vs-carrier).** Splitting A/B needs call-identity Android won't expose
to a non-default-dialer with READ_PHONE_STATE (OFFHOOK stays OFFHOOK across the swap). Likely PARKED (hard); document
as a known limitation. 🧪 unconfirmed on our devices.
**📌 RESEARCH WHEN WE GET TO IT (maintainer):** check whether any other call recorder has solved call-waiting /
second-line split — what signal they use (default-dialer InCallService? CallScreeningService? per-call
`Call.Details`?), and whether it's reachable for us without becoming the default dialer. See [[reuse-proven-implementations]].

### ✅ alex_812 (b) — recordings show numbers, not contact names — REPORTER SELF-RESOLVED 2026-10-13: it was the file-name template, not the phone
**Reporter's own conclusion (2026-10-13):** *"I found the reason. These are the application settings… the person
selected the display number / contact / date, changed it, made a call and now it is visually convenient… the
display is based on the created name of the call recording. It's a pity that it doesn't define and change the
display on its own… the problem may not be with the phone, but with the choice of recording display, and then
you need to make a call to make sure that the desired display is displayed."* In plain terms: he had a
file-name template that wrote the **number** into the name; he switched to a **contact** template, made a **new**
call, and it now shows the contact. Old recordings keep their created name.

**Confirmed against code — the open question is answered: the list DOES re-resolve, but only when the template
carries a number.** `RecordingsRepository.list()` (lines ~220-228) does a live `lookupContactName(number)` on
every pass and overrides the baked `contactName` — **but only when `parsed.number` is non-blank.** The parser
only fills `number` when the filename contains `{phone_number}` (`FileNameTemplates.FILE_NAME_TEMPLATE_PRESETS`).
So:
- Template **with** `{phone_number}` → names re-resolve live each refresh (grant Contacts + sync → old
  recordings light up too).
- Template **contact-only** (`{date}_{contact_name}`, `{contact_name}_{date}`, `{date}_{direction}_{contact_name}`)
  → if the contact was unresolved **at record time**, the formatter bakes the **number into the name slot**, there
  is no `{phone_number}` to look up from, and the display is stuck on that created name forever. ← his case.

**Not a bug; a template-choice behaviour.** Advice stands (grant Contacts, enable Google→Contacts sync), plus:
to get live name resolution on a contact-only template too, we'd need to keep the number available regardless of
template. The `.json` sidecar already records the number — so the list could re-resolve from that when
`parsed.number` is blank.

**🧪 Feature (his ask) — BUILT, MERGED into `main` 2026-10-13, device-verified on OP9, slated for 2.4.6.**
"Resolve the display name on its own, including old recordings." The list already re-resolves from a parsed
number each pass; now, when the filename carries **no** number, it falls back to the baked contact slot when
that slot still **looks like a number** (`looksLikePhoneNumber` — digits + number punctuation only, ≥5 digits,
so a real name is never sent to PhoneLookup) and resolves from that. The file on disk keeps its name; only the
displayed name updates. So a contact saved later, or the template changed later, shows the name without
re-recording. The `.json` fallback is **not** used (it is opt-in/off by default, so unreliable) — the number is
read from the name itself. Commit `6cc8e9f4` + merge; unit tests `LooksLikePhoneNumberTest`.
**Device-verified (OP9, 2026-10-13):** a recording named `…_in_+15551230002.ogg` (number in the contact slot)
showed **"AliceTester"** with the contact present, and reverted to **+15551230002** when the contact was
deleted — live resolution with a safe fallback. Stays 🧪 (not maintainer-confirmed in real use).

---

## 🔼 Truthful, health-gated recorder status + rebind-at-call — RESEARCHED 2026-10-07 (fixes "Active but missed the call")

**Why:** multiple HyperOS/Shizuku reports (UserW Redmi `emerald`, UserZ, and the divago/voss family) where the
Home card shows **"Active / Ready"** but a real call is **silently not recorded**. Full cited research +
sources in memory **[[hyperos-shizuku-recorder-death-research]]**. ShizuCallRecorder **#110** is our bug almost
verbatim; **LyoSU/cally** (2026) and **Nightzuku/NightDog** ship the fix pattern.

**The key framing — two bugs in one face:** (a) the OS *kills* the Shizuku/ADB-child recorder → **unfixable
in-app** (prevention is device-settings-only; confirmed by Shizuku #2475/#1426). (b) the app trusts a **stale
cached binder while the daemon is dead/unresponsive** → **fixable in-app**, and that is what makes "Active"
lie. So detection/honest-status/recovery ARE improvable even though prevention is not.

**🧪 Reproduction found (UserZ, 2026-10-07):** in Shizuku mode, pressing the **clear-all "X"** in HyperOS
Recents reliably stops recording. Clear-all force-kills the **Shizuku** card too → the recorder fork it hosts
(uid 2000) dies → CallVault's Home card still shows **"Active"** → the next call is silently missed. (UserZ's
screenshot even shows Shizuku's card reading *"not running"* beside CallVault's *"Active"*.) Caveat: this is
**one trigger**, not the only one — UserW's log shows the same death *between calls* with no clear-all (HyperOS
doze/screen-off kills it on its own). **Value:** this gives us a manual repro we can run on our own OP12
(Shizuku mode → record → press X → does the status go honest or lie?) to verify items 1–3 below.
**User fix for this trigger:** lock both cards in Recents (long-press card → 🔒 padlock) so the X skips them.

**🧪 BUILT but NOT merged — code preserved on tag `parked/health-gated-recorder-status` (2026-10-07; branch deleted 2026-10-12). Awaiting maintainer sign-off + HyperOS field confirmation before it goes near main.**

Why it is parked and not on main (maintainer bar 2026-10-12: *"merge only if we are positive it won't affect other people"*): (a) its whole reason to exist — the HyperOS "Active while dead" reconcile — has **never run on a real HyperOS device**; (b) it was built against pre-On/Off main and now needs a real re-merge against `HomeViewModel` (the On/Off STARTING logic touches the same `startListPass`/`RecorderConnection.isConnected`), so today's OP9 results no longer fully cover it; (c) it adds a ≤1.5s probe + possible rebind at Shizuku call-start, the exact path where short calls are already timing-tight ([[shizuku-scrcpy-coldstart-loses-short-calls]]). To resume: `git checkout -b feat/health-gated-recorder-status parked/health-gated-recorder-status`, re-merge onto current main, re-test on the OP9, and get a HyperOS user (UserZ/UserW) to run the clear-all repro.

**On-device run on the OP9 (standalone/ColorOS, branch debug build installed over 2.4.5, same cert) — observed in logcat:**
- timeout branch: froze the daemon (SIGSTOP) → `CV:RecorderConn: Recorder did not answer a liveness probe within 1500ms; leaving the binder in place` — bounded, no hang, keeps a slow (not dead) binder. ✓
- happy path: healthy daemon on resume → 0 probe-failure logs, no false reconcile, list pass normal. ✓
- normal death (`kill -9`) → `Daemon binder died; clearing RecorderConnection` → keep-alive relaunched (fresh pid) → `received daemon binder … alive=true`. Death/recovery intact. ✓
- NOT provable on ColorOS: the HyperOS "binder nominally alive but host dead → RemoteException → reconcile" path (ColorOS fires linkToDeath normally) — unit-tested (`RecorderConnectionProbeTest`), awaits UserZ/UserW on HyperOS.

- **Item 1 (honest liveness) — DONE.** `RecorderConnection.probeAlive()` (commit 390f480d): a bounded
  `isRecording()` round-trip (not just `pingBinder`/`isBinderAlive`, which HyperOS leaves nominally alive over
  a dead host); a definitive failure reconciles the stale binder (identity-guarded) and fires `onDeath`, a
  timeout leaves it in place. Wired into `HomeViewModel.startListPass` so the card self-corrects on resume —
  Shizuku mode → `SHIZUKU_NOT_READY`, standalone stays READY and keep-alive relaunches. Unit-tested
  (`RecorderConnectionProbeTest`).
- **Item 2 (rebind-at-call-start) — PARTLY DONE.** `ensureShizukuRunning` now gates reuse on `probeAlive()`
  too (commit 31962311), so a dead-but-nominally-alive host falls through to a fresh bind instead of being
  trusted. Also fixed a latent quirk: a dead-NODE binder made `retireIfStale` clear it yet return false,
  which the gate read as "reuse" and returned true with a null `service`.
- **Item 4 (version-mismatch self-heal) — ALREADY EXISTS on main:** `retireIfStale` → `retireShizukuService`.
- **Item 2-warn + Item 3 (user-facing warn / self-clearing fault notification) — ❌ DROPPED 2026-10-07
  (maintainer):** *"I only care about the auto-heal and not lying about status — adding another notification
  when we have auto-heal is redundant."* The fix is Items 1+2 only. **Verify on the OP9, not the OP12 — the OP12 has no Shizuku.**

**Proposed work (both modes; highest value first):**
1. **Liveness, not a cached flag.** Gate "ready/active" on: live binder (binder-received/-dead listeners) **+
   `pingBinder()` each poll** (catches unresponsive-but-no-death) **+ a real round-trip** (an AIDL call like
   cally's `svc.version`, ideally a trivial "exec as shell uid" probe — HyperOS leaves the binder nominally
   alive while the privileged child is gone).
2. **Re-request/confirm the binder at CALL START** (wait for `Bound` with a timeout, bail+warn otherwise) so a
   dead recorder is a *warnable* event, not a silent lost call. (cally `CallMonitorService.kickoff()`; SCR #110 ask #1)
3. **Self-clearing fault notification** driven by a composite `DaemonHealth` where the between-calls `Idle`
   (daemon alive via `daemon(true)`, app unbound) is **not** a fault — shows only on real fault, auto-cancels
   when healthy. Fixes "shows Active while dead"; `dumpsys`-checkable. (cally `DaemonHealth`/notification; SCR #110 ask #2)
4. **Version-mismatch → `unbind(remove=true)` → fresh bind** self-heals the stale-after-update daemon
   ([[shizuku-stale-service-after-update]]).

🚨 **`daemon(true)` caveat:** only helps if the hosted recorder owns the capture fd; SCR uses `daemon(false)`
because the pipe read-end is app-side — verify against our handoff/pipe model before flipping it.

**Cheap experiment worth a try:** disable the Android 12+ **phantom-process monitor** via shell `device_config`
(`max_phantom_processes`, `settings_enable_monitor_phantom_procs false`) — may stop AOSP killing our spawned
daemon; we hold WRITE_SECURE_SETTINGS. Re-ensure (flags re-sync away). **Bigger later bet:** a Qidi-style
`init`-parented shell sentinel that re-arms Shizuku PC-free + re-injects Doze/standby/AppOps.

**Watch (independent):** HyperOS 3's `MiuiSentinelMemoryManager` hard ~3.5 GB per-app RAM cap could kill us
during a long recording + in-RAM transcription model.

**User mitigations to give now (Shizuku AND CallVault):** Autostart ON + Battery "No restrictions" + lock in
Recents + uncheck "Pause app activity if unused". One-shot ADB whitelisting does NOT persist (PowerKeeper).

---

## 🔵 Still open from the 2.4.4/2.4.5 batch

The batch shipped in 2.4.4 (pulled) → 2.4.5; the shipped pieces are in **Completed** below. Still open from that round:

- **#9 VoIP mic re-take, step 2** — skip the re-take when `isClientSilenced` is false; step 1 built + measured (OP9: 31 re-takes, all `silenced=false`, 3.5 s lost in 49 s), waits on a Samsung log. (`2026-09-23-voip-near-side-zeros.md`)
- **#3 pairing-timeout "switch off with consent"** — the Offline-recording recommendation shipped (in the Pair-again hint); switching the Android 7-day timeout off with consent did not.
- **#7 Resilient falls back to normal recording — ⏸️ DROPPED 2026-09-29**: #1 (Android-version gating) covers coonrw's A12; meti.sh's A13 is covered by the 2.4.3 stale-cblk fix. Revisit only if meti.sh still loses calls on 2.4.3+.
- **#8 No phantom 1-second app-call file at hang-up — ⏸️ PARKED 2026-09-29**: the minimum-recording-length setting already discards short files on both paths (`RecordingForegroundService.kt:719`, `VoipRecordingCoordinator.kt:622`).
- Waiting on users, not on us: benjamin (Wi-Fi-calling vs Android 17 test calls), scrunscotty (rc4 log). Still open from before: #42 Android 13 mic fallback (decision pending).

---

## 🐞 The transcript sheet's height follows its content — one line explains most of #27's follow-up

**Read from source on 2026-09-07, not measured.** `TranscriptSheet.kt:201`:

```kotlin
LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false))
```

`fill = false` lets the list take only the height of what it has *composed so far*, so the sheet grows
as you scroll. That single line predicts nearly everything mirror176 reported in #27: it opens at about
half height, grows towards fullscreen as you scroll down, the playback controls appear to be "revealed"
because a growing list pushes them, rotating a fullscreen sheet drops it back to half (a fresh measure),
and reopening it later opens fullscreen (the retained list state composes more at once). It reads as
intermittent because it is content- and scroll-dependent, not random.

Likely fix: `Modifier.weight(1f)`. The sheet already sets `skipPartiallyExpanded = true` and its own
doc says "near full height", so filling is what was intended. **Verify on a device before believing
this** — and check what an always-near-full sheet looks like for a three-line transcript.

Same report, same area, cheap and independent: **the delete / re-transcribe confirmation closes the
sheet before you have confirmed**, and cancelling leaves it closed. Only close it when a delete is
actually performed. Note the agreed item above about removing "Transcribe again" from the sheet
removes half of this on its own.

---

## ✅ Transcript reading: mirror176's design proposals from #27 — two VERIFIED on device 2026-10-12, two deferred

> **✅ VERIFIED 2026-10-12 on device (OP9) — the maintainer delegated the on-device test ("you can test this
> yourself … generate fake calls, transcribe them and test"); done with generated BCR-named calls + a
> contact-matched call.** Merged into `main` 2026-10-12 (`6b67bbcf`; code `2e6979e8` + `ab9d67a4`); build +
> full unit suite + checkTranslations green. (`main` is still local — nothing pushed.)
> - **Header number/date/time:** opened a transcribed call's reading view → header read **"Alice Tester" /
>   "+15551230002 · 2026-07-11 14:30"** (the number shows only when a contact name resolves — gated on
>   `contactName`, after a bidi-isolate dup bug was caught and fixed in `ab9d67a4`).
> - **Sort chip:** Newest / Oldest / Longest / Name A–Z all reordered correctly; the chip tints when not the
>   default. Bonus: the select-all row's tri-state (indeterminate → all) and bulk delete emptied the list with
>   nothing stranded (re-confirmed the bulk-delete-by-name fix on the OP9).

- **Previous / next call from inside the transcript.** ❌ DEFERRED — medium effort; must follow the list's
  current sort+filter order (now that a user-facing Sort exists, this can be built cleanly on top of it). He
  reaches a transcript, reads it, and has to go back out to reach the next call.
- **A transcript that is fullscreen all the time**, with the number, date and time in its header. ✅ DONE
  (`2e6979e8`): the sheet was already near-full (`skipPartiallyExpanded`) and the height bug is fixed, and the
  header now carries number · date/time (`TranscriptView` `subtitle`, fed from the catalog row in HomeScreen).
- **A way to deselect a highlighted line.** ❌ DEFERRED — medium; two parts (clearable highlight + prefer
  restoring scroll over jumping on rotation). The highlight is currently the playing line and always re-jumps.
- **Richer filtering and sorting of the call list.** ✅ DONE (`2e6979e8`): filtering was already fully faceted;
  added a Sort control (`RecordingSort` NEWEST/OLDEST/NAME/LONGEST on `HomeUiState` + a chip). This is what
  makes the prev/next ordering question above real, so prev/next is the natural next step.

---

## 🔵 Targeting Android 17 (API 37) will break mDNS discovery until we ask for it

Android 17 makes `ACCESS_LOCAL_NETWORK` a runtime permission and gates `NsdManager`, mDNS and every
local-network socket behind it. **It is enforced by target, not by device**: at `targetSdk 36` we keep
local-network access implicitly through `INTERNET`, so nothing is broken today — a reporter already on
Android 17 (issue #23) reaches discovery fine. The day `targetSdk` goes to 37, `AdbMdns` finds nothing
on every phone, and pairing dies with it.

What it needs when we bump: declare `ACCESS_LOCAL_NETWORK`, request it at runtime before the first
discovery, and handle denial with a real message rather than a silent nothing. Do NOT request it while
we still target 36 — Google's own guidance is that it is not enforced there.

Reference: <https://developer.android.com/privacy-and-security/local-network-permission>. Shizuku's
13.7 fork already declares `ACCESS_LOCAL_NETWORK`, `USE_LOOPBACK_INTERFACE`, `NEARBY_WIFI_DEVICES` and
`CHANGE_WIFI_MULTICAST_STATE`; we declare only `INTERNET`, which is worth re-reading at that point.

---

## 🔵 #30 follow-up: a setting to stop CallVault managing Wireless debugging

Asked for by mirror176 in #30 (2026-09-10). Agreed by the maintainer 2026-09-11, and promised in the
issue.

**What 2.3.0 already does:** CallVault only turns Wireless debugging off if CallVault turned it on
(`WirelessDebuggingPolicy.mayRelease`, pref `wd_enabled_by_us`). A switch the user flips is left alone.

**The ask:**

1. **An on/off setting for that automation**, in Settings or as a button on the home screen. With it
   off, CallVault leaves Wireless debugging to the user.
2. **Show when CallVault is leaving the switch alone.** Today the only sign is a log line ("Leaving
   Wireless debugging on … the user switched it on, not us"). He asks for something the user can see.

**Decided (maintainer, 2026-09-11): "off" means CallVault never turns Wireless debugging off.** It may
still turn it on when the recorder needs a way back in. The other reading — never turning it on — was
rejected: a dead recorder could not come back without USB debugging or loopback, and the next call
could be missed. The cost of the chosen reading is that Wireless debugging stays on, leaving a network
port open; the setting's description should say so.

**Check before building:**

- **The transport rule still wins.** Dropping adbd's last transport kills the daemon whatever the
  setting says.
- **An old switch already exists:** `wd_disable_when_idle` ("Turn off Wireless debugging when idle",
  default off, from persistent-server mode, `AppPreferences.kt`). Check whether it is still shown or read
  before adding a second one; it may be the right home for this.
- **Standalone mode only.** Shizuku does not use Wireless debugging for us, so the row should say it
  does nothing there.
- **One place turns it off:** `AdbShell.releaseWirelessDebugging`. Check the setting there, not at each
  caller — three callers had to agree before #30 was fixed.
- **Onboarding:** probably not; this is a power-user setting.

---

## 🔵 Release hardening: APK size (R8) + release supply-chain (attestation/immutability) — raised 2026-10-13

Two independent, actionable items raised by a public critique from **kitsumed** (ShizuCallRecorder author,
our upstream). His other two points — that CallVault is a fork, and is AI-assisted — are true and already
disclosed, so nothing to do there. Full analysis, evidence and options:
`docs/dev-notes/release-hardening-size-and-attestation.md`.

### 🔵 1. Turn R8/minification on (cut the APK size)
`isMinifyEnabled`/`isShrinkResources` are **off**, so the APK is ~68 MB — **~56 MB of it unshrunk DEX**
(native libs are only ~14 MB). R8 is off deliberately: the daemon is launched by `app_process` via a **string**
classpath, so R8 strips it and **silently breaks recording** (documented in `build.gradle.kts`). Fix =
comprehensive `-keep` rules for the whole daemon class graph + reflective entry points, **gated on on-device
verification** (real carrier + VoIP call + daemon-up-after-install-over on both phones), not on a green build.
Realistic floor ~30–40 MB, not the 6–9 MB of a call-recorder-only app — the transcription/ADB stack stays.
Medium effort, **real risk** (a wrong keep set = silent no-record). Own branch.

### 🟡 2. Attested / immutable releases (immutable DONE)
CI (`build-app.yml`) wires up `actions/attest-build-provenance`, but releases are **built locally and
published with `gh release create`**, so the **published APKs are not attested**. Steps, cheapest first:
(1) ✅ **immutable releases — DONE 2026-10-13 (maintainer)**; (2) **provenance for the shipped APK** —
🚫 **do NOT build+sign in CI** (that puts the update-signing cert — the trust anchor for silent in-place
updates — into CI secrets, the one genuinely risky option; see [[release-build-signing]]); instead, if wanted,
**cosign keyless** the locally-built APK (key stays local, low risk); (3) **reproducible builds** — strongest
answer, pairs with F-Droid readiness. Current guard is local key custody + a pinned cert SHA-256 the updater
verifies. **Whole item is lower risk than item 1 — process/infra, cannot break recording; the only real risk
is key custody, avoided by keeping signing local.** Full detail + verdict in the dev-note.

---

## ✅ Completed

Shipped/verified work, condensed to one line each. Newest first. Full detail lives in each release's memory note, the dev-notes, and git history.

**2.4.5**
- **Transcript + notes `.md` sidecar beside the audio (#1)** — 2.4.5 (🧪, `83369db1`); opt-in, for PC backup.
- **More filename templates (#3)** — 2.4.5 (🧪, `83369db1`); contact/number-first orders.
- **Export / import settings (#4)** — 2.4.5 (🧪, `83369db1`); allow-listed, device-specific keys excluded.

**2.4.4 (pulled) → 2.4.5 batch** — still-open residue is in "Still open from the 2.4.4/2.4.5 batch" above.
- **Features match the phone's Android version (#1)** — 2.4.4→2.4.5 (🧪, `53529e65`, rc17); grey out + switch off what the Android version can't run (app calls need 14, Resilient 13). Not yet seen on an A12/13 device.
- **Detect a lost ADB pairing + "Pair again" (#43)** — ✅ VERIFIED 2026-09-29 OP9 (rc11); stops the endless retry, Home card + Settings row.
- **Recover a recording cut off by the app being killed (#4)** — ✅ VERIFIED 2026-09-29 OP9 (rc13, `35d178b7`); finishes a leftover `rec_stage_*.tmp` at start-up.
- **Say when a call was lost (#5)** — ✅ VERIFIED 2026-09-29 with #4; notification + card instead of silence.
- **Fix the misleading "turn Wireless debugging on" regrant card (#6)** — ✅ VERIFIED 2026-09-29 OP9 (rc14, `cd6de764`); not shown when the phone blocks grants or loopback works.
- **vivo app-call crash + one-sided freeze (#10)** — 🧪 DONE (`5a63239f`; freeze fixed `e598175a`, rc16); far side still unrecordable on vivo (SUPPORT.md).
- **Update popup + faster checks** — 🧪 BUILT (`fc493e6c`, rc19); once-per-version popup on open, background check 24h→6h, on-open throttle 6h→30min.

**2.3.0 / 2.1.x (2026-08)**
- **Favourites, min-duration discard, storage cap** — ✅ VERIFIED 2026-08-30 (`19a018d`, `ce9fee8`, `1afaecc`). 🚨 Any reason to sweep must be tested by BOTH `RetentionScheduler` and `RetentionSweepWorker`, or a cap-only sweep never runs.
- **D5 "mark a moment" (flag + Stop ongoing notification, both paths)** — BUILT 2026-08-30 (`abd5ae0`); D9 per-app VoIP whitelist followed.
- **SilentFailureNotifier — notify after boot when offline recording's loopback is down** — DONE 2026-08-30 (`d79eb72`); self-clearing, mode-aware.
- **E1 InCallService spike** — Telecom binds us on OxygenOS 16, giving carrier direction/state by construction (`da035bf`); but WhatsApp registers no Telecom call, so the VoIP case gets no callback. Feature PARKED — see the E1 sections.
- **Refuse to transcribe a recording over 15 min, on every path** — DONE 2026-08-25 (`TranscriptionRunner.runOne` + `TranscriptionQueue.pending`); avoids the whole-file decode OOM.
- **Enable ARM CPU features in the ggml build** — SHIPPED 2026-08-26 (`2ce45c8`); 7 CPU variants, HWCAP-selected; summarise 1.74×, transcribe 1.17×.
- **Silent VoIP failure now reported** — fixed 2026-08-24 (`VoipMissPolicy`). 🚨 No retry is possible (arming is fixed at track creation) — do not re-propose one.

**1.5.x (2026-07)**
- **Don't install an update while a call is being recorded** — v1.5.6 (`4e46948`, merged `2e5c0dc`); `CallInProgressGate`, guards VoIP too.
- **VoIP near-party drop-outs on One UI** — FIXED by re-taking the mic, v1.5.5.
- **Validate the encoder before recording into it** — v1.5.6 (`1ee77af`, merged `2e5c0dc`); `EncoderLimits` clamps bitrate, refuses an unsupported format → scrcpy.
- **Manual "Check for updates" in Settings** — SHIPPED; bypasses the 6h throttle, reports in place.
- **Keep-alive rewarm latch could permanently stop recording** — v1.5.5 (`RewarmGate`); exposed v1.4.0→v1.5.3. Shipped on unit tests only, no device run recorded.
- **Upload schedule surfaced in Settings (#20)** — v1.5.5; picker extracted from the wizard (`SyncScheduleLabels.kt`).
- **Settings restructure — a "General" section** — v1.5.5; nine top-level sections → six, Settings as a side panel.
- **Control over what gets recorded** — 1.5.6 (`CARRIER_RECORDING_ENABLED` + `VOIP_AUTO_START`); an app-calls-only recorder is now possible.
- **Resilient recording on One UI — ring geometry + crackle** — v1.5.3; ring derived from the mapping, `GUARD_FRAMES` 32→960. 🚨 The native drain must be passed `wrapFrames`, not `frameCount` — a third caller would reintroduce the over-read.

**Issues verified / shipped**
- **One notification during a recorded call (#31 follow-up)** — ✅ VERIFIED 2026-09-11 (`SharedStatusNotice`); the recording posts under the keep-alive's id.
- **Import an audio file recorded elsewhere (#37)** — SHIPPED in 2.4.0 (`HomeViewModel.importAudio`, `ShareImportScreen`, `ImportedRecording`).
- **Speaker labels for Shizuku recordings (#38)** — ✅ VERIFIED 2026-09-20 OP9 (`ef2dc3b9`, `8d79713e`); labels at transcription time from the stereo decode.

---

## Current state — 2026-09-11 (STALE — superseded by the 2026-10-01 block at the top; kept as history)

**Released:** `v2.3.0` (versionCode **20350**), published 2026-09-11. Asset `CallVault.apk`, downloaded back
and verified byte-identical to the tested build (sha256 `5b795a26…0add`). `origin/main` = `756f27a`.
It carries everything once planned as 2.2.1, which never shipped on its own. The next release must be
above 20350.

**🧪 Shipped, waiting on a reporter or the maintainer:** stuck-mic auto-heal (tester's OP13), #33 progress
curve, #31 manual-run notification, #34, #35, Save + full-screen log viewer (#28/#29, mirror176), #23
(Xiaomi discovery), #24 (Shizuku hide mode).

**Backlogged, not in 2.3.0:** #32, #36, #37, #38, F-Droid, and a setting to switch off CallVault's
Wireless debugging management (#30 follow-up).

The 2026-08-24 block below is stale — it describes the 2.1.0 stack — and is kept only as history.

## Current state — 2026-08-24

**157 commits** sit unreleased on `feat/speaker-labels` → `spike/summarisation` →
`worktree-feat+transcription-engine`, all documented under **[Unreleased]** in `CHANGELOG.md` as
**2.1.0**. None of the three branches is merged or pushed. That is a whole major release in a stack
of three unmerged branches; the longer it stacks, the more a single bad merge costs.

`ciVersionName` is already **2.1.0** and `versionCode` **20100** (an earlier entry here said to bump
to 1.6.0 — stale, written before the version scheme moved). 20100 clears the 10720 floor.

The maintainer is running this build daily for a few days before cutting the release.

### 🧪 Switching between an app call and a phone call — ONE file (`fcbff97`, 2026-08-30)

Reported by the maintainer: WhatsApp call → answer a cell call → return → the app call came back as
a **second** recording. The audio was never lost (the first half is published when the phone call is
answered) but one conversation in two files is not one recording.

**The constraint that shapes the whole design:** the app capture MUST release the microphone for the
phone call. A second voice AudioRecord open during a carrier recording silently drops the user's own
side — see [[no-second-voice-capture-during-call]]. So this is a *suspend*, not the pause added the
same day: the pause keeps the mic, and using it here would trade a split app recording for a
half-broken phone recording.

Encoder, muxer and fd stay open; new records are acquired on the way back. Re-acquiring mid-recording
was already proven — `retakeMic` does it on One UI.

**Two things that were nearly bugs, recorded so they are not reintroduced:**
- The feeder threads ended themselves on a failed read. Correct for a broken capture, fatal for a
  suspended one — the thread would be gone before the phone call finished. They now wait.
- `CaptureAudit.released()` was being called twice for the same id (once on suspend, once in stop),
  which logs a misleading "already released" line into the exact record a stuck-microphone report is
  read from. The id is zeroed on suspend.

**Reverse direction needs nothing.** Nothing stops a carrier recording when an app call starts, and
`mayStartNow` blocks an app recording while a phone call is up, so cell → app → cell was already one
continuous carrier file.

**Open risk, only a real call can settle it:** whether the far-party submix re-attaches after the
phone call. If it does not, the second half is near-side only — logged explicitly rather than failing
silently, so the log will say so.

### 🅿️ Original framing — D5 "flag a moment", before the decision

Pause/Resume already existed. Stop is done. Flag is the remaining third and it is **carrier-only**
unless a whole ongoing notification is built for the VoIP path, which today posts only error and
"ended" notifications and has no action surface at all.

That matters specifically for this maintainer: on 2026-08-30 three of the four recordings on the
OP12 that day were `voip-WhatsApp`. A carrier-only flag button would be nearly useless to the person
asking for it, so it was not built on the quiet assumption that some flag is better than none.

Cost if it is wanted: an in-memory list of offsets during the call, an elapsed-audio clock on the
engine (wall clock minus accumulated pause, since paused time is not written to the file), persisting
at finalize against the FINAL name to survive the call-log rename, a `recording_flags` table at v8
joined to the cascade, and chips on the playback screen that seek. Roughly favourites-sized for the
carrier path; roughly double that if the VoIP path gets its own ongoing notification.

**Confirmed on the OP12 by the maintainer, 2026-08-30**: the star, the Starred filter chip and both
new Settings rows all work. Installed as release 2.1.2 over 2.1.2; the v6 → v7 migration applied
cleanly on a real database.

**Still proven only by unit test**, because no real event has exercised them yet: that a genuinely
short call is discarded, and that a cap sweep deletes oldest-first while skipping a starred
recording. Neither needs chasing — they will settle themselves in ordinary use — but do not write
either into the README as a measured result until one has actually fired.

### 🅿️ Trash / recycle bin — built 2026-08-29, then REVERTED and PARKED

Built end to end and reverted the same day. The maintainer's verdict was that it was more machinery
than the problem deserved: *"I think we overdid it with the trash."* Agreed — a delete already has a
confirmation, and the bin added a setting, a Home mode, a purge schedule and two folder-scanning code
paths to protect against a mis-tap that is already guarded once.

**Do not rebuild it from scratch without reading this.** The design worked, and the reverted commits
are `1ad9391`, `d47c8d2`, `1cd5de3`, `2d59385`.

**What the design got right, if it ever comes back:**

- **Rename in place; never move or copy.** A move needs `FLAG_SUPPORTS_MOVE`, which not every SAF
  provider offers, and the copy-and-delete fallback would download and re-upload the file on the Drive
  folder — a hundred megabytes over mobile data to delete something. A rename transfers no bytes and
  is exactly as reversible. All state lives in the file name, so no table can disagree with the folder.
- **Filter at `RecordingsRepository.enumerateFolder`**, the single funnel every listing goes through.
  `UntrackedRecordings` and `DriveCatalogRepair` both use it, which is what stopped a trashed file
  reading as untracked and being deleted on the *live* retention schedule.

**Three traps that cost real time, and would again:**

1. 🚨 **Trashing must not run `TranscriptCascade`.** The transcript, summary, note and tags belong to
   a recording that can still come back; taking them on the way in makes a restore return silent
   audio. `RecordingCatalog.removeName` grew a `cascade` flag for that one caller.
2. 🚨 **`RetentionScheduler` cancels the daily sweep when retention is off — the default — and that
   sweep is the only thing that empties the trash.** So the bin never purged for most users and grew
   without bound. The purge code was written to run "regardless of whether retention is on", which was
   true of the worker and false of the world.
3. 🚨 **Every delete path has to be routed, not just the per-row one.** Bulk multi-select delete
   bypassed the bin entirely on the first pass — the easiest place in the app to destroy more than was
   meant. Per-copy delete correctly stays permanent, since the other copy survives.

**And the question that surfaced two of those:** retention and the bin are independent. Retention
deletes **permanently** and does not fill the bin, so a 7-day retention is 7 days, not 7 + 30. That is
right — retention exists to bound storage — but nothing in the UI said so, and it is the first thing
anyone asks.

### ⛔ E3 — off-Wi-Fi reboot re-arm — SPIKE DONE 2026-08-29, **UNSOLVABLE without root. Do not re-investigate.**

Off-Wi-Fi recording ships (v1.4.0): `adb tcpip` puts adbd on a TCP port and the app connects to
127.0.0.1, so a call records with no network. **The port does not survive a reboot**, and re-arming it
means sending `tcpip:<port>` *through an existing ADB connection* — which off Wi-Fi you do not have.
To get a connection you need the port; to open the port you need a connection.

## Four doors tried. Three are shut, one is untested.

Measured on the OP9 and the AOSP emulator, 2026-08-29. The post-reboot state was reproduced without
rebooting: Wi-Fi off, then `adb usb` to drop adbd back to USB-only (`service.adb.tcp.port` → 0).

| Door | Result |
|---|---|
| `setprop persist.adb.tcp.port` from shell — would open the port at every boot and end the problem outright | ❌ `Failed to set property` — **and it fails on the AOSP emulator too**, so it is SELinux, not an OEM choice. Only adbd may write it, which is the deadlock restated |
| `setprop service.adb.tcp.port` from shell | ❌ same |
| `settings put global adb_wifi_enabled 1` with Wi-Fi off — the app holds `WRITE_SECURE_SETTINGS`, so this is the one lever it *can* pull after a reboot | ❌ the platform **resets it to 0**. Control with Wi-Fi on: it sticks. So the gate is the *network*, not our permission |
| The phone's own **hotspot** as a substitute network | ❌ **tested by the maintainer by hand, 2026-08-29: Wireless Debugging cannot be enabled with only a hotspot up.** Soft-AP does not satisfy it. `cmd wifi start-softap` also refuses uid 2000, so this could only ever have been checked by a person |

## Verdict

**No code-only escape exists.** Every route to opening the port runs through adbd, and adbd only takes
the instruction over a connection that cannot exist yet. This is not a missing API we have failed to
find; it is the shape of the problem.

**All four doors are now shut.** Reboot away from a real Wi-Fi network and the privileged bridge stays
down until the phone next reaches one. Nothing in the app can change that, and no amount of further
searching will: this is a platform property, verified on AOSP as well as on two OEM ROMs.

## Second pass, 2026-08-29 — harder look, four more doors, still shut

Re-opened deliberately: the VoIP "impossible" verdict was overturned once by exactly this kind of
push, so the first spike was treated as suspect rather than final.

- ✅ **The premise is now measured, not assumed.** The OP9 was actually rebooted:
  `service.adb.tcp.port` comes back **empty**. The deadlock is real on this hardware.
- 🔎 **The first spike's negative was stronger than it claimed.** During that test the OP9 had a SIM
  and live mobile data (`rmnet_data1` holding a global IPv6). So Wireless Debugging refused **with a
  working network present** — it wants *Wi-Fi specifically*. That kills the whole family of ideas
  built on "give it some other interface": USB tethering, a VPN `tun`, mobile data, Ethernet.
- ❌ **`android.debug.IAdbManager` / `cmd adb`** — a binder service the first pass never looked at. Its
  shell surface is queries only (`is-wifi-supported`, `is-wifi-qr-supported`); the interesting methods
  (`enablePairingByPairingCode`, `allowWirelessDebugging`) are `signature|privileged` and unreachable
  from our uid.
- ❌ **`/dev/socket/adbd`** — adbd holds a **listening unix socket**, present on a fresh boot with no
  Wi-Fi and no TCP port. Exactly the shape of a door: no network needed at all. But **even shell gets
  `Permission denied` stat-ing it**, so an ordinary app is nowhere near it.

**The one condition that could not be reproduced:** Wi-Fi radio *on* but not associated with any
network. `cmd wifi disconnect` refuses uid 2000 and the phone cannot be moved out of range on demand.
It matters because if WD only needs the radio rather than an association, the whole problem collapses
into "turn Wi-Fi on, you need not connect to anything". Evidence against: the maintainer meets this in
real life, and most people leave Wi-Fi enabled — which suggests radio-on alone is not enough. **Not
proof. Worth one deliberate check next time the phone is genuinely away from any known network.**

## Third pass, 2026-08-29 — the spoofing angles, and the one useful thing they turned up

Maintainer confirmed by hand that **Wi-Fi radio on but unassociated is still refused**, closing the
condition the second pass could not reach. Then: can the association be *faked*, or the USB transport?

**The gate, in the system's own words** — caught in logcat at the moment of refusal:

    AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.

That is `system_server` reading `WifiManager`'s own connection state. Nothing an app or a shell can
influence; there is no setting in between to lie to.

**Why "we already have shell" does not help, precisely.** The kernel's denial, verbatim:

    avc: denied { set } for property=persist.adb.tcp.port pid=… uid=2000 gid=2000
         scontext=u:r:shell:s0 tcontext=u:object_r:default_prop:s0
         tclass=property_service permissive=0

And the reason it is not an inconsistency that adbd *can* set these: **adbd runs as uid `shell` too**,
but in the `adbd` SELinux **domain**, while our shells run in the `shell` domain. Same uid, different
domain, different rights. `service.adb.tcp.port` sits in `adbd_config_prop`; `persist.adb.tcp.port` is
plain `default_prop` — and `shell` may set neither. `adb root` is refused outright (`ro.debuggable=0`,
`ro.build.type=user`).

**USB cannot be faked either.** `sys.usb.config` is already `midi,adb`, so adb is *configured* — but a
USB device needs a host to enumerate it, and there is no transport without a computer on the other
end. Forcing the functions from shell just restarts the gadget and kills the session.

## 🔑 The useful finding: it needs an ASSOCIATION, not INTERNET

"Not connected to any wireless network" means no station association. It does **not** mean no working
internet. **Any** access point satisfies it, for a few seconds, with no login and no connectivity:

- **a second phone's hotspot** — the maintainer carries two;
- a car's Wi-Fi, a hotel or café AP nobody logs into, any open network.

That changes the advice from *"wait until you get home"* to **"associate with any AP for ten seconds,
including your other phone's hotspot"** — which is very often available when a real network is not.
Worth saying in the README and in the recovery prompt, because it is a materially smaller ask.

## Mitigations that are now clearly the right answer

1. **Warn before the reboot.** `ACTION_SHUTDOWN` arrives while the bridge is still up. If offline mode
   is on and Wi-Fi is not associated, say so *then* — the one moment the user can still act.
2. **Notify after the boot**, when the listener is down, rather than letting a missed call be the
   notification.

## Fourth pass, 2026-08-29 — four parallel research tracks + AOSP source. **Closed for good.**

### The gate, from AOSP source, byte-identical in every release 12 → main

`AdbDebuggingManager.getCurrentWifiApInfo()`:

```java
WifiInfo wifiInfo = wifiManager.getConnectionInfo();
if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
    Slog.i(TAG, "Not connected to any wireless network. Not enabling adbwifi.");
```

An **infrastructure STA association** check — not `NetworkCapabilities`, not `getActiveNetwork()`. On
null the handler writes `ADB_WIFI_ENABLED=0` and breaks; the TLS server is never started.

### Why every "make our own network" idea fails, with the reason

P2P, `startLocalOnlyHotspot()`, SoftAP and Wi-Fi Aware all live on **separate interfaces** and none
writes a `networkId` into the **STA** `WifiInfo`; several switch STA off, which independently forces
−1. `WifiP2pServiceImpl` has zero `NetworkAgent` references, so P2P can never present as
`TRANSPORT_WIFI` at all. That is why the maintainer's hotspot test failed, and the whole family was
never viable.

### Device owner: dead, and now for a stated reason

`DevicePolicyManagerService`'s `GLOBAL_SETTINGS_ALLOWLIST` **does** contain `ADB_WIFI_ENABLED`, so a
device-owner app could flip it from app code with no shell — genuinely new, and it looked like the
loop-breaker. But the gate sits **downstream of the setting**. It changes *who may ask*, not the
answer. Not worth the device-owner enrolment UX.

### The race window does not exist — measured, with a validated instrument

The claim that adbd briefly starts a listener before teardown was tested directly on the OP9:
baseline `::`-bound listeners captured from `/proc/net/tcp6`, then `adb_wifi_enabled=1`, then **200
rapid on-device polls**. **No new listener, ever.** Setting reset to 0.

🚨 **The first attempt at this test was invalid and the control caught it.** Polling
`service.adb.tls.port` / `persist.adb.tls_server.enable` returned empty *even with Wi-Fi on and the
server demonstrably running* — a blind instrument producing a false negative. The working detectors
are `/proc/net/tcp6` (state `0A`) and, from the host, `adb mdns services` showing
`_adb-tls-connect._tcp`. **Always run the positive control before believing a negative.**

Confirmed in passing: the TLS server binds `in6addr_any` — **all interfaces, loopback included** — on
an **ephemeral** port (mDNS-advertised, not 5555). So there is no interface obstacle once it runs.

### Independent corroboration that this is closed by design

`droserasprout/io.drsr.hotspotadb` is an Xposed/LSPosed module whose entire purpose is removing this
restriction. It hooks the exact three call sites and injects a **synthetic** `AdbConnectionInfo` with a
forged BSSID, because the real call returns null. **Magisk + Zygisk required; no non-root variant.**
When a root module has to fabricate a BSSID, the door is shut.

### Also: the teardown is continuous

A receiver disables wireless debugging on Wi-Fi off, disconnect, empty BSSID **or a BSSID change from
roaming**, and teardown calls `kick_all_tcp_tls_transports()`. So "enable it at home and walk out" was
never going to survive the walk either.

### 🔑 What this pass is actually worth: the recovery is far cheaper than we tell users

Neither gate consults `NET_CAPABILITY_VALIDATED`. **A bare association with no internet passes.** So
recovery is not "get home to Wi-Fi", it is:

- **another phone's hotspot** — the maintainer carries two;
- a café or hotel AP nobody logs into; a car's Wi-Fi; a travel router with nothing plugged in.

Our copy says "needs Wi-Fi", which users read as "needs internet". **That is one string, and it turns a
dead end into a ten-second fix.**

### One free diagnostic worth wiring in

`logcat -s AdbDebuggingManager` — the line `Not connected to any wireless network. Not enabling
adbwifi.` settles this entire class of bug report instantly. Worth capturing specifically, given the
256 KiB ring rotates within minutes.

### 🆕 A competitor nobody had found: `LyoSU/cally`

https://github.com/LyoSU/cally — *"Pixel call recorder for stock Android via Shizuku — no root...
Dual-track VOICE_UPLINK+DOWNLINK with 5-step fallback ladder... opt-in cloud transcription."* Almost
exactly CallVault's shape, cloud-optional where we are on-device. **It was not in the 2026-08-27
competitive research.** Worth its own look.

## ⛔ The MIC-fallback idea is DEAD — and it would have been worse than doing nothing

I proposed recording from the app's own uid when the bridge is down, and framed the cost as a privacy
trade. **That was wrong on the facts.** Verified against AOSP source at every tag from 12 to 16:

**An ordinary app uid capturing during a call receives digital zeros.** Not degraded audio, not the
near party, not a quiet far party — `AudioFlinger` `memset`s the buffer to 0 while `startRecording()`
reports success. `AudioPolicyService::updateUidStates_l()` sets `allowCapture=false` for any uid
lacking `CAPTURE_AUDIO_OUTPUT` or `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`.

- **Speakerphone makes no difference** — the decision references no route or device; it is
  route-independent by construction.
- **VoIP is the same gate** — `is_state_in_call()` covers `MODE_IN_COMMUNICATION` too.
- **Every source is remapped anyway.** `Engine.cpp` rewrites MIC/VOICE_RECOGNITION/CAMCORDER to
  `VOICE_COMMUNICATION` during a call, which routes to a physical mic with **AEC and NS applied** —
  the one path engineered to remove the speaker leakage an acoustic fallback would need.
- **MediaProjection cannot reach it either**, twice over: carrier downlink is a device→device patch
  that never consults mix matching, and the VoIP usage allowlist for an ordinary app is exactly
  `{UNKNOWN, MEDIA, GAME}`. 🚨 The trap: `addMatchingUid(<voip uid>)` **registers successfully and
  yields silence** — CTS asserts precisely this. It would have produced a clean-looking file of zeros.
- **Bluetooth SCO** is closed twice: `startBluetoothSco()` is ignored during a call, and the SCO mic
  never carries Telephony Rx.
- **No role or permission on 14/15/16 helps.** The three roles granting call-audio permissions are all
  `systemOnly`, `visible="false"`, static.

**So a "degraded recording" is not available at any quality. The only thing on offer was a silent file
that passes every size, waveform and success check** — which is strictly worse than recording nothing,
because it fails invisibly. Do not revisit.

**Why the shell bridge works, stated properly:** `packages/Shell/AndroidManifest.xml` grants exactly
`CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `CALL_AUDIO_INTERCEPTION`, `CAPTURE_MEDIA_OUTPUT` and
`CAPTURE_VOICE_COMMUNICATION_OUTPUT`. Shell is the only uid on a stock device holding that set. The
bridge is not a workaround we found; it is the only door in the building.

**History, for the record:** Android 9 added the `CAPTURE_AUDIO_OUTPUT` gate and the in-call source
remap — that is when the direct door closed, not Android 10. Android 10 then closed the acoustic
fallback. The accessibility + `VOICE_RECOGNITION` loophole is still live in AOSP `main` but only
*un-silences*; the remap still applies, so it yields the AEC'd uplink mic — near party only, far party
actively cancelled. Play-banned since 2022-05-11. That is what Cube ACR ships.

## 🆕 Two things this turned up in OUR code

1. **`AudioRecordingConfiguration.isClientSilenced()` — public API since API 30.** It is the supported
   way to detect that a capture is being fed zeros. **Any path that only checks "did `startRecording()`
   succeed" reports false success on every blocked route.** This is directly relevant to our
   silent-recording bug class and is worth wiring in regardless of anything else here.
2. 🚨 **Possible Android 13 VoIP gap.** `CAPTURE_VOICE_COMMUNICATION_OUTPUT` was added to
   `packages/Shell/AndroidManifest.xml` **only in Android 14** — absent at `android-13.0.0_r1`. Our
   VoIP loopback path depends on it, so on Android 13 and earlier the shell daemon may be unable to
   register that mix at all. OEM Shell manifests vary. **Wants on-device confirmation before anyone
   files it as a bug** — see [[voip-recording-feasibility]].


Record from the **app's own uid with plain `MIC`**, when the privileged bridge is down. No ADB, no
shell, no Wi-Fi. It captures the near party always and the far party only on speakerphone — the
behaviour of every pre-ADB call recorder.

🚨 **The cost is not technical, it is what CallVault currently is.** The app declares **no
`RECORD_AUDIO` at all** — every capture happens in the shell-uid daemon, and the app process never
touches the microphone. That is a real privacy property and arguably a selling point. Adding a MIC
fallback trades it for degraded recording in a rare state. **Do not do this without deciding that
trade explicitly.**

## What is still worth doing, and it is small

We cannot fix it. We can stop it being **silent**, which the demand research says is what users punish
hardest — a bridge that collapses quietly is the top reason people abandon this category (24 sources).
Today the app tries to re-arm, fails, logs a warning, and the user finds out by missing a call or by
opening the app and reading the status card.

The mitigation is a **notification when offline recording is enabled and the loopback listener is
down** — "recording is paused until this phone reaches Wi-Fi once" — fired after boot rather than at
the moment a call is missed. That is a small, contained piece of work and it converts the worst
property of this hole (invisibility) into an inconvenience.

**And the README must state the limitation plainly** rather than leaving it to look like an oversight.

### 🅿️ Capture fallback ladder with RMS audibility check — PARKED 2026-08-29, before any work

`cally`'s design, and the competitive research called it "the single most valuable idea in the
landscape for CallVault": try capture strategies in order — `DualUplinkDownlink → DualMicDownlink →
SingleVoiceCallStereo → SingleVoiceCallMono → SingleMic` — measuring RMS against an adaptive noise
floor on each stream, dropping a rung when a stream is silent, and caching the winner per device
fingerprint.

**Parked without starting, on the maintainer's call, and the reasoning is worth keeping:** the ladder
is insurance against silent recordings, and *we do not have a silent-recording problem*. Carrier and
VoIP capture are both reliable in daily use, with no user reports in a long time; the one incident it
was argued from has been downgraded above as self-inflicted. It is a large change to the most
safety-critical code in the app, bought to fix something that is not broken.

**What we do have, if this is ever revisited:** `VoipCaptureSession.farPartyHeard` is a peak-threshold
audibility check on the VoIP path only. The carrier paths judge the *file* (`CallOutcome`, under 1 KB
→ `NO_AUDIO`), which catches an empty file and not a full-length recording of silence. Nothing
anywhere retries with a different strategy, and nothing caches per device.

### 🅿️ E1 — non-UI `InCallService` — spike done, PARKED 2026-08-29

**Parked after the spike answered the question, not before.** The mechanism works; the reason it was
top of the list does not. Carrier calls would gain an authoritative number, direction and state at the
earliest moment, plus a foreground lift — a solid improvement, no longer a structural one, because the
VoIP half is impossible (see below). Spike reverted; the design and every measurement are kept here so
picking it up again costs nothing.

**To resume:** re-apply `da035bf`, re-grant the companion role, and start from
`CallSessionManager` — the InCallService becomes the authoritative source for carrier calls with the
broadcast path as fallback, and the two must not race. Also: any grant step **must read the value
back**, because `appops set` returns exit 0 while doing nothing on both OnePlus ROMs.

Telecom binds our app at `onCallAdded` — the earliest moment a call exists — giving the number,
direction, state and the VoIP/carrier distinction **by construction** rather than by inference. The
bind also lifts the process to foreground, which is why BCR says it avoids Android 12+'s background
microphone limitation and needs no boot receiver.

**Prior art, already studied (see `research/2026-08-27-competitive/09-capture-techniques.md` §1.7).**
BCR uses `CONTROL_INCALL_EXPERIENCE`, which needs root or system. **Our own upstream,
ShizuCallRecorder, found the non-root equivalent**: the `MANAGE_ONGOING_CALLS` appop is
`signature|appop`, so an appop grant is a legitimate path, and Telecom's `InCallController` has a
single `||` on it. Works Android 12–16; Android 11 has no appop branch and cannot.

## 🚨 The documented primary path is DEAD on the maintainer's phones — measured 2026-08-29

| | OP12 · OxygenOS · Android 16 | OP9 · ColorOS · Android 14 |
|---|---|---|
| `appops set … MANAGE_ONGOING_CALLS allow` | ❌ silently ignored, **exit 0** | ❌ ignored |
| `appops set` for *any* op (control) | ❌ also ignored — it is the ROM, not the op | ❌ |
| `cmd companiondevice associate … COMPANION_DEVICE_WATCH` | ✅ association created | ✅ association created |
| `cmd role get-role-holders … COMPANION_DEVICE_WATCH` | ✅ **holds the role** | ❌ **role not held** |

So the `companiondevice` route is not a fallback here, it is **the** route — and it is confirmed
working only on Android 16. On Android 14/ColorOS the association exists while the role does not, so
the capability may not follow.

⚠️ **`appops set` returning exit 0 while doing nothing** is the trap: any code that grants and assumes
success will believe it worked. Read the value back, always.

⚠️ Running `associate` twice creates a **duplicate** association. Done accidentally on the OP9 while
testing and removed with `cmd companiondevice disassociate 0 <pkg> <mac>`.

⚠️ A fake association was left on **both** phones during this research (mac `00:11:22:33:44:55`). It
does not appear in Settings' paired list and is removed on uninstall.

## 🚨 A WhatsApp call produces NO callback — the VoIP case does not work, and cannot

Measured on the OP12, 2026-08-29, immediately after the carrier call above. An 18-second WhatsApp
call was recorded normally by CallVault (`…voip-WhatsApp…ogg`, `farPartyHeard=true`) and **our
`InCallService` received nothing at all** — no `onCallAdded`, no `onCallRemoved`, with the logcat ring
still holding the carrier call from two minutes earlier.

`dumpsys telecom` says why, and it is not our binding:

    12:36:29  Enter SIM_CALL / MODE_IN_CALL / TC@161            ← the carrier call, we saw it
    12:38:59  CommSess{uid=10394, created=12:38:42, callId=none} ← WhatsApp: a session, not a Call

**`callId=none`.** WhatsApp never registers its calls with Telecom as a self-managed
`ConnectionService`, so Telecom has no `Call` object to hand anybody. No `InCallService` — ours,
BCR's, or the dialer's — can see a call that was never given to Telecom. `supportsSelfMg?true` on our
bind is necessary and not sufficient: it says we *would* be told, if anyone told Telecom.

**This corrects the research** (`09-capture-techniques.md` §1.7 and the synthesis), which presented the
VoIP/carrier distinction as coming "by construction" from the bind. For apps that register with
Telecom — Signal uses `ConnectionService` — it would. For WhatsApp on this phone it does not, and
WhatsApp is the maintainer's main VoIP case. **Untested: Signal, Telegram.**

**What survives, and it is still worth having:** carrier calls gain an authoritative number,
direction and state at the earliest moment a call exists, plus the foreground lift. And the *absence*
of a callback becomes a usable negative signal — if our own VoIP detection fires and Telecom said
nothing, it really is not a carrier call. That is weaker than the research promised but is still
better than inferring both sides.

**The OP9 is a useful negative control** — it holds the association but *not* the role. If it binds
there too, the role is not what is doing the work and the mechanism is something else.

## Next step is a spike, not the feature

`telecom is-non-ui-in-call-service-bound com.baba.callvault` returns **false** on both, which proves
nothing yet — we have no `InCallService` declared, so there is nothing for Telecom to bind. The
decisive test is a manifest entry plus an empty service: if Telecom binds it, E1 is worth building
properly; if not, an hour was spent instead of days. **Do that before writing any of the feature.**

### ✅ Remove the "Transcribe again" button — VERIFIED 2026-10-12 on `fix/transcribe-again-done-only` (agreed 2026-08-29)

> **✅ VERIFIED 2026-10-12 on device by the maintainer ("#4 is fine").** Installed in the combined build
> on OP9 + OP12. Merged into `main` 2026-10-12 (`753f5fbe`). Detail below kept for the rationale.
>
> **BUILT (build + full unit suite green):** the retranscribe button + `onRetranscribe`
> param are gone from the reading view (the DONE case); `TranscriptAudio.retranscribeOffered` and its two
> tests removed. A FAILED transcript keeps its retry on the Transcripts row (`onRetry -> startTranscription`),
> so an undecodable one is never stranded — exactly the safeguard this item required. Commit `ef755692`.
> **Installed on OP9 + OP12 2026-10-12** (combined build; see note at end). Verify on device: open a DONE
> transcript (no "Transcribe again" button) and a FAILED one (retry still offered on its Transcripts row).

Drop the retranscribe action from the transcript sheet before the release.

**Why.** It exists because the model or the language pin might have been wrong, which was a real
worry while transcription was being built and the defaults were still moving. Once the version ships
with a settled model and a pinned language it stops earning its place: it costs minutes of CPU and
battery, it is one of several actions on a sheet the user reaches to *read* something, and the failure
it repairs is one they will now almost never hit.

🚨 **Do not simply delete the call.** `TranscriptRepository.retry` is also how a FAILED transcript is
retried, and the nightly queue deliberately skips FAILED so an undecodable file is not attempted every
night for ever. Removing the button must leave a way to retry a *failed* one — otherwise a recording
that failed once can never be transcribed again by any route. The likely shape is: keep retry where
the row shows FAILED, drop it where the transcript is DONE.

Agreed with the maintainer on 2026-08-29: "it won't be necessary once we release the version."

### 🟢 Seven calls recorded zero audio on the OP12 — DOWNGRADED 2026-08-29, likely self-inflicted

**The maintainer's assessment, 2026-08-29:** these almost certainly came from a period of active
experimentation with capture code, not from a defect in a shipped build. *"Except for cases where we
have been playing around with features and code, the app is really stable — it records both cell and
VoIP reliably and I haven't gotten any issues about that in a long time."*

Kept rather than deleted, because the log evidence below is real and would matter if it recurred on a
build nobody was changing. But it should **not** be cited as an open field defect, and it was — the
capture fallback ladder was argued for partly on its strength.

### 🔵 Original report — kept for the evidence


Found while verifying the 2.1.0 install on the maintainer's daily driver. Seven consecutive **carrier**
calls, 11:43 → 13:16 on 2026-08-25, each produced a file of **exactly 98 bytes**:

```
11:43  in   גבריאל 2b     98 B      13:02  out  יצחק 2b לוי   98 B
11:56  in   גבריאל 2b     98 B      13:09  out  גבריאל 2b     98 B
12:36  out  גבריאל 2b     98 B      13:16  in   גבריאל 2b     98 B
12:37  in   גבריאל 2b     98 B
```

98 bytes is `OpusHead` + `OpusTags` and then end-of-file — both Opus headers (mono, 48 kHz, pre-skip
312) and **not one audio packet**. `ffprobe` reports `End of file`; the playback screen draws a flat
line and the row shows `0 KB`. The encoder was initialised and the container opened; no PCM ever
reached it.

**The window is clean and bracketed.** `11:32` before it is 1.5 MB and healthy; `16:08`, `16:10`
(WhatsApp) and `16:12` after it are all healthy. Nothing outside 11:43–13:16 is affected, on any day.
The device is in **standalone** mode — Shizuku is not installed on it — so the wrong-host and
stale-service failure modes in [[only-one-recorder-host]] and [[shizuku-mode-capture-rules]] are ruled
out as written.

**Why it is not diagnosed.** The debug log for that window was deleted before anyone looked, so the
decisive evidence is gone — the same way it was gone for the stuck-microphone report above. The two
candidate stories the artefacts cannot separate:

1. **Collateral from that morning's testing.** 11:43–13:16 is exactly when the mode-switch, keep-alive
   and WD-lease bugs were being worked, and this phone was the far end of those test calls. A daemon
   killed or a host torn down underneath a live capture would look precisely like this.
2. **A real capture failure that the ~16:01 install happened to clear** — in which case it can ship.

Nothing in a 98-byte file distinguishes those, and guessing between them is how two wrong conclusions
got reached earlier the same day.

**The plan, agreed with the maintainer 2026-08-25:** logging is switched back on on the OP12 and this
waits for a recurrence. It is **not** a release gate — three later calls on the same phone, carrier and
VoIP, recorded, transcribed and summarised correctly.

**If it recurs, the log now answers it** — which is what the diagnostics pass that day was for. Look for,
in order: the `CaptureAudit` line for the capture (was a microphone opened at all, and what did
`release()` actually do), the daemon's own merged `CV:RecorderServer` / `CV:DirectCapture` lines (did
the `AudioRecord` ever reach RECORDING), the config header (mode, resilient, transport), and whether a
teardown or mode switch lands inside the call. See [[diagnosing-a-user-report]].

### 🔵 Transcribing long calls — the blow-up is our decoder, not whisper — researched 2026-08-26

The 15-minute refusal exists because `AudioDecoder` builds the **entire** file in memory, four times over,
before whisper sees a single sample (`AudioDecoder.kt`, `decodePcm`):

```
sink.write(chunk)               // whole file accumulates in a ByteArrayOutputStream
val bytes = sink.toByteArray()  // full copy #1 — both alive at once
val shorts = ShortArray(...)    // full copy #2
pcm16ToMonoFloat  → FloatArray  // full copy #3
resampleTo16k     → FloatArray  // full copy #4
```

A 60-minute call at 48 kHz mono 16-bit is ~346 MB of raw PCM, and `ByteArrayOutputStream` doubles its
buffer as it grows, so the peak is ~700 MB before the copies begin; the float array is another ~690 MB.
At 15 minutes it is already ~170 MB with copies, which is exactly why the limit sits there.

**Whisper is not the constraint.** It works on 30-second windows natively and never needs the whole
file. We hand it everything because our decoder produces everything.

**The fix (Option A): stream the decode and call whisper per window.** No runtime change, no model
change, no re-download, and speaker labels survive — they are built on our own two-channel capture and
would not survive a runtime move. Care needed on window boundaries so words are not cut in half;
overlap or VAD-aligned cuts are the usual answer.

**Rejected: switching to sherpa-onnx / ONNX Runtime.** Investigated properly because a user pointed at
[anti-vocale](https://github.com/RisorseArtificiali/anti-vocale), which advertises selectable NNAPI and
long-audio support. Both claims dissolve on inspection:

- **It caps at 10 minutes** (`AudioPreprocessor.kt:49-50`) — tighter than ours. It has not solved this.
- **Hebrew is the killer.** sherpa's Whisper path has an unfixed preprocessing bug (#2900), and its
  non-Whisper models do not speak Hebrew. Already settled in
  `docs/dev-notes/2026-08-16-on-device-transcription-design.md:76-81`.
- It would cost every user a 326–988 MB re-download and add a second runtime beside llama.cpp.

**On NNAPI specifically, so nobody re-litigates it:** it *is* reachable through ORT, and ORT on F-Droid
is a solved problem — `dev.davidv.translator` builds it from source and reproduces bit-for-bit on the
buildserver. But ORT's own docs warn the NNAPI provider falls back to `nnapi-reference` for unsupported
ops, which is **slower** than ORT's optimised CPU kernels; NNAPI was deprecated in Android 15; and
Google states it expects most devices to use the CPU backend in future. **Qualcomm QNN is closed to us
outright** — the Maven artifact declares `Qualcomm AI Hub Model License` / `scm:not_public`, and F-Droid
explicitly rejects the "but it is on Maven Central" defence.

### 🅿️ In-app bug report / feature request → GitHub — designed 2026-08-26, PARKED

Asked for, costed, then parked in the same session. Park is deliberate: it depends on the log
pseudonymisation landing first, because the whole premise is that reports go to a **public** repo.

**Three shapes, and only one is worth building.**

| | Cost | Verdict |
|---|---|---|
| **A. Prefilled issue link** — open `github.com/<repo>/issues/new` with an Issue Form template and query parameters filling the fields | ~1 day | **This one.** No auth, no shipped secret, no backend, no new dependency, nothing for F-Droid to object to. |
| **B. OAuth device flow** — user signs in, issue posted as them; device flow needs only a public `client_id`, so no secret ships | several days | Buys little over A, which already lands them on a filled-in form. Still cannot carry the log — GitHub caps an issue body at 65,536 chars. |
| **C. Backend proxy holding a token** | highest, ongoing | Only if you want reports from people without GitHub accounts. Needs hosting, rate limiting and real abuse control — an unauthenticated endpoint that creates public issues **will** be found and spammed — and the report would transit our server, undercutting the privacy story. |

**Build two entry points, not one:**

- **Request a feature** — carries no user data at all, just app version and device.
- **Report a bug** — prefills only the **config header** (mode, app version, Android version, device, and
  the settings that change the capture path). A few hundred bytes, fits comfortably in a URL, and it is
  exactly the block that answers the first three questions of any report. The log stays a deliberate,
  separate attachment that the user chooses.

**Non-negotiables, whichever shape:** never auto-submit; show exactly what will be sent before sending;
default to *not* including the log. An app that silently uploads diagnostics contradicts its own README,
which states analytics and crash reports "None exist".

**What already exists** and does not need rebuilding: the report export writes
`cacheDir/logs/callvault_debug_report.txt` and is shared through the FileProvider, and the app already
talks to `api.github.com` unauthenticated for release checks.

**The dependency:** phone numbers are redacted, but **contact names were not** — they ride into the log
inside recording filenames (`<timestamp>_<direction>_<contact name>.ogg`). Pseudonymisation is being
implemented separately. Do not ship a button that encourages users to attach logs until that has landed.

**Known limitation of option A:** it needs a GitHub account, so some users will file nothing. Ship A
first and find out whether that is actually a problem before paying for C.

### 🔵 F-Droid reproducibility traps that already apply — noted 2026-08-26

These bite any app shipping native code, and CallVault already does:

- **`.so` files are stripped by Gradle by default**, which breaks reproducible builds — needs
  `packagingOptions { doNotStrip '**/*.so' }`.
- **Pin the NDK exactly.** The same NDK version on different host platforms still produces differing
  binaries.
- **16 KB page alignment** — `zipalign --page-size 16 --pad-like-apksigner`.

The existing CMake/submodule setup is otherwise already on the right side of F-Droid's scanner, because
anything compiled in the `build:` phase runs after the scan and never needs a `scanignore`.

### 🔵 A call skipped for being too long says so only in the log — found 2026-08-25

The 15-minute transcription limit is now enforced in `TranscriptionRunner.runOne` and
`TranscriptionQueue.pending`, so every route inherits it — nightly sweep, per-call, manual tap and
queue drain alike. Before that it lived at the manual tap alone, and automatic transcription walked
straight into the OOM the limit exists to prevent.

**What is still missing is the user's side of it.** The tap raises a dialog, but that dialog only
fires when the **call log** knows the duration. When the call log has no duration and the container
does — which is exactly the case for app calls — the recording is skipped with nothing but a `W`
line to show for it, and the row simply never transcribes. No status, no explanation.

There is no cheap surface to reuse. `TranscriptEntry.errorMessage` is stored but rendered nowhere,
and `TranscriptStatus`/`TranscriptRowAction` carry only NONE/BUSY/OPEN/RETRY. Reusing `FAILED` would
be worse than the gap: a red retry icon with no reason, **and** it would bar those calls from every
future automatic run — including the ones that become possible the moment the limit is lifted.

**The fix** is a `TOO_LONG` status, a row action for it and one string — roughly half a day. It
disappears entirely once long calls are transcribed in pieces, so weigh it against just doing that.

Related: the skip is deliberately *not* recorded as a failure, so nothing has to be un-marked later.

### ✅ Shell mic left on after a carrier call — CLOSED 2026-10-12 (maintainer, on field silence); auto-heal shipped v2.3.0 (reported 2026-08-25)

> **✅ CLOSED 2026-10-12 by the maintainer on field silence** — ~1 month since the fixes shipped (v2.3.0) with
> no recurrence reported. Promoted on absence of reports, not a captured stuck-dot heal. **Residual gaps still
> on the record:** the `NO_WAY_BACK` gate leaves a phone with WRITE_SECURE_SETTINGS off + Wireless-debugging off
> and no armed loopback un-auto-cleared, and the Shizuku path is excluded by design — reopen with those in mind
> if a stuck-dot report ever returns. The "UNDIAGNOSED" framing below was stale; authoritative write-up:
> `docs/dev-notes/2026-09-05-stuck-mic-case-file.md`.
> **Cause:** when AudioFlinger invalidates a handoff capture mid-call (`CBLK_INVALID`), the recording is
> rebuilt whole but the shell-uid (2000) `RECORD_AUDIO` app-op from the *invalidated* capture keeps running —
> dropping the last ref runs `releaseInput()` (clears the client) without `stopInput()`/`finishRecording()`,
> so the green dot stays lit with nothing recording.
> **Fixes (all in v2.3.0 → v2.4.5 and on `main`):**
> (a) stop-before-release at call end — `HandoffReceiver.releaseRefs()` (`a5efcd8c`);
> (b) stop the invalidated track before the mid-call rebuild — `HandoffReceiver.kt:430` (`9257efbc`) — correct
> hygiene but proven **insufficient alone** (AudioFlinger accepts a stop on an already-invalidated track but
> never finishes the op);
> (c) **the reliable one — `MicOpAutoHeal`** (`32248851`, v2.3.0-rc20): 8 s after a call ends, reads the shell
> uid's running mic ops via the daemon and, if one is running with nothing recording, restarts the daemon (the
> keep-alive relaunches it) which clears the op. Wired at carrier end (`RecordingForegroundService.kt:599`) and
> VoIP end (`VoipRecordingCoordinator.kt:756`).
> **Why it stays 🧪, not ✅:** (1) no report has yet confirmed the auto-heal clearing a *genuinely* stuck dot —
> only a no-regression pass (OP12, clean call → no spurious kill); (2) the `NO_WAY_BACK` gate (`MicOpHealPolicy`)
> refuses to restart the daemon unless it can be relaunched (needs WD on, OR an armed offline-recording
> loopback, OR WRITE_SECURE_SETTINGS + Wi-Fi) — the original reporter's config (WSS off, WD off) is exactly the
> population left un-auto-cleared; (3) STANDALONE only — the Shizuku path is excluded by design. Settle this
> with a stuck-dot report on an auto-heal-capable phone. Original 2026-08-25 investigation kept below.

A user on **1.5.8** reported that after an ordinary carrier call (not VoIP) the shell process stayed
alive with the microphone on. A force-stop cleared it; no reboot needed.

**What the two reports do show.** At 10:58:02–04 the system log has `AudioPolicyService:
updateUidStates_l() current->uid=2000 current->pid=26306 allowCapture=1`, repeatedly — a **shell-uid
process in an active capture state four minutes after the last call ended** (10:54:02). That is
consistent with the report.

**What they cannot show, and why.** The app-side teardown is complete and identical for both recent
calls: `stop requested` → `HandoffEncoder finished` → `handoff encode DONE` → `handoff capture input
released`, with **no `stopHandoff` failure logged**. But whether the *daemon* released its own
`AudioRecord` is invisible:

- The debug export is **app-process only** — it contains no `CV:RecorderServer`, `CV:HandoffSource` or
  `CV:DirectCapture` lines at all, so `releaseHeld` could not appear in it either way.
- The system report *would* keep those tags (the filter passes everything `CV:`), but it is truncated to
  the most recent 69 matching lines and covers **10:58:02 → 10:58:19** — seventeen seconds, ending four
  minutes after the call. The teardown had already rotated out of the logcat ring.

So the decisive evidence was gone before the report was taken. **Fixing that is the first job**, not
guessing at the cause.

**Fixed already, because it is provable without the missing logs:** `RecorderConnection.service?.
stopHandoff()` on a null binder was a silent no-op that `runCatching` reported as success — "the daemon
released its microphone" and "there was nobody to ask" wrote identical logs. Both outcomes are now
logged distinctly, in the handoff and daemon paths alike. That does not explain the report, but it
removes one way the logs could hide it.

**Next, in order:**
1. Make the daemon's teardown visible in the app's own debug export, or make the system report cover
   enough history to include the call — the ring was grown to 8M at 09:01 and the export still scanned
   only 9,865 lines, so the growth needs verifying rather than assuming.
2. Ask the reporter whether it is reproducible, and for a report captured **within a minute** of the
   call while the mic indicator is still lit.

### 🔵 Direction for VoIP calls — investigated 2026-08-25, parked

A phone call shows incoming/outgoing; an app call shows only which app it came from. Whether the
direction is obtainable at all was investigated rather than guessed:

**The call log cannot answer it.** Measured on the OP9: 3,000 rows, and *every one* written by
`com.android.phone/…TelephonyConnectionService`. WhatsApp writes nothing there, so there is no entry to
read a direction from. (The same fact is why a VoIP recording had no duration until it was read from the
file container instead.)

**The one real route is the calling app's notification.** Android 12+ `CallStyle` notifications carry
`android.callType` — 1 incoming, 2 ongoing — and `VoipCallerName` already parses that dump for the
caller name, so reading one more field is nearly free.

**What blocks it is timing, not access.** A VoIP call is detected when the audio mode becomes
`MODE_IN_COMMUNICATION`, which for an *incoming* call is **after the user answers** — by which point the
notification has flipped from incoming to ongoing. Sampled there, both directions report "ongoing".

Getting it right means sampling while the phone is still ringing, and each way of doing that has a cost:

| Approach | Cost |
|---|---|
| Hook the earlier `MODE_RINGTONE` transition | cheap — **if** the calling apps set it, which is unverified |
| Poll the notification dump while idle | the daemon shells out per dump; battery, and it does not work in Shizuku mode |
| `NotificationListenerService` | exact and real-time, but needs notification access — a heavy permission for a call recorder to ask for |

**The experiment that decides it** takes five minutes: ring the phone on WhatsApp and, *before
answering*, capture `dumpsys notification --noredact` and the audio mode. If `android.callType=1` is
present and the mode passes through `MODE_RINGTONE`, the cheap option works. If not, drop the idea.

Until then the current behaviour is honest: the app badge says where the call happened, and no direction
is invented that we cannot know.

### 🔵 The release gate, agreed 2026-08-24

Judged by **which failures are silent**, since a call recorder's worst outcome is losing a call
without anyone noticing.

| # | Item | Why it blocks |
|---|---|---|
| 1 | ~~Silent VoIP failure~~ | **DONE 2026-08-24.** See below. |
| 2 | **B8 — the speaker tap must not harm recording** | Highest severity left. This exact failure already happened once: the downlink probe silently took the near side off a recording and looked completely normal in the logs, the file size and the waveform. The tap now runs on every call. |
| 3 | **B5 — deletion and privacy** | Reading it on 2026-08-24 already found one: the retention sweep's *untracked* half deleted files without cascading, so transcripts outlived the recordings. Fixed (`UntrackedCascade`); the device checks are still unrun, and it is the privacy promise, not a nicety. Transcripts are full searchable text of private calls in a *separate* database with the cascade enforced by code rather than a foreign key. The retention-sweep path matters most — that is how calls actually expire. |
| 4 | **Transcription re-verified broadly** | Reduced scope: **one long call and one non-Hebrew language**. The last device pass found that every transcript came back empty for every user by default (`detect_language` means *exit after detecting*). Nineteen months of upstream whisper change deserves better than ten-second clips. |

**Explicitly NOT blocking: re-measuring transcription speed.** The estimate recalibrates itself from
real runs, so it heals in the field, and being wrong costs a misleading figure on a dialog that
already says you can stop at any time. The long call in #4 re-measures it for free.

**Also done 2026-08-24, not on the original list:** B7 rewritten (it still tested the deleted
ringback detector), and the CHANGELOG corrected — it told users the attribution came from the
network's ringback tone, which is both deleted and never true on this phone.

### 🔵 App calls get no speaker labels — found 2026-08-24, not scheduled

`VoipCaptureSession` is a **third** capture path beside the daemon's direct one and the app's handoff
one, and the only one with no `SpeakerTurnDetector`. So VoIP transcripts cannot be labelled at all.

Galling, because VoIP is the *easy* case: the two directions arrive as separate streams interleaved
LEFT near, RIGHT far, so the mapping is known by construction with nothing to infer. Adding the
detector is small. The real question it raises: the trusted mapping is currently **one global value
per device**, while VoIP's is fixed and the carrier's is an OEM detail — the two cannot share one
value on a phone where they disagree.

### 🔵 "A call was not recorded" may be a false positive

On 2026-08-21 the status card warned **"A call was not recorded — 13:06"**, and
`20260821_130658.098+0300_in_גבריאל 2b.ogg` (689 KB) was sitting in the recordings folder the whole
time. Either the warning fires on a recording that succeeded, or the recording finished without the
app registering it.

Worth chasing because this warning is the one the user is meant to trust: it is how a genuinely
missed call gets noticed at all, and a warning that cries wolf is worse than no warning. Observed
once, not reproduced, cause unknown.

Two other list oddities seen the same day, possibly the same root: a recording present on disk did
not appear in the list at all (today's 08:54), and the saved count moved 61 → 60 → 59 across a
session without anything being deleted.

### 🔵 Known gaps, agreed but not done

- **Per-recording summaries** — SHIPPED in 2.0.0. Plan at
  `docs/dev-notes/2026-08-21-summarisation-ui-plan.md`.

- **A Hebrew whisper fine-tune (`ivrit`) — RESEARCHED, PARKED 2026-08-23 at the maintainer's
  request.** Likely the largest remaining transcription-quality lever, and the research is done, so
  picking it up again is a decision rather than an investigation.

  The sibling project at `~/Desktop/Projects/AIDashboard` (`src/lib/calls/transcribe.ts`) documents
  that generic whisper on auto-detect **returns Arabic on Hebrew audio**, and that `ivrit` +
  `--language he` produced 6564 characters of clean Hebrew from a call the other paths mangled.

  | Candidate | Size | Licence |
  |---|---|---|
  | `ivrit-ai/whisper-large-v3-turbo-ggml` (official, fp16) | 1.62 GB | Apache-2.0 |
  | `Ibrerhim/ivrit-whisper-v3-turbo-q5_0-ggml` | 574 MB | **none stated** |

  **APK cost is zero** — models are downloaded, never bundled. The q5_0 build is byte-identical in
  size to the generic turbo we already ship and has a different SHA-256 (`6c1da92e…` against
  `39422170…`), so it is a real fine-tune rather than a re-upload. The open question is which to
  depend on: an unlicensed third-party re-quantisation, or three times the download.

  **Two constraints on "make it seamless".** The model has to be chosen at *download* time, not at
  transcribe time, so auto-detect cannot select it — the language is not known until after the
  transcription. And `ivrit`'s own language detection is degraded by the fine-tuning, which is why
  the sibling pins the language explicitly. Realistically: pinning Hebrew in Settings gets `ivrit`,
  auto-detect keeps generic.
- **Estimates are still one number times a length.** A short recording pays a fixed cost — loading a
  model, decoding the audio — that a real-time factor cannot express, so short calls are quoted
  optimistically. A two-part estimate (fixed overhead plus a per-second factor) would fit reality
  better. Not started; raised on 2026-08-21 after a 1:17 call was quoted two minutes.

---

## Current state — 2026-08-05, status reconciled against the tags 2026-08-14

Kept at the top so a session can start from disk instead of from recall. **Update it whenever a
release is cut, a branch lands, or something starts or stops being blocked.**

**Released:** `v1.5.7` is the latest release users can get (versionCode **10720**, tag `v1.5.7`, asset
`CallVault.apk`, published 2026-08-04 and verified byte-identical to the locally built artifact). Also
published, both **pre-releases** invisible to the in-app updater and **never to be merged**:
`v1.5.2-diag-scrcpy` (issue #18, branch `diag/scrcpy-only`) and `v1.5.7-loopbackdiag` (issue #22,
branch `diag/loopback-oneui`).

**What 1.5.7 shipped:**

- **Daemon + system log collection** (`2026-07-28-daemon-and-system-logs-design.md`). Ring grows on
  logging-enable and restores on disable; Share attaches a filtered, redacted logcat slice. Verified
  on the OP12.
- **CodeQL triage** of 2026-08-01: ten alerts dismissed with rationale on the alert itself, three
  relative-path-command **fixed** (`sh`/`pkill` by absolute path).
- **Retention actually deletes what it promises** — four faults, all found by measuring the OP12 on
  2026-08-04 and all fixed and device-verified the same day. See below.
- **The USB-mode warning is no longer hidden behind readiness**, `UNKNOWN` is surfaced instead of
  silently treated as safe, One UI 8's "Debugging only" is recognised as safe, the mode is resolved
  from `sys.usb.config` where `dumpsys usb` omits it, and the picker no longer spins on a confirming
  read-back that could not work. Device-verified except the `COULD_NOT_CHECK` message, which needs a
  phone whose mode was never read.

**Open:** issue **#22** (Galaxy S25, One UI 8.5) — the reporter holds `v1.5.7-loopbackdiag` and has not
yet sent logs. Nothing in 1.5.7 addresses their loopback failure. **1.5.7 will be offered to them as a
normal update, which replaces the diagnostic build and loses its instrumentation** — tell them not to
update if those logs are still wanted. Issue **#18 is closed**: the reporter found the cause himself
(Meta Ray-Ban glasses), written up in `2026-08-04-bluetooth-headsets-and-silent-recordings.md`.

**Waiting for the next release — this is the whole list, one item:** `fix/voip-carrier-collision`
(`75868ca`) — a carrier call could be mistaken for an app call on ROMs that route calls over IMS.
Merged to `main`, unit-tested, **not yet run on a device**; see the section below for what to check
when it ships. Note it is on **local `main` only** — `main` sits 4 commits ahead of `origin/main`.

**What each recent section actually shipped in, verified with `git ls-tree` on 2026-08-14.** The
sections below are the detail; this table is the truth about *release membership*:

| Entry (implementing file) | Shipped in |
|---|---|
| Keep-alive rewarm latch (`RewarmGate.kt`) | **v1.5.5** |
| Upload schedule in Settings, issue #20 (`SyncScheduleLabels.kt`) | **v1.5.5** |
| Settings "General" restructure (`SettingsSidebar.kt`) | **v1.5.5** |
| Resilient-recording ring + guard fix (`handoff/HandoffGeometry.kt`, `GUARD_FRAMES = 960`) | **v1.5.3** |
| No install while recording (`CallInProgressGate.kt`) | **v1.5.6** |
| Encoder validation (`EncoderLimits.kt`) | **v1.5.6** |
| VoIP/carrier collision (`VoipTelephonyGate.kt`) | **unreleased — `main` only** |

**The retention story, because it cost a day and the shape recurs.** With retention set to 7 days the
app showed a convincing 64 recordings going back exactly 7 days while **131 files had outlived the
window** (8 device, 123 Drive, oldest by 48 days). Four independent faults:

1. `deleteFile` cleared the catalog entry even when the delete failed, so a failed Drive delete made
   the file invisible *and* unreachable for ever. Fixed: the entry survives a failed delete.
2. The sweep walked only the catalog, so anything missing from it was exempt regardless of age. Fixed:
   it reads the folders too, gated on `RetentionPolicy.isEligible` (only names CallVault writes) and
   never deleting a file whose age is unknown.
3. `ExistingPeriodicWorkPolicy.UPDATE` ignored the new initial delay, so changing **Run at** moved
   nothing for up to a day. Fixed: `CANCEL_AND_REENQUEUE`.
4. **Google Drive renumbers the account slot inside its SAF URIs** (`acc=1` → `acc=4` here), which
   invalidates every stored Drive URI — uploads, deletes and listings all throw SecurityException, and
   re-picking the folder does not repair the URIs already stored. Fixed: `DriveCatalogRepair`
   re-points them against the live listing before each sweep. **This is a recurring hazard, not a
   one-off** — it will happen again whenever the user's Drive accounts change.

Device-verified 2026-08-04 across three sweeps: `deletedLocal=9`, then `deletedDrive=124`, then
`63 re-pointed, 1 forgotten`. The one pre-cutoff file the sweep deliberately left alone was
`callvault-signing.keystore`, sitting in the same Drive folder — the eligibility gate earning its keep
on its first real run.

**Hard constraint on the next release:** versionCode must exceed **10720** — what 1.5.7 shipped as, and
what the maintainer's OP12 now carries. The floor climbs faster than the version number: 1.5.6 shipped
as 10670, then test builds and a published diagnostic pre-release took it through 10678, 10680, 10690
and 10700-10714 before 1.5.7 was cut at 10720. Anything at or below the floor installs for most users
and silently fails on the devices that matter most. See the `release-version-bump` memory, and read the
phone before choosing.

**Device-verified 2026-07-31 (OnePlus 12, build `1.5.6-encoder` / 10661):** encoder validation does
*not* divert recording to the scrcpy fallback — output was mono 48 kHz, full duration, −16.9 dB mean.
The mid-call guard's device path is covered by unit tests only. **Known gap:** the new
`CV:EncoderLimits` line runs in the *daemon*, so it never reaches the app's debug log; it does reach
logcat, but logcat's default 256 KiB ring holds barely a minute on this phone (measured 2026-07-31:
123 KiB consumed in 26 s), so it had aged out before it could be read. An earlier note here blamed
ColorOS for filtering third-party logs — that was wrong, and re-tested: our lines are present. The
fix is the ring growth in `2026-07-28-daemon-and-system-logs-design.md`.

**Known limitation, parked, not planned:** a Bluetooth headset or smart glasses can make a carrier
recording silent — right size, right duration, no audio. This was the real cause of issue #18 (Meta
Ray-Ban glasses), found by the reporter after about a week. See
`2026-08-04-bluetooth-headsets-and-silent-recordings.md`. **If a silent-recording report ever arrives
again, ask what the audio was routed to before anything else.**

**Blocked on other people:** nothing.

**Written but unplanned:** `2026-07-28-daemon-and-system-logs-design.md` — a design for getting daemon
diagnostics into a bug report, with no implementation plan yet. Issue #18 is the standing argument for
it: twice, the answer lived in the daemon's process where no bug report can reach.

**Also argued for by issue #18, not yet scheduled:** `SILENT` detection (an all-zeros check on the
daemon's PCM, cheap on the direct path) and a settings snapshot in the log-export header (the export
carries device and version but not the toggles, which is why the VoIP question above needs a manual
test at all).

---

## 🟡 A carrier call could be mistaken for an app call — FIXED, THE ONE ITEM AWAITING RELEASE

**Branch `fix/voip-carrier-collision`, commit `75868ca`. Fold into the next release and device-test it
there.** Not device-tested: the fixed path cannot fire on the OP12 (see below), so the only thing a
device run proves is the absence of a regression — one WhatsApp call and one carrier call, both
recording as before.

App-call detection recognises a VoIP call by one signal, the audio mode being
`MODE_IN_COMMUNICATION`. Nothing enforced that a carrier call could not set the same mode; the only
thing separating the two paths was a comment in `VoipCallDetector` asserting they "cannot collide".
Wi-Fi calling and some VoLTE stacks carry the call over IMS and can present as
`MODE_IN_COMMUNICATION` — and there the app-call path would record a phone call the carrier path is
already recording, holding a plain `MIC` capture that contends with the dialer, made worse by the
1.5.5 microphone-reclaim logic taking it back mid-call.

`VoipTelephonyGate` makes the telephony call state the authority: no start while ringing or off-hook
(the ring matters — the mode moves around during call setup), and a running capture stops once a
carrier call is answered, signalled by the telephony broadcast because on such a ROM the mode never
changes and the mode listener never fires. Fails open on an unrecognised state, like
`CallInProgressGate`.

**Measured on the OP12, 2026-08-05, and worth keeping:** every carrier call went to `MODE_IN_CALL` set
by `com.android.server.telecom`; only WhatsApp used `MODE_IN_COMMUNICATION`. So the collision is real
in principle and absent on this hardware — which is exactly why it survived unnoticed.

**Where this came from:** a user report that "the mic is always on during a cell call", which turned
out not to be a bug at all. Measured on the OP12 mid-call: a live `AudioIn` thread, `Standby: no`,
`AUDIO_SOURCE_VOICE_CALL` on `AUDIO_DEVICE_IN_TELEPHONY_RX`, reading continuously for the duration of
the call and gone afterwards — the carrier recorder doing its job, attributed to `com.android.shell`
because that is the uid the daemon runs as. Android shows the privacy indicator for any active
capture and it cannot be suppressed. The reporter's belief that 1.5.6 did not do this was checked and
dropped: 1.5.6-era recordings exist, so the capture — and the indicator — was running then too.
**If this is reported again: the indicator is the recording. Ask whether recordings exist for the
period they think was quiet.** There is nothing to fix short of not recording, and the gap is
documentation — nothing in onboarding warns that the indicator appears on every call and is
attributed to Shell rather than CallVault.

**Checked against history, 2026-08-07, so nobody re-derives it.** There is no release in which a call
was recorded without a shell-uid capture. At `v1.4.7` the carrier path is `DirectAudioRecorderSession`
opening `AudioRecord` itself, annotated *"shell uid holds `CAPTURE_AUDIO_OUTPUT`; the daemon is not an
app"*. Direct capture only arrived in `v1.4.0` (`7df300a`); before it everything went through
scrcpy-server, which `ScrcpyConfig.kt:21` at **`v1.1.0`** — the earliest tag — describes as running
"with `app_process` … the shell user (UID 2000)". The app has **never** declared `RECORD_AUDIO`, so
shell was always the only possible attribution. Capture code between `v1.4.7` and `v1.5.7` moved only
for `EncoderLimits` bit-rate validation and 1.5.5's mic re-take, neither of which changes the source,
the uid, or how long the capture is held.

**What that check cannot cover:** it proves our capture never changed, not that the OS always
*displayed* it the same way. A ROM update altering how shell-uid captures are surfaced would look
exactly like a CallVault regression and leave no trace in this repo. If a second user reports it and
their recordings also check out, look there.

## 🔵 Our captures do not register with `AudioService`'s record tracking

Noticed while investigating the above, unexplained, and left alone deliberately. During a live carrier
call on 2026-08-05 the HAL-level capture was plainly running (`AudioIn_5C6`, frames read climbing)
while `dumpsys audio`'s record-activity log carried **no matching `rec start`** — and the same log
shows `rec stop` events at 10:22, 12:22 and 13:58 with no starts either. Starts register sometimes
(11:50 that day, and the 08:59 VoIP `MIC` capture) and not others.

It causes no stuck microphone and no lost audio, so it is not urgent. It does mean the OS's view of
who is recording disagrees with reality, which would affect anything keyed off record-configuration
callbacks. Worth understanding before relying on that API for anything.

---

## 🟡 VoIP-only mode SHIPPED in 1.5.6; Shizuku coexistence still open

Investigated 2026-07-30, no code changed. Full write-up:
`2026-07-30-voip-only-mode-and-shizuku-coexistence.md`.

**✅ Done in 1.5.6.** The naming became a real switch instead: `CARRIER_RECORDING_ENABLED`, off =
phone calls ignored end to end. That turned out to be necessary rather than cosmetic — the
combination this entry described (both auto-record toggles off) is the **Ask me** state, and
`CallSessionManager` still sent `ACTION_STANDBY`, so an app-calls-only user was prompted on every
phone call. One new preference each side (`VOIP_AUTO_START` too), both defaulting to the old
behaviour; no existing state was duplicated.

**Shizuku does not crash; we kill it.** Both apps' helpers are children of an `adbd` shell, and any
`adbd` restart kills every process started over ADB — established here already and quoted from
Shizuku's maintainer in `transport-and-daemon-architecture.md`. CallVault restarts `adbd` in exactly
two places that matter: arming loopback (`tcpip:`, always destructive, once per boot) and enabling
Wireless debugging. The routine WD-off after each daemon launch is harmless *when USB debugging is on*
(pid measured unchanged), which is why users will report it as intermittent.

Cheapest honest response: detect Shizuku and warn before arming offline recording.

**Full Shizuku support researched 2026-07-30** — `2026-07-30-shizuku-support-feasibility.md`. It fits
better than expected (Shizuku's `UserService` *is* our daemon: our code, uid 2000, and the daemon
already runs its own shell commands via `ProcessBuilder`, so it needs no ADB), and the app touches the
daemon through one AIDL from five files, so most of the work is in how it starts.

**But the reliability argument fails.** Shizuku's server is itself an `adbd` child, so a screen-off
`adbd` restart kills it exactly as it kills ours — this repo already quotes Shizuku's maintainer on
that. Shizuku stops CallVault *causing* churn; it does not survive it. And it costs hands-free
operation after reboot, which is a stated differentiator.

Recommendation: warn first, extract a `PrivilegedProvider` abstraction regardless (worth it alone),
and treat full support as a product decision rather than a fix.

**Not chasing the reporters.** A set of diagnostic questions used to live here (USB debugging on?
offline recording on? does Shizuku stop once or repeatedly?). Dropped 2026-07-31: when Shizuku is
actually picked up, it gets tested properly on our own devices rather than reconstructed from
second-hand answers. The analysis above stands as a hypothesis until then.

---

## 🔵 README is out of date after 1.5.5

Screenshots predate the Settings panel and the two new wizard steps; **"Settings ▸ Experimental"** is
referenced throughout but the path is now **Settings ▸ General ▸ Experimental**; and the VoIP
compatibility table does not know Samsung/One UI works as of 1.5.5. Screenshots were already stale
before today — regenerating them needs care about real contacts in a public repo.

---

## 🔵 Split `AppPreferences` into per-domain interfaces

**Why.** `AppPreferences` is 686 lines, 60 keys and 107 accessors, and every subsystem in the app
reaches into it: the knowledge-graph build on 2026-07-27 measured 188 edges and a betweenness of
0.190, bridging 33 of 243 communities — from `DaemonKeepAliveService` to `Theme` to
`VoipRecordingCoordinator`. That number is a symptom, not the finding.

The finding is *how* it clustered. Community detection split the class's own members into six groups,
and the dividing line is the **storage primitive, not the subject**: boolean writers in one group,
string writers in another, int accessors in a third, `getStringSet`/`setLong` in a fourth. Only the
storage/sync accessors clustered by meaning. There is no domain structure inside the file for the
algorithm to find, because the file has none below the comment headers.

**What.** Keep one `SharedPreferences` instance and the `Key` enum. Expose them through roughly nine
narrow interfaces — `RecordingPrefs`, `StoragePrefs`, `TransportPrefs`, `UpdatePrefs`,
`AppearancePrefs`, `FilterPrefs`, `DebugPrefs`… — each 5–12 methods, with `AppPreferences` as the
single implementation satisfying all of them. Consumers depend on the slice they actually use.

**The boundaries are already written in the file.** `Key` has 13 comment-delimited groups, and they
map nearly one-to-one onto communities that formed independently elsewhere in the graph: Storage
Routing / Sync Schedule, Retention, ADB, In-app updates, Persistent recorder server, Automation +
Filters, Developer & Debug, Audio quality, UI & Appearance. Those comments are doing an interface's
job. Cut along them and the split needs no new judgement.

**What this does not buy.** It does not decouple anything — `DaemonKeepAliveService` still needs its
settings. The wins are narrower and worth stating honestly: each consumer's dependency becomes
legible, tests can fake a 6-method interface instead of a 107-method class, and the "General" section
restructure above gets a seam to cut along. It is a wide, mechanical diff across most of the app, on
a file that has caused none of the recent failures — real value, no urgency. Do it *with* the Settings
restructure, not on its own.

---

## ⛔ Switching Wireless debugging off — NOT POSSIBLE, and why

Three attempts, all failed, all for the same underlying reason. **Do not try routes 1 or 2 again.**

**`adbd` only runs while USB debugging or Wireless debugging is enabled.** `service.adb.tcp.port` says
*where* `adbd` listens — it is not a reason for `adbd` to exist. With both switches off there is no
`adbd`, so there is nothing to launch the daemon over and nothing to keep it alive.

Measured on a OnePlus 12 (1.5.0-wdoff3, USB debugging off throughout):

```
11:02:15  Dropping Wireless debugging before launch
11:03:18  shell not ready within 60000ms (150 probes)   <- a full minute, never returned
11:03:20  Loopback self-healed after 1500ms             <- 1.5s AFTER WD was switched back on
```

The apparent success later in that log (11:04:25) is confounded — it is the exact moment USB debugging
was enabled, which starts `adbd`. **Every "it worked" observation in this investigation turned out to
have a second debugging switch on somewhere**, which is the single lesson most worth keeping.

- **Route 2 (make the daemon outlive `adbd`)** — impossible without root. Init kills the service's POSIX
  process group AND its cgroup on stop, explicitly so `setsid` cannot escape. Shizuku dies the same way
  ([#311](https://github.com/RikkaApps/Shizuku/issues/311)); its community's workaround is `adb tcpip`
  ([#864](https://github.com/RikkaApps/Shizuku/issues/864)), i.e. exactly our loopback — which does not
  solve it either.
- **Route 1 (launch over the loopback after dropping WD)** — cannot work, per the above. It also cost
  **two minutes of delayed readiness** at boot, since each attempt burns the full timeout. Reverted.

**One device difference:** on a Galaxy S24 FE the listener *did* return ~1.5 s after WD was dropped with
USB debugging off, so its `adbd` behaves differently from the OnePlus. If this is ever revisited, the
only defensible shape is **opportunistic and remembered**: after the daemon is up and idle (never on the
launch path), try the drop once, poll briefly, record the answer for that device — success keeps WD off,
failure re-enables it and never retries. That still leaves OnePlus-class devices with WD on.

**The real escape is to stop needing the daemon at call time** — Track A in
`capture-research-directions.md`. That is the only route that removes the debugging switch entirely.

---

## 🔵 "Test my setup" — prove the whole path works, before it matters

> **Superseded (2026-07-28) by [the setup-health design](2026-07-28-setup-health-status-design.md).**
> The button is gone: a test the user has to remember to press is not read by the people who need it
> most. The status card reports what real calls proved instead, and a call-log sweep catches calls
> CallVault never saw. The reasoning below still holds — only the shape of the answer changed.

**Why.** This app fails *silently*, and the failure is discovered after the call you needed. Every
recovery mechanism shipped so far (screen-lock USB fix, resilient recording, fast daemon recovery)
reduces the chance of a failure without ever telling the user whether their setup works **right now**.
Every naming bug in the VoIP feature was found by making real phone calls, because there is no other
way to exercise the path.

**What.** One action that runs the real pipeline end to end and reports which step failed:

1. ADB connection alive (and how long it took — this is where the ~75 s stall would show).
2. Daemon reachable, binder responsive.
3. Capture starts on the configured audio source.
4. Encoder produces non-silent frames.
5. File is created in the chosen SAF folder, catalogued, and appears in the list.
6. Clean teardown, and the test file removed.

Report per step, with the failing step named in plain language and a link to the setting that fixes it.

**Notes.** Do not fake it end-to-end: a test that stubs any step will pass while the real path is
broken, which is worse than no test. The daemon already exposes what is needed; a `VOICE_CALL` source
cannot be exercised outside a call, so the test should use `MIC` and say so, or capture briefly from
the configured source and report "could not verify without a live call" rather than implying more than
it checked. Reuse `voipFarPartyHeard`'s honesty pattern — report what was actually observed.

---

## 🔵 Per-app VoIP support, checked at runtime

**Why.** Settings currently says VoIP recording is experimental and "some apps block recording, and
this cannot be known until a call is under way". Half of that is now avoidable: whether an app opts out
of capture is readable **before** a call, from the audio flags its playback carries.

**What.** A per-app list under Settings ▸ Experimental ▸ VoIP calls — installed calling apps with a
real status each: verified working, not yet tried, or blocks capture. Turns a vague warning into a
fact, and tells the user *which* of their apps will work rather than leaving them to discover it.

**Notes.** The distinction that matters: `FLAG_NO_MEDIA_PROJECTION` (0x800) is bypassable by this
route and is what WhatsApp, Telegram and Signal all set; `FLAG_NO_SYSTEM_CAPTURE` (0x1000,
`ALLOW_CAPTURE_BY_NONE`) is checked before any permission and is **not** bypassable. Reading the flag
requires an active playback track, so it cannot be sampled at rest for an app that is not in a call —
expect "not yet tried" to be a real state, and record the observed result after each call instead of
promising a prediction. Feeds the README's tested-devices table.

---

## 🔵 `AdbShell.ensureConnected` is unbounded on the recording-start path

**Why.** It can block for ~75 s while a recording is trying to start. 1.4.6 capped one such read at
1.5 s after it caused calls to be missed entirely; this is the same class of problem, not yet fixed.
It was the agreed next priority before VoIP took over.

---

## 🔵 Smaller, known, and worth not forgetting

- **✅ Translations are complete and now enforced (2026-07-29).** Ten locales — pt-BR added, the other
  nine backfilled — at 445 translatable strings each. `./gradlew :app:checkTranslations` fails the
  build on a missing string, an orphaned one, or a placeholder that differs from the base; it gates
  `check` and `assembleRelease`, with `-PallowMissingTranslations=true` as the deliberate override.
  **This entry is the reason the check exists:** it previously read "three VoIP strings are
  untranslated" while eight locales were actually 46 behind and the three keys it named were in zero
  locales. Counting by hand is what failed, twice. Do not replace the check with a note.
- **Stale screenshots** in `docs/screenshots` (21 June) — predate the current UI.
- **`WD_DISABLE_WHEN_IDLE` is a dead preference** — read by nothing.
- **Wrong contact label** on some recordings (reported, not diagnosed).
- **CI release workflow is broken**: its `SIGNING_KEYSTORE` is a *different* key from the release key,
  so it fails at signing. Releases are built locally with `signing/callvault-signing.keystore`
  (cert `c875ffd0…`). Left broken deliberately — fixing it means putting the real key in CI.
  It also still names its artifact `ShizuCallRecorder-<version>.apk` and titles the release the same,
  while the in-app updater only accepts an asset named exactly `CallVault.apk`
  (`GitHubReleases.APK_ASSET_NAME`). A release published by this workflow would therefore be **invisible
  to the updater** — fix the naming at the same time as the signing, or the first CI release silently
  reaches nobody.
- **No instrumentation tests at all** (`app/src/androidTest` does not exist). 16 unit-test files cover
  parsing, version comparison and policy decisions; everything device-shaped is verified by hand on a
  real call. That is the honest state, and it is why regressions here are found by making phone calls.

## A VoIP recording ends with no confirmation at all

**Found 2026-08-27**, while checking whether the decode-memory fixes had broken something. They had not
— this is pre-existing and has always been the case.

The end-of-recording toast and vibration come from `RecordingNotificationHelper.handleStateChangeToasts`,
which is called from exactly one place: `RecordingForegroundService`. `VoipRecordingCoordinator` carries
an explicit comment that the VoIP path **deliberately does not go through** that service, so it never
drives the state machine those toasts hang off. VoIP can raise an *error* notification and nothing else.

So a VoIP call records successfully and tells the user nothing. The maintainer noticed the absence
himself and assumed he had misremembered.

This matters more than a missing toast normally would, because it sits on the failure mode the
2026-08-27 research found users punish hardest: **a recording that silently does not happen looks
exactly like one that silently does.** With no positive confirmation, the only way to learn a VoIP call
was not captured is to go looking for it later.

Options, cheapest first:

1. Post a success notification from `VoipRecordingCoordinator` where it already posts errors, and
   vibrate through the same helper (which already honours the user's vibration preference). Smallest
   change; keeps the deliberate architectural split intact.
2. Give the VoIP path its own lightweight state notion and reuse `handleStateChangeToasts`. More
   consistent, more surface area.
3. Fold VoIP into `RecordingForegroundService`. Largest, and undoes a split that exists for reasons.

Related: the live input-level meter (`10-product-ux.md` D1) attacks the same problem from the other end
— confirmation *during* the call rather than after it.

## Store recordings as Matroska `.mka` (issue #36)

**Requested 2026-09-11 by mirror176.** One container per call holding both sides as separate audio
streams, the transcript as a subtitle track, and caller/date/summary/notes as metadata — so the data
is portable and readable by other software instead of living only in CallVault's database, and a
merge becomes a container operation rather than a re-encode.

The idea is sound. The blocker is the platform: **Android cannot write Matroska.** `MediaMuxer`
offers MPEG-4, 3GPP, WEBM, OGG and HEIF (checked 2026-09-11), none of which is `.mka`; WEBM is a
Matroska subset but the platform muxer exposes no subtitle track and no general metadata. Media3's
muxer package is `Mp4Muxer`/`FragmentedMp4Muxer` only. Reading is fine — the platform demuxes `.mkv`
for common codecs — so this is entirely a writing problem.

Shipping it therefore means bundling or writing a Matroska muxer (libmatroska or ffmpeg: a new native
dependency, its size, its licence, its build) and then changing every path that touches a recording —
the three capture paths, playback, merge/un-merge, Drive backup, retention, the catalog — plus a
migration for every recording that already exists. Largest single change the app has made, and it
would also make the F-Droid picture harder (see the F-Droid readiness note).

If it is ever taken up, the cheap half is worth separating: writing a **sidecar** (the transcript as
`.srt`/`.ass` and the metadata as JSON next to the audio) gets most of the portability with none of
the container work, and would be a day rather than a month.

Full reasoning: `docs/dev-notes/2026-09-11-issues-34-35-36.md`.

## Transcript lines whisper invented from noise (issue #32)

**Reported 2026-09-10 by mirror176**, S20 FE, resilient recording on. A call's transcript opened with a
line that had **no speaker label**, stamped at 1 s, followed by a real `You:` "hello" at the same 1 s;
tapping the unlabelled line played nothing audible. On another call a "hello" was stamped on a moment
that held only the phone's vibration. Analysed from code on 2026-09-11, **not reproduced**.

**What the code says is happening**

- Speaker labels come from our own capture: `SpeakerTurnDetector` classifies every 100 ms as A, B, both
  or silence (`SILENCE_FLOOR` 300), and `SpeakerLabeller` returns null when nobody was voiced during a
  line, or when neither side reached 66%. **An unlabelled line is our own audio saying nobody spoke
  there** — whisper wrote text on noise that the VAD had kept (`speech_pad_ms` 400, threshold 0.4).
- Both lines land at 1 s because of the #25 fix: `SpeechGapSnap.snapForward` moves any start that falls
  before the first kept speech onto that speech's start, so the invented line is moved onto the real
  "hello" and the two share a timestamp.
- Our whisper settings leave `suppress_nst` at its default (off) and `no_speech_thold` at 0.6.
  whisper.cpp still has an open request for proper no-speech detection (#1026).

**Options, none decided**

1. Hide (or grey) a line when the captured turns say both sides were silent for its whole span.
   Language-independent, which matters: an English hallucination word list is already measured-negative
   in `transcription-quality-ceiling`.
2. Try `suppress_nst = true` and/or a stricter `no_speech_thold`, measured on the desktop harness first.
3. Don't snap a line forward onto the start of a line with the same text.

**Risks before choosing:** option 1 would also hide genuinely quiet speech below the silence floor, and a
Hebrew test can only falsify a quality change, never clear it. Option 1 only works where turns exist —
the handoff path with resilient recording on. Needs a real affected recording, or a clip with a vibration
before the first word, run through the whisper-cli harness with the app's exact settings.

---

## What is installed on the test phones, and what is now on `main` (2026-10-12)

Both OP9 (`daabf34f`, Shizuku mode) and OP12 (`6011b07e`, standalone — daily driver) run a debug build
carrying all of the below. **All four branches were merged into `main` on 2026-10-12** (maintainer gave the
go; `main` built + full unit suite green after the merges; **still local — nothing pushed to origin**):

- `feat/select-all-library` → **`ff401b8c`** — #1 select-all as the top-of-list "Select all" row (Option D) +
  deselect-keeps-mode fix. ✅ VERIFIED on device.
- `fix/transcribe-again-done-only` → **`753f5fbe`** — #4 drop "Transcribe again" on DONE. ✅ VERIFIED.
- `fix/bulk-delete-by-name` → **`6f58462f`** — bulk "delete both copies" deletes by name like a single-row
  delete (`7d99eb42`). ✅ VERIFIED (Select-all → Delete empties the list, the stuck row included).
- `fix/drive-sidecars-merge-applock` → **`06bf6f62`** — app-lock "Allow screenshots" (✅ VERIFIED), #49
  sidecars→Drive (approved for release), **Merge-Drive-only (🧪 — merged on the maintainer's call, only
  device-tested; watch for real-user feedback)**.

Earlier in the session `main` also took the global On/Off master switch (`1c800e60`) and #46 Greek. The
`build/install-combined-2026-10-12` branch was the pre-merge install artifact and is now redundant.
WRITE_SECURE_SETTINGS was re-granted after each install (`granted=true`); both recorders verified back up
(OP12 `app_process` daemon + `recorder_keepalive`; OP9 `:recorder` fork).
