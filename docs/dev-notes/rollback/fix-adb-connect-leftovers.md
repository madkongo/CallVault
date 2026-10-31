# Rollback: `fix/adb-connect-leftovers`

🧪 VERIFYING — built 2026-10-31, unit tests pass, **not run on a phone**.

## What changed

- `AdbShell.ensureConnected`, `forceReconnect` and the private Wireless-debugging connect were `@Synchronized`
  on the `AdbShell` object. They now share `connectLock`, a `BoundedEntryLock` (new file
  `integrations/adb/BoundedEntryLock.kt`, a re-entrant lock). A caller that cannot get in within
  `CONNECT_ENTRY_WAIT_MS` = 60 s gets the existing "not connected" answer (`false`, or the new
  `BaseConnect.CONNECT_LOCK_BUSY`, which the arming path reports like `NO_ADB_SERVICE`) and a log line
  `… still holds the ADB connect lock after 60000ms (held by <thread>) …`.
  Uncontended and re-entrant entry behave exactly as the monitor did; a waiter behind a healthy holder still
  waits (60 s is above the 📐 calculated longest healthy hold, ≈ 29 s Wireless debugging / ≈ 48 s offline).
- Offline mode's loopback self-heal loop in `ensureConnected` now ends 12 s after it starts on the clock,
  instead of after 12 s of *sleeps* (which ignored up to 16 s per connect round).
- No preferences, no data, no migrations.

## Revert

```
git revert -m 1 f90ff314
```

Self-contained: nothing else depends on `BoundedEntryLock` or `CONNECT_LOCK_BUSY`.

## What users would notice after a rollback

Nothing on a healthy phone. On a phone where an ADB connect gets stuck (libadb lock wedge), recording
attempts would again wait behind it indefinitely instead of failing after a minute and retrying — the
silent "recorder down for hours" pattern.

## Symptom that would justify rolling back

- Recordings failing to start with the log line "still holds the ADB connect lock after 60000ms" while the
  holder thread was in fact making progress (i.e. a healthy connect that legitimately took > 60 s).
- Any new "not connected" / recorder-down report that starts after this merge on a phone that connected
  fine before, especially in offline (loopback) mode.
- A deadlock or a thread dump showing two threads waiting on `connectLock` and `heavyOperationLock` in
  opposite orders (the lock order is meant to be unchanged: heavy → connect).
