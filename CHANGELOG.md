# Changelog

All notable changes to CallVault are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and this project uses semantic-ish versioning.

## [1.2.0] — unreleased

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
