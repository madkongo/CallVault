# Rollback: app badge appears for a messenger installed while CallVault runs

🧪 VERIFYING — merged 2026-11-02 as e8b19172 (`fix/voip-icon-refresh`). Builds; VoIP unit tests pass; no test covers it
(it needs a PackageManager). To settle: with CallVault running, install a messenger, make a call with it, then
reopen the recordings list after 30 s or more. The row should show that app's own icon, not the grey "people" glyph.

## What happened

OP9, 2026-11-02: Telegram was installed at 10:00:36 and its first call recorded at 10:05 as `..._voip-Telegram_...`.
The list showed the fallback glyph. `VoipAppIcons` built its label → package list once per process, and kept
misses forever, so an app installed later was never matched until a restart.

## What changed

`VoipAppIcons.iconFor`: a miss older than 30 s is dropped and, if the list was built 30 s or more before, the list
is rebuilt once and the lookup retried. A row already drawn keeps its glyph until the list is reopened or scrolled.
No preference or data change.

## How to roll back

```
git revert -m 1 e8b19172
```
