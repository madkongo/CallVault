# Rollback: the scheduled Drive copy uploads newest first

🧪 VERIFYING — merged 2026-11-03. Unit tests pass (DriveSweepOrderTest). To settle: the OP9 run with a Drive folder,
then Eugene (Honor BVL-N49) going a schedule cycle without the "not reaching Drive" warning.

## Why
Eugene's Honor (2026-11-03) warned "Записи не попадают в Drive". The daily/weekly `SyncSweepWorker` uploaded in
folder-listing (name) order; his file names start with the contact, so old calls went first, a run cut off part-way
never reached the recent ones, and those are what the warning counts.

## What changed
- `DriveSweepOrder.newestFirst` (new, pure). `SyncSweepWorker` now makes two passes: stamp what Drive already has,
  then upload the rest newest first (undated last). Whatever a run does not reach is picked up by the next run,
  with any new calls, because every run lists the folder afresh. Log: "Sweep: N recording(s) to copy to Drive".
- Rolling this back after `feat/drive-copy-notice` may conflict in `SyncSweepWorker.kt`; revert that one first.

## How to roll back
```
git revert -m 1 9a0928aa
```
(`9a0928aa` = the merge of `fix/drive-sweep-newest-first` into integration/next.)
