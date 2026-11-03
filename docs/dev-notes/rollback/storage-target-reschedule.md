# Rollback: changing where recordings are saved re-applies the Drive schedule

🧪 VERIFYING — merged 2026-11-03. StorageTargetRescheduleTest fails on the old code and passes on the new.
To settle: Settings ▸ Save and sync ▸ switch to "Device + Google Drive" with Daily set, and the log shows
"Periodic sweep scheduled" at once.

## Why
Found on the OP9 while testing v2.4.9's Drive copy: only the schedule/time controls called
`SyncScheduler.apply`. Switching to Drive with a daily schedule queued nothing until the next app start or call.

## What changed
`SettingsViewModel.setStorageTarget` and `setDriveFolderUri` call `SyncScheduler.apply`. The setup wizard already did.

## How to roll back
```
git revert -m 1 d68e3e6d
```
(`d68e3e6d` = the merge of `fix/storage-target-reschedule` into integration/next.)
