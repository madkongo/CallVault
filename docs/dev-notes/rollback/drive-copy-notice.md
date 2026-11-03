# Rollback: scheduled Drive copy as a visible job, with a counter and "Stop for now"

🧪 VERIFYING — merged 2026-11-03 (option C, chosen by the maintainer from the "Drive Copy Notice" mock-ups).
2388 unit tests pass; checkTranslations passes (14 locales).

**OP9 2026-11-03, real Google Drive folder, build 2.4.9-drive1 (Shizuku mode):**
- 14:27 daily run: "Sweep: 19 … newest first", notification 3 → 17 of 19, complete in 12 s, 0 failures.
- 15:29 run with 80 test copies: "31 of 80 copied" with bar; stop signal at 15:30:49 → "Sweep stopped by the user after 32 of 80" at 15:30:51, notification gone. The button is attached (`actions=1 "Stop for now"`) but on the OP9 it sat below the fold of a crowded shade, so the stop was sent as root to the same receiver; a tap on the button itself is not yet seen.
- 15:44 run: "Sweep: 49" = the 48 left + 1 new, complete, 0 failures. Drive then listed all 100, no `.cvpart`.
- Cleanup: test copies deleted from the phone; the Drive folder moved to the Bin; OP9 back to device only.
- Seen on the way, not fixed: changing "Where to save" alone does not re-apply the schedule (only schedule/time changes and app start do).

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
