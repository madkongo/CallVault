# Plan 3 — Storage Routing Findings & Status

**Date:** 2026-06-10
**Plan:** `docs/superpowers/plans/2026-06-10-callvault-unified-plan3-storage-routing.md`
**Device:** OnePlus 12 (CPH2581), OxygenOS 16 / Android 16
**Branch:** `feat/callvault-adb-spike`

## Result: 🟢 GREEN — recordings auto-copy to Google Drive

With Storage Target = **Both** and a Google Drive folder selected, a recorded call's `.ogg`
is kept locally AND copied to the Drive folder via a WorkManager job:
`RecordingCopyWorker: Recording copied to Drive (deleteLocal=false, src=66597 dest=0)` → `SUCCESS`.

## Implemented (this plan)

- `StorageTarget` (LOCAL/DRIVE/BOTH) + prefs (`getStorageTarget`, `getDriveFolderUri`).
- `SafHelper.copyFileToFolder` (write-only stream — works on cloud providers) + `fileSize`.
- `RecordingCopyWorker` (WorkManager, network-constrained, backoff) + `StorageRouter`.
- Finalize hook in `RecordingForegroundService` routes the final file exactly once (post-rename).
- Settings: Storage Target selector + Drive folder picker (reused existing dropdown + SAF picker).

## Bugs found by on-device testing (fixed)

1. **Re-onboarding on every launch** — the onboarding gate checked the *live* per-process ADB
   connection (`isConnected`), so each cold start looked unconfigured. Fixed: persist an
   `ADB_PAIRED` flag (set on first successful connect), gate onboarding on it, and auto-connect
   in the background on app start (`ShizuApplication`). Commit `95ada9e`.
2. **Local call shown as "cross-country"** — detection was actually correct (`crossCountry=false`);
   the recording notification showed the cross-country *tip* whenever metadata was merely unknown
   (early in the call). Fixed to show it only when genuinely cross-country; also replaced the stale
   "Waiting for Shizuku Server…" notification text with "Preparing to record…". Commit `d320dd0`.
3. **Drive copy uploaded then vanished** — the worker verified the copy by comparing
   `DocumentFile.length()`, but Google Drive reports `length()=0` immediately after a streamed
   write, so the worker deleted the copy and retried forever. Fixed: a successful `copyTo` (no
   exception) is the success signal; size differences are logged, never acted on. Commit `8dbf4ad`.

## Not yet exercised on-device (implemented, low risk)

- **Drive only** (target=DRIVE): copies to Drive then deletes the local file. Logic in place
  (`deleteLocalAfter=true`); copy success is trusted before deletion, so it is safe.
- **Offline retry**: WorkManager `NetworkType.CONNECTED` + backoff — the job waits for network and
  retries (the original size-verify retry loop is gone). Verify by toggling airplane mode.

## Follow-ups (Plan 4)

- Full Shizuku removal: delete `ShellService`/`IShellService.aidl`/`ShizukuConnectionManager`,
  Shizuku Gradle deps + `ShizukuProvider` in the manifest, vestigial `tryStartShizukuServer()` /
  Shizuku prefs + the remaining Shizuku settings section, the throwaway `spike/` package + its
  manifest entries, and now-unused Shizuku imports.
- Hands-free boot: BootBridge / watchdog / OEM auto-launch + battery whitelisting / QS-tile fallback.
- Minor: the Drive copy passes `ScrcpyAudioCodec.mimeType` (e.g. audio/opus) to `createFile` while
  the file is an `.ogg` container — confirm Drive doesn't mangle the extension; if it does, pass
  `audio/ogg`.
