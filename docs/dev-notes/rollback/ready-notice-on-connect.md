# Rollback: "ready" as soon as any recorder connects

🧪 VERIFYING — merged 2026-11-03. 2378 unit tests pass. To settle: after an install-over the notification
turns from "Call recorder starting up…" to "Ready to record calls" within a few seconds, not a minute.

## The bug

The keep-alive's readiness notification went to "ready" only when its own relaunch succeeded, or at its 60 s
check. After an update the app start usually brings the recorder up first, so the notification said "starting
up" for a minute while recording already worked. Measured on the OP9: 2026-11-02 10:17 (next7) and
2026-11-03 11:57 (next8, recorder armed at 11:57:13, notification ready at 11:58:13).

## What changed

- `RecorderConnection.onConnected`: a new hook fired on every fresh binder, after `onDaemonReady`. A failing
  hook is logged and does not lose the binder.
- `DaemonKeepAliveService` sets it next to `onDeath`, refreshes the notification on the main thread when the
  ready state changed, and clears it in `onDestroy`.
- Both modes. No pref, database or file change.

## How to roll back

```
git revert -m 1 d7371db4
```

(`d7371db4` = the merge of `fix/ready-notice-on-connect` into integration/next.)
