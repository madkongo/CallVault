# Rollback: a call that is over before the recording starts is not recorded (#68)

🧪 VERIFYING — merged 2026-11-02 as cbeca91e (`fix/voip-stale-start-68`, commits 4eae416f and b2c38892).
2374 unit tests pass. Not tried on a phone; the case itself is a race we cannot trigger on demand.
To settle: the reporter's POCO X5 Pro (HyperOS, Android 14) runs the build for a few days. A WhatsApp call that
drops at once should leave no file and no "Only your side … recorded" notification. The log shows one of the two
lines below. Normal calls must still record.

## What happened

Issue #68 (POCO X5 Pro 5G, HyperOS OS2.0.17, Android 14, 2.4.8). WhatsApp held `MODE_IN_COMMUNICATION` for 80 ms.
Our mode listener ran 290 ms later with the old mode, started a recording 1.4 s after that, and 1.5 s later ended
it. The 0.7 s file had no far side, so the user was told the other app blocks capture.

## What changed

- `VoipSwitchPolicy.stillStarting()` + `VoipCallDetector.evaluate`: before acting on a START, the live
  `AudioManager.mode` is read again. If it no longer says an app call, nothing starts. Log: "mode=IN_COMMUNICATION
  was reported, but the mode is already …".
- `VoipStubPolicy` + `VoipRecordingCoordinator.onCallEnded`: a recording under 3 s whose far side was never heard is
  discarded with no warning (the call ended while starting). Log: "The app call ended before the recording got
  going". One-sided recordings of 3 s or more still warn as before.
- App calls only. No preference or data change.

## What users would notice after a rollback

Very short or dropped app calls may again leave a sub-second file and the "other app blocks capture" warning.

## How to roll back

```
git revert -m 1 cbeca91e
```
