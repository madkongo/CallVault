# Rollback: one Shizuku recorder retire or bind at a time

🧪 VERIFYING — merged 2026-11-03. 2378 unit tests pass (two new ones fail on the old code). To settle: a run of
install-overs on the OP9 in Shizuku mode with no ~60 s "recorder down" window, then an update in real use.

## The bug (measured on the OP9, Shizuku mode)

- 2026-11-02 09:28 and 2026-11-03 11:55 (1 of 4 timed install-overs): after the update the recorder was down
  for about 62 s. A call in that minute would have been lost.
- The update receiver, the app start and the keep-alive each found the old recorder at the same moment, and
  each retired it and bound a new one. Two Shizuku service records were created 11 ms apart.
- Shizuku keeps one record per service: the second replaced the first (Shizuku manager: "unable to find token
  …"). The recorder that started answered a binding the app had already dropped, five waits timed out at 10 s,
  and nothing retried until the keep-alive's 60 s tick.

## What changed

- `RecorderBackend`: `ensureShizukuRunning`, `retireShizukuService` and `retireIfStale` run under one
  `ReentrantLock` (`tryLock` 40 s, then give up with a log line). A caller that waited finds the fresh recorder
  and reuses it.
- `retireShizukuService`: a host that is dead and no longer the connected one is left alone ("already gone and
  was replaced"), so a queued retire never tears down the recorder another caller just brought up.
- `UpdatePackageReplacedReceiver`: retires only a host running from the replaced APK (`retireIfStale`), not
  whatever is connected. A stale host that connects later is still retired by `ensureShizukuRunning`'s own check.
- Shizuku mode only. Standalone (ADB) mode is untouched. No pref, database or file change.

## How to roll back

```
git revert -m 1 726c6496
```

(`726c6496` = the merge of `fix/shizuku-serial-rebind` (066784d4) into integration/next.)
