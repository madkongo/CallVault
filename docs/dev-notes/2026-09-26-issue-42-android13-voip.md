# Issue #42 — Android 13 cannot record app calls; a speakerphone MIC fallback; Keyyo voicemail

**Status:** 📐 researched 2026-09-26 (AOSP source + the reporter's patches and log). Nothing built.
Reporter: POCO F3 (`alioth`), MIUI, Android 13, CallVault 2.4.0, standalone, speakerphone user. His
package (AI-assisted patches, PATCH_NOTES, one debug report) is attached to the issue.

## In plain words

1. **He is right, and we predicted it.** On Android 13 and older the shell user is not given the one
   permission app-call capture needs (`CAPTURE_VOICE_COMMUNICATION_OUTPUT`). Android adds it to the shell
   only from Android 14. So on Android 12/13 **app calls are never recorded** — and today the app says
   nothing: the setting stays on, the log says "policy refused", no file is made.
2. **There is no clean way around it without root.** The permission is role-managed and cannot be
   granted; Android 13 refuses a capture of call audio without it, and other capture routes exclude call
   audio by design. What is left is his idea: record the **microphone**. On speakerphone that hears both
   sides; on the earpiece it hears only the user.
3. **The Keyyo voicemail part is a different thing** (playing a voicemail, not a call), and his
   continuation logic as written would keep the microphone recording for up to 10 minutes whenever any
   music plays after an app call. Not adoptable as is.

## Evidence

- **AOSP `packages/Shell/AndroidManifest.xml`:** android12-release and android13-release list
  `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_AUDIO_HOTWORD` (A13 adds `MODIFY_AUDIO_ROUTING`); only
  android14-release adds `CAPTURE_VOICE_COMMUNICATION_OUTPUT` (and `CAPTURE_MEDIA_OUTPUT`). Already found
  from source on 2026-08-29 (memory `voip-recording-feasibility`, "Possible Android 13 gap"); #42 is the
  first hardware confirmation: his log says `registerAudioPolicy rejected (rc=-1)`, and `pm grant` fails
  because the permission is managed by a role.
- **AOSP android13 `AudioService.isPolicyRegisterAllowed`:** a `LOOP_BACK_RENDER` mix matching
  `USAGE_VOICE_COMMUNICATION` requires `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, else registration fails.
  A mix by UID instead does not escape it — the source's own comment: *"for UID, USERID or EXCLUDE rules,
  the capture will be silenced in AudioPolicyMix."* Playback capture (MediaProjection) never includes
  voice-communication usage. 📐 No non-root far-side route on Android ≤13 found.
- **The microphone is allowed alongside WhatsApp on Android 13:** the concurrency rules in
  `2026-09-23-voip-near-side-zeros.md` hold there too (shell has `CAPTURE_AUDIO_OUTPUT` on A13), which
  matches his result that it records.
- **Our code:** `VoipCaptureController.sync` logs "VoIP capture unavailable on this device (policy
  refused)" and returns false; `RecorderServiceImpl.startVoipRecording` refuses. Nothing reaches the UI.

## His patches, reviewed

| Patch | Verdict |
|---|---|
| `01` provider authority `.instrtest` | His test plumbing, he says so. Ignore. |
| `02` MIC fallback when the policy is not armed | **Right idea, wrong shape.** It silently switches every Android ≤13 user to an acoustic recording, sets `voipFarPartyHeard()` to **true** unconditionally (so a recording with no far party passes as healthy), and uses the direct-capture path rather than the VoIP session's near side. |
| `03` voicemail continuation | **Not adoptable.** Continues on *any* `USAGE_MEDIA` playback while telephony is idle — music resuming after a WhatsApp call keeps the mic recording up to 10 min. No check that the playback belongs to the app of the call (his log shows `ownerUid=-1`). |

## Proposal (needs the maintainer's word)

1. **Say it (small, do regardless).** When arming is refused on Android ≤13, show it where the VoIP
   setting lives: "App-call recording needs Android 14 or later on this phone." Also covers the private
   Note20 reporter (Android 13, VoIP on). The debug report header should carry `VoIP policy: refused`.
2. **Opt-in "record app calls with the microphone" (medium).** Offered only where the policy is refused;
   explained as speakerphone-only. Uses a mic-only capture, records the outcome honestly
   (`farPartyHeard=false`, a "microphone only" mark on the recording). Testable here by forcing the
   refused path on the OP9/OP12 and on an Android 13 emulator image (not installed yet — only
   android-36); the reporter offered to test on the POCO F3.
3. **Keyyo voicemail: leave out** unless the maintainer wants voicemail playback recorded. If he does,
   the rule must be "playback from the same app uid as the call", and it only matters in mic mode.

Side note from his log: `capture#2 opened … (now live: 2)` on the fallback start — a first capture
still open. Worth a look if (2) is built, not before.
