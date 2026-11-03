# Rollback: forget Drive copies deleted in Drive itself

🧪 VERIFYING — merged 2026-11-03. DriveGoneCopiesTest passes. On the OP9 (2.4.9-calls4) the check ran: "100 Drive
copies, folder listing 100, 0 deleted" — **files in Drive's Bin still count as present to apps** (the binned
"CallVault OP9 test" folder still lists all 100), so only files deleted *forever* are caught. To settle: delete
that folder forever from Drive's Bin, reopen Recordings after 6 h (or clear `shared_prefs/cv_drive_presence.xml`),
and the 81 test entries should leave the list ("N deleted in Drive and forgotten" in the log).

## What changed
- `DriveCatalogRepair.forgetDeletedIfDue` (every 6 h, state in `cv_drive_presence` prefs) / `forgetDeleted`:
  candidates = Drive copies missing from the folder listing (or all, when the listing is unavailable or empty);
  forgotten only when querying the document says not found (`FileNotFoundException` or no row). Anything else
  keeps the copy. `RecordingCatalog.removeCopyByUri` drops the row only if no copy is left.
- Called from `HomeViewModel.startListPass` (before listing) and `RetentionSweepWorker` (after `reconcile`).

## How to roll back
```
git revert -m 1 8469d89a
```
(`8469d89a` = the merge of `fix/forget-deleted-drive-copies` into integration/next.)
