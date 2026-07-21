# Changelog

All notable changes to CallVault are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and this project uses semantic-ish versioning.

## [1.3.0] — 2026-07-21

The headline feature: **in-app updates**. CallVault can now tell you when a new version is out and
install it for you, without hunting for the APK on GitHub.

### Added
- **In-app updater (manual).** CallVault checks GitHub daily (and when you open the app) for a new
  release. When one is available you get a notification and an **Update** banner on the Home screen;
  tap it and CallVault downloads, verifies, and installs the update itself, then reopens on the new
  version and confirms with an "updated" banner + notification. No more manually downloading APKs.
  - The download is **resumable** — a slow or flaky connection resumes where it left off instead of
    restarting the ~80 MB file.
  - Every update is **signature-pinned**: the downloaded APK is checked against CallVault's release
    certificate (and must be a genuinely newer build) before anything is installed.
  - The install also re-grants the permission an app update otherwise drops, so recording keeps
    working seamlessly across updates.
  - A **"Check for updates"** toggle (Settings → Updates, on by default) turns the checks off if you
    prefer to update manually.

### Notes
- Installing is always an explicit choice — CallVault never installs an update on its own.
- Distribution is still sideload/F-Droid/Obtainium; the in-app updater is an added convenience for
  GitHub releases, not a replacement.

## [1.2.3] — 2026-07-19

### Fixed
- **Filenames no longer lose the caller for unsaved numbers.** With a file-name template using
  `{contact_name}`, calls from numbers not saved in your contacts produced a name with an empty
  contact segment — no way to tell who the recording was from. The placeholder now falls back to
  the **phone number itself** when there is no saved contact (and it's not voicemail). If your
  template already includes `{phone_number}`, the fallback stays empty so the number isn't
  written twice.

## [1.2.2] — 2026-07-19

A field-report fix release: voicemail calls get their proper name, and the app now tells the truth
when recording can't work instead of pretending everything is fine.

### Fixed
- **Voicemail calls are now labeled correctly.** Carrier voicemail short codes (e.g. `123` in
  France) were being "standardized" into an invalid international number (`+33123`), which broke
  contact-name resolution — and voicemail isn't a real contact anyway, so lookups always came up
  empty. Short codes now keep their raw form, and calls to the carrier voicemail number are labeled
  with a localized **"Voicemail"** name in file names and the recordings list.
- **No more false "Ready to record" while Developer options is off.** If Developer options is
  disabled (e.g. after an OS update or manual toggle), the recorder daemon cannot survive and every
  "recording" comes out empty — but the Home screen still showed green. It now shows a clear red
  **"Developer options is off"** status explaining what to re-enable.
- **Empty recordings are no longer saved as if they succeeded.** A 0-byte file (daemon died before
  capture started) used to be cataloged, copied to Drive, and shown as an unplayable entry
  ("Can't read this file"). It is now deleted and reported with an error notification instead.
- **You are warned during the call if recording silently stops.** The app now watches the recorder
  daemon while a recording is live and immediately notifies **"this call is NOT being recorded"**
  if the daemon dies mid-call, instead of discovering the loss after hanging up.
- **Post-reboot and startup notifications are now translated.** The "Ready to record calls /
  Listening for calls after restart" notification (and the boot-time "Preparing call recorder…")
  were hardcoded in English; they now follow the app language like everything else.

### Internal
- Cross-country detection is preserved for invalid/foreign numbers (the ignore-cross-country
  auto-record rules keep working for them).
- The unit-test harness (JUnit/Robolectric) now lives on `main`, with tests covering the number
  enrichment, voicemail matching, file-name fallback, and Developer-options detection.

## [1.2.1] — 2026-06-26

A localization release: every shipped language is now fully translated, fixing screens that
appeared in English even when the rest of the app was localized.

### Fixed
- **Onboarding and main screens now follow the selected language.** The disclaimer/first page, the
  permissions and setup wizard, and the home screen (recordings list + status) previously fell back to
  English for many strings — most visibly the **first page stayed English** even with a French
  device/app language. All shipped locales (fr, de, es, it, hu, pl, ru, vi, zh-rCN) are now at **100%
  coverage** (289 UI strings each).
- **Recorder & pairing notifications are now translated.** The cold-start "Call recorder starting up…
  / Ready to record calls" status and the wireless-debugging pairing notifications were hardcoded in
  English; they are now string resources and localized in every language (using each locale's official
  Android wording for "Wireless debugging").

### Changed
- **Translation coverage is now enforced at build time.** Lint treats `MissingTranslation` and
  `ExtraTranslation` as errors, so a new untranslated string can no longer ship silently.

### Internal
- Hardened two notification posts (`DebugNotificationHelper`, `RecorderReadinessNotifier`) with an
  explicit `POST_NOTIFICATIONS` check, resolving the corresponding `MissingPermission` lint errors.

## [1.2.0] — 2026-06-25

A reliability release focused on **recording the first call after a reboot**, plus a rebuilt,
user-facing debug/bug-report flow and an audio default better suited to voice.

### Added
- **Debug section (always visible).** Settings now has a simple **Debug** section: turn on diagnostic
  logging, see an **"ON" reminder** (in-app warning + a persistent notification) so you don't leave it
  running, and **Share debug logs** in one tap via the system share-sheet to send a bug report. Logs
  are phone-number **redacted**.
- **24 kbps audio bit rate**, flagged **Recommended** and now the **default** for Opus — plenty for
  intelligible voice; higher rates only inflate file size.

### Changed
- **Removed the hidden "Developer Options"** (the 7-tap unlock, test-call simulator, and the
  redaction-off "Debug mode"). Log **redaction is now always on** and cannot be turned off.
- After a reboot **or an app update** the app briefly shows **"Call recorder starting up…"**, flipping
  to **"Ready to record calls"** once recording is actually possible — so you know when a call will be
  captured. Only appears while the recorder daemon is cold (nothing shown when it's already warm).

### Fixed
- **First call after a reboot now records.** A new bounded post-boot **live call-state listener**
  detects calls in real time instead of relying on the system `PHONE_STATE` broadcast, which on a
  freshly-booted device could arrive **~9 seconds late** — after the call had already ended.
- **Faster recorder warm-up after boot** (~5 s vs ~15 s): trimmed a redundant Wireless-Debugging wait
  and skip the stale-daemon scan in the first 90 s after boot (a reboot already cleared any daemon).
- **Daemon cold-start no longer over-waits.** The launcher returns the instant the daemon's binder
  arrives instead of blocking out the full keep-alive window, recovering calls that were previously
  aborted 1–3 s too late.
- **Redaction can no longer be left disabled.** A leftover developer "Debug mode" flag could keep real
  phone numbers in shared logs; redaction is now unconditional.
- **Number-less recordings rename correctly.** The end-of-call CallLog rename now uses
  `DocumentsContract.renameDocument` (the previous call threw on single-document SAF URIs), so files
  get the contact/number in their name.

## [1.1.1] — unreleased

A stability + features release built on top of v1.1.0. It keeps v1.1.0's proven on-demand
Wireless-Debugging behaviour (the daemon "keep-alive" experiment that made Wireless Debugging
repeatedly turn itself on was **not** included) and adds the safe fixes plus new settings.

### Added
- **Retention / auto-delete.** Automatically delete recordings older than a chosen period
  (Daily / Weekly / Bi-weekly / Monthly, or Keep forever). Set **one period for both device & cloud,
  or a separate period for each**. A daily background sweep runs at a **time you choose**, anchored to
  the **device's local time zone** (re-anchored automatically when you change time zone). Defaults to
  off; enabling it asks for confirmation.

### Changed
- **Settings reorganised.** "Recording & storage" is now **two separate sections** — **Recording**
  (filename template, auto-record rules) and **Storage** (target, device folder, Drive folder).
- **Accordion settings.** Sections open **one at a time** (Recording open by default); opening one
  collapses the others.

### Fixed
- **Phantom Drive recordings** no longer appear in the Home list — the list is now backed by a
  standalone on-device catalog instead of Drive's stale index.
- **Folder pickers** now open at their own currently-selected folder (best-effort; OEM file pickers
  may still ignore the hint).
- **Onboarding no longer skips the ADB step** after a reinstall (Auto Backup disabled), plus clearer
  guidance for the OEM per-app battery mode (e.g. OxygenOS "Allow background activity").
- **Stuck microphone** fixed: recording start is aborted if the call ends during daemon cold-start.

### Performance
- Faster scrcpy socket connect (tighter polling).

### Security / internal
- Removed the exported debug-only broadcast receivers used during development.

## [1.1.0] — 2026-06-11

Complete visual redesign ("Signal" theme) plus UX polish: setup wizard, Home screen with in-app
playback and filters, smoother Wireless-Debugging pairing. See the
[v1.1.0 release](https://github.com/madkongo/CallVault/releases/tag/v1.1.0).
