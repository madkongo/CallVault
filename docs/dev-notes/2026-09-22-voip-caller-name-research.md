# 2026-09-22 — contact names for VoIP calls: why the OP9 gets none, and what else could give them

Status: **✅ VERIFIED 2026-09-22** by the maintainer on the OP9 (Android 14, built-in mode): the 12:17
WhatsApp recording shows its contact name in the app ("name appears fine"). Merged to `main` (`ee1ff4f1`).
Still 🧪: the retry path on its own, Telegram/Signal on Android 14, `SpannableString` titles.

## The report

Maintainer: "check how we can get contact names for VoIP calls as well." The app already does — since
v1.4.7 the daemon reads the VoIP app's ongoing-call notification (`VoipCallerName`, `dumpsys notification
--noredact`) and puts the person in the file name. So the real question was why it does not work for him.

## Measured, 2026-09-22

| | OP12 (Android 16) | OP9 (Android 14) |
|---|---|---|
| VoIP recordings with a name | **18 of 18** WhatsApp | **1 of 6** WhatsApp |
| `dumpsys notification --noredact` contains `ONGOING_EVENT` | 12 times | **0 times** |
| … contains `flags=0x…` (hex) | 1602 | 868 |
| Titles printed as `SpannableString (…)` | — | 4 (other apps) |
| VoIP-app rows in the system call log | 0 of 5999 | 0 of 3000 |
| `cmd notification list` from shell | works | works |
| WhatsApp | 2.26.36.74, POST_NOTIFICATIONS granted | same |

## Cause (read from AOSP, then confirmed above)

`VoipCallerName.extractFromDump` keeps only records containing the literal `ONGOING_EVENT`. That word is
printed by `Notification.flagsToString`, which **exists only from Android 15**; Android 14's
`NotificationRecord.dump` prints `flags=0x62` (0x2 ONGOING_EVENT | 0x20 NO_CLEAR | 0x40 FOREGROUND_SERVICE).
On an Android 14 phone the filter therefore rejects every record and the recording is nameless. The OP9's
one success (2026-08-24) is unexplained — most likely a build from before the filter (it replaced a
`category=call` filter on 2026-07-26) was still on that phone then.

Sources: `services/core/java/com/android/server/notification/NotificationRecord.java` at `android14-release`
(line ~484, hex) vs `android15-release`/`android16-release` (`flagsToString`);
`core/java/android/app/Notification.java` at `android14-release` (no `flagsToString`).

A second, smaller gap in the same parser: a title that was a `Spanned` on the app side crosses Binder as a
`SpannableString` and dumps as `android.title=SpannableString (Name)`; `TITLE_REGEX` matches `String (` only.
WhatsApp's titles were plain `String` in all 18 OP12 hits, so this has not bitten yet.

## What else the research established (with sources in the agent's report, summarised)

- **CallStyle** (Android 12+) always copies the person's name into `android.title`
  (`CallStyle.addExtras → fixTitleAndTextExtras`), so a CallStyle notification is readable by the same
  regex. `android.callPerson` is a `Person` with no `toString`, so it dumps as `Person (android.app.Person@…)`
  — the name is NOT recoverable from that field. Neither phone had a CallStyle notification up at the time.
- **Signal** (source): title = contact display name (unless message privacy hides it), `CallStyle` when not
  background-restricted. Telecom integration gated off by remote config (min SDK 37) and would not log to the
  call log even when on.
- **Telegram** (source): 1:1 ongoing call → name in `android.text`, title "Outgoing call"; incoming → CallStyle
  (name in title). `USE_CONNECTION_SERVICE` is hard-coded `false`: never in Telecom.
- **WhatsApp**: closed source; the empty call logs on both phones say it does not opt into
  `EXTRA_LOG_SELF_MANAGED_CALLS` here. Android 16 QPR1 / 17's "unified call log" (Jetpack Telecom 1.1) may
  change that in future — WhatsApp is already selectable in the 17 QPR1 beta Phone app.
- **System call log / InCallService**: self-managed calls are logged only if the PhoneAccount opts in, and
  delivered only to a default dialer or a service holding `CONTROL_INCALL_EXPERIENCE` / `MANAGE_ONGOING_CALLS`.
  Closed for us, confirmed by 0 rows on both phones.
- **`dumpsys activity top`** prints view classes, never text. **`uiautomator dump`** (shell, no grant) prints
  the foreground window's accessibility tree with text, but needs the UI to go idle — a ticking call timer may
  prevent that. Untested.
- **Shell can grant a NotificationListenerService without the user**: `cmd notification allow_listener
  <component>` is accepted from uid 2000 on 14 and 16 (`NotificationShellCmd`). A listener gets the real
  extras — `EXTRA_CALL_PERSON` as an object, `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_PEOPLE_LIST` — with no text
  parsing. Would need re-granting after every install-over, like WRITE_SECURE_SETTINGS.
- **`cmd notification get <key>`** (shell) dumps ONE record unredacted: cheaper than the whole multi-MB dump
  and free of the 1.5 s timeout race.
- **Competitors**: Cube ACR's FAQ ties VoIP contact names to its accessibility "App Connector"; Boldbeast
  documents no source. Accessibility from shell (`settings put secure enabled_accessibility_services`) is also
  grant-free but heavier and screen-dependent.

## Options (not decided)

1. **Fix the parser** — accept `flags=0x…` with bit 0x2 (or 0x40) as well as `ONGOING_EVENT`, and
   `(String|SpannableString) (`. Small, pinned by a test with a real Android 14 record. Better still, read
   `cmd notification list`, filter the package, `cmd notification get <key>` each — one record, unredacted.
2. **Retry at call end** — today the name is read once, at the instant the audio mode flips
   (`VoipRecordingCoordinator.onCallStarted`), before the file exists; a notification posted a second later
   is never seen. Reading again at stop and renaming would catch that. Whether it happens is what the live
   probe below measures.
3. **NotificationListenerService granted from shell** — highest fidelity, no regex; more moving parts and a
   re-grant on every update. Worth it only if 1+2 still miss on a real call.

Recommendation: 1 and 2 together; 3 held.

## Owed measurement

`docs/dev-notes/spike-tools/voip-notification-timeline.sh daabf34f com.whatsapp` while a WhatsApp call is
placed to or from the OP9: shows, second by second from the mode flip, when WhatsApp's record appears, its
flags, and whether the name is a `String` or `SpannableString` in the title or text. Raw dumps hold names —
delete them afterwards.

## Fix built (12:10, 🧪 VERIFYING) — branch `fix/voip-caller-name-android14`, `491f9856`

Options 1 and 2, on the maintainer's word:
- `VoipCallerName`: ongoing by the word `ONGOING_EVENT` OR the 0x2 bit of a hex `flags=0x…`; title/text may
  be `String` or `SpannableString`; records read one at a time via `cmd notification list` → `get <key>`
  (unredacted, a few KB each), the whole dump as fallback. Tests shaped after the OP9's real record.
- `VoipLateCaller` + `VoipRecordingCoordinator`: when the first lookup at the mode flip finds nothing, ask
  again 3, 8 and 20 s in; the file is published only at the end, so the late name goes into the name it is
  published under. No rename.

**Live WhatsApp call, OP12 ↔ OP9, 12:02–12:05.** Captured mid-call on the OP9 with `cmd notification get`:
`flags=0x206a`, `android.title=String (פרוזה)`, `android.text=String (Ongoing…)`, `android.callType=2`,
CallStyle template — the hex-flags shape the old parser rejected, with the name where the new one reads it.
On the OP12: `flags=ONGOING_EVENT|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE|NO_DISMISS`, same fields.
The OP12 (old build, Android 16) recorded both calls with names, as it has 18 times before.

**The OP9 recorded nothing — and that is expected: it is in Shizuku mode, which does not support VoIP
capture** (no audio policy registered there; the OP12 shows one). So the Android 14 fix has NOT been seen
to work on a phone: it needs an Android 14 phone in built-in mode, i.e. the OP9 switched over for one call.
The probe script's first version grepped the ringer-mode line and never fired; fixed to `Actual mode =`.

## Measured on the OP9 in built-in mode, 12:17 (🧪 by the assistant; the maintainer has not opened it)

The maintainer switched the OP9 to built-in mode (`1 AudioMix` registered) and made a WhatsApp call. The
OP9 published **`20260922_121732.336+0300_voip-WhatsApp_פרוזה.ogg`** (127 KB) — named, on the Android 14
phone where 5 of the 6 earlier WhatsApp recordings had no name. Whether the first lookup or a retry found it
is unknown: this ROM keeps no `CV:` lines in logcat and the probe script did not fire a second time (its
mode poll never matched; not chased). Still owed: the maintainer's own look at the row in the app, and a
Telegram or Signal call on Android 14 — the same parser, but the name sits in `android.text` for Telegram.
