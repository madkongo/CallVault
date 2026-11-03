# Rollback: a finished Drive copy is recorded even when the job is being stopped

🧪 VERIFYING — merged 2026-11-03. MarkDriveNonCancellableTest fails on the old code and passes on the new.

## Why
Eugene's log (Honor BVL-N49, 2026-11-03): `markDrive('…m4a') failed: Job was cancelled`. The file had reached Drive,
but the worker was being stopped, so the catalog row stayed "not in Drive", which the health warning counts.

## What changed
- `RecordingCatalog.markDrive` runs its one database write in `withContext(NonCancellable)`, for all four callers
  (sweep, immediate copy, catalog repair).

## How to roll back
```
git revert -m 1 8055164f
```
(`8055164f` = the merge of `fix/drive-mark-noncancellable` into integration/next.)
