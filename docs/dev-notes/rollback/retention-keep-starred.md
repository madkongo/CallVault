# Rollback: auto-delete never deletes starred recordings

🧪 VERIFYING — merged 2026-11-03 (maintainer's choice after the settings-name audit). RetentionPolicyTest covers
`mayAgeOut`. To settle: star an old recording with auto-delete on; it survives the next daily run.

The size cap already spared starred recordings and its caption said so; the age rule did not. Now
`RetentionSweepWorker` skips starred recordings in the catalog pass and the untracked pass
(`RetentionPolicy.mayAgeOut`); an unreadable starred list skips the age pass for that run.
`retention_description` says "except starred ones" (15 locales, with the renames).

```
git revert -m 1 0e5fe4e7
```
