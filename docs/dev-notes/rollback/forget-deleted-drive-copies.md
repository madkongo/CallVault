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

## Follow-up 2026-11-03 — the signal that works (merge d9b84c1d, branch fix/drive-gone-signal) 🧪

OP9 probe after the maintainer deleted the test folder forever: the folder listing was 0, but a details query on
each document still returned its cached name, size and flags=7 — no proof. Opening the document threw
`FileNotFoundException: StorageFileLoadException[io_error]`. Now: proof 2 = opening fails with
FileNotFoundException while the phone has a validated connection (offline = unknown, kept); the listing is a direct
children query (failure = null, not an empty list). OP9 2.4.9-calls5: "100 Drive copies, folder listing 0, 100
deleted in Drive and forgotten"; list 108 → 22, the 19 real recordings kept their phone copy. Not yet seen: the
offline path (keeps everything by code; no deleted file was left to test it with).
Roll back this part alone: `git revert -m 1 d9b84c1d`.

