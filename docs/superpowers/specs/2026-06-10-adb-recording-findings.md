# Plan 2 — ADB Recording Findings & Status

**Date:** 2026-06-10
**Plan:** `docs/superpowers/plans/2026-06-10-callvault-unified-plan2-adb-recording.md`
**Device:** OnePlus 12 (CPH2581), OxygenOS 16 / Android 16, non-root
**Branch:** `feat/callvault-adb-spike`

## Result: 🟢 GREEN — the fused app records real calls over embedded ADB, no Shizuku

A real outgoing call was recorded end-to-end through CallVault's embedded ADB transport
(no Shizuku app, no root): scrcpy-server launched over ADB, audio streamed via the
`localabstract` socket, parsed by the existing `ScrcpyClient`, and muxed to an `.ogg`
in the user's SAF folder (`/sdcard/CallRecording/…_out_….ogg`, ~40–86 KB per short call).
On hang-up the muxer finalizes and scrcpy-server exits (0 orphans).

## What was validated on-device

- scrcpy audio over ADB `localabstract` (Plan 2 Task 1 gate): opus FourCC + streaming bytes.
- Onboarding fusion: the "Shizuku" setup gate is replaced by "Wireless debugging (ADB)",
  which goes **Granted** after connecting with the persisted key (no re-pair).
- Real call (Task 7 gate): both-call-side capture via `audio_source=voice-call` (the
  same source that worked under Shizuku — our ADB shell is the same `uid 2000`),
  `codec=opus`, file finalized, clean stop, no orphan `app_process`.

## Two bugs found by on-device testing (fixed)

1. **NetworkOnMainThreadException** — the recording-start coroutine ran on the main thread;
   ADB `openStream` does real TLS network I/O (Shizuku's binder calls were exempt, ours are
   not), and the socket-readiness retry loop would also ANR. Fixed: the start path runs on
   `Dispatchers.IO` (`RecordingForegroundService` `serviceScope.launch(Dispatchers.IO)`).
   Commit `3fffda6`.
2. **Orphan scrcpy-server after each call** — `ScrcpyLauncher.stop()` closed the ADB streams
   on the main thread; the close (network I/O) threw and was swallowed by `runCatching`, so
   the CLSE packet never reached scrcpy-server and it lingered. Fixed: `stop()` closes the
   streams on a background daemon thread, so scrcpy-server exits. Commit `0dafa85`.

## Key insight reaffirmed

The embedded-ADB shell is the **same `uid 2000(shell)`** Shizuku gave scrcpy, so every audio
source / scrcpy arg that worked under Shizuku works unchanged here — only the transport
(Shizuku UserService → ADB socket) changed. ShizuCallRecorder's `ScrcpyConfig`/`ScrcpyClient`/
`ScrcpyAudioMuxer` were reused as-is (only `tunnel_forward=false → true`).

## Known follow-ups (Plan 3)

- **Remove Shizuku entirely:** delete `services/ShellService.kt`, `IShellService.aidl`,
  `integrations/shizuku/ShizukuConnectionManager.kt`, the Shizuku Gradle deps + `ShizukuProvider`
  in the manifest, the vestigial `tryStartShizukuServer()` / Shizuku prefs, and the throwaway
  `spike/` package + its manifest entries.
- **Settings UI:** replace the remaining Shizuku settings section with ADB pairing/status.
- **StorageRouter (the original ask):** Local / Drive / Both with verify-before-delete + retry.
- Minor: clean now-unused Shizuku imports in `PermissionsScreen.kt` (warnings only).

## Plan 4 follow-ups

- Hands-free boot (BootBridge / watchdog / OEM auto-launch + battery whitelisting / QS-tile
  fallback). Note: Wireless debugging persisted across one reboot in Plan 1 testing; multi/cold
  reboot reliability still to be validated.
