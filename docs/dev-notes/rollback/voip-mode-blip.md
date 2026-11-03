# Rollback: app-call recording survives a short audio-mode dip

✅ VERIFIED 2026-11-02 on Евгений's OnePlus CPH2747 (Android 17, Shizuku), build 2.4.8-echofix2, reported via the maintainer: Telegram no longer cuts out mid-call. Other phones untested.

Was: 🧪 VERIFYING — merged 2026-11-01 as adfc0d9b. 2357 unit tests pass. Not yet tried on a phone.
To settle: on Евгений's OnePlus CPH2747 (Android 17), make three Telegram calls of 30 s or more in a row.
Each should give one full recording, with no 1–2 s "only your side" file at hang-up.

## What happened

Report 2026-11-01 ("Telegram records every other time", "recording is interrupted after once"). In the debug log
both failed Telegram calls show the same sequence:

- 15:16:39.1 mode → NORMAL, 15:16:40.3 back to IN_COMMUNICATION, 15:16:40.6 "VoIP call ended" (mode still
  IN_COMMUNICATION, call still up for another 20 s).
- 15:25:36.1 mode → NORMAL, 15:25:36.8 back, 15:25:37.6 "VoIP call ended".

The 1.5 s end debounce was scheduled on the dip and never cancelled when the mode came back
(`VoipSwitchPolicy.decide` returned NOTHING for "call up, mode up"). The rest of the call went unrecorded. The
hang-up then fired a fresh START, which made a 1 s one-sided stub and the "Only your side was recorded" home card.

## What changed

- `VoipSwitchPolicy`: new `Action.STAY` for "app call up, mode still IN_COMMUNICATION", and `stillEnded()`.
- `VoipCallDetector`: STAY cancels a pending end. The debounced end re-reads the mode and ends only if it still
  says ended.
- No preference or data change.

## How to roll back

```
git revert -m 1 adfc0d9b
```
