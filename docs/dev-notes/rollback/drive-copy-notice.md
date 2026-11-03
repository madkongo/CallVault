# Rollback: scheduled Drive copy as a visible job, with a counter and "Stop for now"

🧪 VERIFYING — merged 2026-11-03 (option C, chosen by the maintainer from the "Drive Copy Notice" mock-ups).
2388 unit tests pass; checkTranslations passes (14 locales). OP9 run with a real Drive folder: see below.

## Why
As a plain background job the daily/weekly sweep is stopped by Android after ~10 minutes, so a big backlog never
finished (Eugene, Honor BVL-N49, 2026-11-03).

## What changed
- `HeavyWorkNotification.forDriveCopy`: "Copying recordings to Drive · N of M copied", bar, "Stop for now"; same
  silent MIN channel as transcription, own id 43, FGS type specialUse (what WorkManager's service declares).
- `SyncSweepWorker`: when there is something to upload, `setForeground` with the counter, updated after each file
  (best-effort: a refused foreground still copies). Checks "Stop for now" and `isStopped` only between files.
- `DriveCopyStop` + `DriveCopyStopReceiver` (not exported): a flag, never a WorkManager cancel, so the periodic
  schedule stays. Reset at the start of every run. A stopped run returns success; the next run copies the rest.
- Partial files: unchanged and already safe. A copy is written as `name.cvpart.ext` and renamed when complete; a
  leftover `.cvpart` is deleted on the next attempt; a short file under the real name is replaced by size check.
- Strings `drive_copy_title`, `drive_copy_progress`, `drive_copy_stop` in 15 locales. Manifest: the receiver.

## How to roll back
```
git revert -m 1 cc381b54
```
(`cc381b54` = the merge of `feat/drive-copy-notice` into integration/next.) Strings and the receiver go with it.
