# Rollback: app-call app identified late still names the file

✅ VERIFIED 2026-11-02 on Евгений's OnePlus CPH2747 (Android 17, Shizuku), build 2.4.8-echofix2, reported via the maintainer: Telegram recordings now carry the name and icon. Other phones untested.

Was: 🧪 VERIFYING — merged 2026-11-01 as 9d025b7b. 2362 unit tests pass. Not yet tried on a phone.
To settle: a Telegram call of 30 s or more on Евгений's OnePlus CPH2747. The file should be named `..._voip-Telegram...`
and show the Telegram badge in Recordings. The log should say "Call app found 3 s into the call".

## What happened

Every Telegram call in the 2026-11-01 report logged "No VoIP app uid after 7 attempts". Telegram's call audio track
shows up about 2.5 s after the audio mode flips (endprobe vcPlay=1 at +2.5 s). The start-time lookup gives up after
1.2 s, so the file had no app and the list showed no icon. WhatsApp's track is already there and was named.

## What changed

- `VoipLateCaller.withApp()` puts an app found late onto the `_voip` marker.
- `VoipRecordingCoordinator`: when the app is unknown at the start, it asks again at 3/8/20 s. Once found:
  - the name is applied at publish and in the catalog;
  - the notification is labelled;
  - an app the user switched off for recording is stopped and discarded (still in staging, so no trace);
  - the caller lookup runs for that app, and an ignored caller is discarded as before.
- "Start when they answer" still needs the app at the start; a late-found app does not turn it on.
- No preference or data change.

## How to roll back

```
git revert -m 1 9d025b7b
```

## Follow-up: a switched-off app needs two lookups (2026-11-02)

🧪 VERIFYING — merged as 82465bc7 (`fix/voip-late-app-confirm`, commit 6fe66636). 2374 unit tests pass.
A single late lookup that named a switched-off app used to discard the whole call; a wrong lookup would lose a call
the user wanted. Now a second lookup 1 s later must name the same app (`VoipLateCaller.verdict`). If it does not,
the recording goes on unnamed and the next retry asks again. Log lines: "…and confirmed: … switched off" or
"Call app looked like … then …; recording on". To settle: on a phone, switch Telegram off for recording, make a
Telegram call; nothing should be kept, and the log should show the "confirmed" line.

Roll back this follow-up alone:

```
git revert -m 1 82465bc7
```
