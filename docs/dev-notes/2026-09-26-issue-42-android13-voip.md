# Issue #42 — Android 13 cannot record app calls; a speakerphone MIC fallback; Keyyo voicemail

**Status:** 📐 researched 2026-09-26 (AOSP source + the reporter's patches and log). Nothing built.
Reporter: POCO F3 (`alioth`), MIUI, Android 13, CallVault 2.4.0, standalone, speakerphone user. His
package (AI-assisted patches, PATCH_NOTES, one debug report) is attached to the issue.

## In plain words

1. **He is right, and we predicted it.** On Android 13 and older the shell user is not given the one
   permission app-call capture needs (`CAPTURE_VOICE_COMMUNICATION_OUTPUT`). Android adds it to the shell
   only from Android 14. So on Android 12/13 **app calls are never recorded**. ~~and today the app says
   nothing~~ **Corrected 2026-09-27:** Settings does show *"Couldn't enable on this device — VoIP recording
   isn't available here"* once, at the moment the toggle is switched on (`VoipRecordingToggle`,
   `voip_recording_unavailable`). But the toggle stays on, nothing says it again later, and the reason
   (Android version) is not named.
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

## 2026-09-27 — can we take his changes without harming current users? (maintainer's question)

**Short answer: yes for the microphone fallback, as an opt-in that only exists where today nothing is
recorded. No for the voicemail change as written; a narrow version is possible but is a product call.**

### Microphone fallback — safe if gated, because its trigger is "we would have recorded nothing"

- It runs only where `armVoipCapture` fails. On every phone that records app calls today (Android 14+,
  standalone) arming succeeds, so the new path is never reached — no change to them.
- **Shizuku mode is already excluded** (`ModeCapability.VOIP_RECORDING` greys the toggle out), which
  matters: a microphone `AudioRecord` in a Shizuku-hosted process is forbidden by our own rules
  (memory `shizuku-mode-capture-rules`). The gate must stay "standalone and arming refused".
- **Must be opt-in, not automatic** as in his patch: on the earpiece it records only the user, and a
  recording that silently lacks the other person is worse than a clear "not available".
- **Must not lie about the far party**: his patch returns `voipFarPartyHeard() = true`; our health
  tracking (`CallOutcome.of`) would then never flag a one-sided recording. Report "microphone only"
  honestly instead.
- **Risk to the call itself (📐):** on a phone where the VoIP app opens a plain `MIC` rather than
  `VOICE_COMMUNICATION`, Android gives the mic to the latest starter — our capture could silence the
  app's own mic. The July S24 FE separated-rooms test says the far end kept hearing; his POCO works; not
  proven for every phone. The opt-in and the reporter's testing are the mitigation; he should confirm
  the other side always heard him.
- Worth checking when built: his log shows `capture#2 opened … (now live: 2)` on the fallback start — a
  first capture already open. Could be harmless (the sync ledger) or a leak; a device log decides.

### Voicemail continuation — not as written

- His trigger is "any `USAGE_MEDIA` playing after the app call": music resuming after a WhatsApp call
  would keep the microphone recording for up to 10 minutes. Real harm to every fallback user.
- A narrow version is possible: continue only when the playback belongs to the **same app uid** that
  set `MODE_IN_COMMUNICATION`. The app process cannot see playback uids (his `ownerUid=-1`), but the
  shell daemon can (`AudioPlaybackConfiguration` uid, mode owner from `dumpsys audio`). Medium work.
- It only matters in microphone mode (the normal capture taps call audio, not media playback), and it
  records a voicemail being listened to — whether CallVault should do that is the maintainer's call.
- Today on his phone each voicemail play leaves a ~1 s junk recording; with the narrow rule it becomes a
  full voicemail recording, without it the junk stays (same as any Android ≤13 user never sees).
