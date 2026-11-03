# Rollback: direct capture tells the ledger it released the mic

🧪 VERIFYING — merged 2026-11-03. 2374 unit tests pass. No phone has run it yet.
To settle: on the OP9, make one carrier call with debug logging on. The log should show
`capture#N released: direct capture` and `after stopping direct capture: no capture left open in this process`,
and the next call's `now live:` count should not climb.

**OP9 2026-11-03, build 2.4.8-next8:** the maintainer made one outgoing carrier call (11:02:45–11:03:02). Log: `capture#1 released: direct capture (still live: 0)` then `after stopping direct capture: no capture left open in this process`; the recording was published. Stays 🧪 until the maintainer confirms.

## What changed

- `DirectAudioRecorderSession.stop()` now calls `CaptureAudit.released(...)` for the AudioRecord it releases, then
  `assertNoneLive`. Before, only `cleanupPartial()` (failed start) did. Every finished carrier call therefore left
  a false "STILL OPEN — direct capture" entry, and the `now live:` count grew by one per call.
- Found in the #69 report (Realme GT 7): 5 false entries after a day of calls. MicOpHeal showed 0 shell microphone
  ops after each call, so the mic really was released; only the log was wrong.
- Log-only change. No behaviour, pref, database or file change.

## How to roll back

```
git revert -m 1 9d0ee6c8
```

(`9d0ee6c8` = the merge of `fix/direct-capture-audit-release` (5d221409) into integration/next.)
