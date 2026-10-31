# Root mode: what it would buy

📐 CALCULATED 2026-10-25. Three research passes: app stores + root recorders, AOSP source per Android version,
Samsung Wi-Fi Calling. Nothing was built or run; downloaded code was only read.

## Verdict

Root does **not** give better audio on phone calls, and it does not fix Samsung Wi-Fi Calling by itself. It
gives two things:
1. **Reliability on phone calls:** no Wi-Fi, pairing or Wireless debugging; works before the first unlock
   after a reboot; Android can bind us when a call starts. This removes most of our support load.
2. **App calls on Android 11–13**, which the shell can never do (#42).

## 1. Phone calls

- Every root recorder found records from the same `VOICE_CALL` / `VOICE_UPLINK`+`VOICE_DOWNLINK` sources
  the shell already reaches. **BCR** is a Magisk priv-app with only `CAPTURE_AUDIO_OUTPUT` +
  `CONTROL_INCALL_EXPERIENCE`, detecting calls with an InCallService. **SKVALEX** does root, built-in ADB
  and Shizuku all through the same uplink+downlink source and removed its old root-only CAF/ALSA/MSM
  sources. BCR's maintainer: "The audio that's recorded is determined entirely by Android and its audio
  drivers" (#504, #461).
- Store landscape, Google Play 2026-10-25:

  | App | Installs | Rating | Root mode |
  |---|---|---|---|
  | Cube ACR | 56.7M | 4.09 | none, accessibility based |
  | ACR Phone (NLL) | 6.5M | 4.18 | APH helper or Magisk module; VoIP only via ConnectionService apps, one-sided off Qualcomm |
  | Talker ACR | 7.7M | 3.88 | none |
  | SKVALEX | 0.86M | 3.12 | root = Shizuku = ADB, same source; internet calls "not supported" |
  | Boldbeast VoIP | 51k | 2.97 | root mixed mono, manual start, abandoned since 2021 |
  | BCR (GitHub) | — | — | Magisk/KernelSU, phone calls only |

## 2. App calls (AOSP source, per version)

Gate: `AudioService.isPolicyRegisterAllowed()` + native `captureVoiceCommunicationOutputAllowed()`; a mix
only gets VOICE_COMMUNICATION when flagged by that check.

| Android | What the mix needs | Shell has it? | Root (uid 0) passes? |
|---|---|---|---|
| 10 | impossible: loopback mixes only match MEDIA/GAME/UNKNOWN | no | **no**, nothing unlocks it |
| 11 | CAPTURE_VOICE_COMMUNICATION_OUTPUT + a *privileged* mix (16 kHz mono 16-bit) + MODIFY_AUDIO_ROUTING | no | **yes** |
| 12 | CAPTURE_VOICE_COMMUNICATION_OUTPUT + MODIFY_AUDIO_ROUTING | no | **yes** |
| 13 | CAPTURE_VOICE_COMMUNICATION_OUTPUT (shell lacks only this one) | no | **yes** |
| 14+ | same as 12–13 | **yes** (today's path) | yes, no gain |

- Root passes because Java `checkComponentPermission` grants uid 0/1000, and every native capture check
  starts with `isAudioServerOrRootUid(uid)`. The permissions are `signature|privileged`, so a Magisk priv-app
  route also works.
- Apps' opt-out: only ALLOW_CAPTURE_BY_NONE blocks it, and root doesn't get past that. WhatsApp evidently
  does not set it, since our A14 capture works.
- Simplest route: run our existing helper as root on 11–13; on 11, make the mix privileged and 16 kHz mono.
- Nobody else does this: no maintained root recorder records WhatsApp with both sides on any version. The
  one exception is a research proof of concept that injects into audioserver
  (nighthawkk/AudioServer-Voip-Recorder, per-firmware offsets, SELinux injection). Not shippable.
- The "far party hears nothing" problem (`voip-mic-capture-mutes-user`): root changes nothing in the AOSP
  concurrency logic (the shell already holds CAPTURE_AUDIO_OUTPUT on 14). It is probably HAL/input
  reconfiguration from two different mic sources. Not a root fix. Root-only alternative: hook WhatsApp's own
  capture (LSPosed/Frida), which is fragile and injects into another app. Not recommended.

## 3. Samsung Wi-Fi Calling

- Root recorders fail too: BCR #176, #461, #587, #642, #668 (static even with root and a custom kernel).
  SKVALEX tags Wi-Fi calls "[wifi]" and says they are mostly empty on Samsung. **BCR #587:** fine on One UI
  6.0, one-sided on every VoWiFi call after **6.1**, the update that added Samsung's own VoWiFi recording.
- `crwfcmix`: AOSP's AudioPolicyManager connects OUT_REMOTE_SUBMIX at a policy mix's address when an input
  on that mix starts. So some component registered a mix "crwfcmix:<n>", and Samsung reroutes our VOICE_CALL
  capture to it during Wi-Fi calls. We get only our own side: either the downlink player does not match the
  mix rule for us, or a Samsung-only gate applies. Under AOSP rules the shell (CAPTURE_AUDIO_OUTPUT) can
  already read such a mix, **so this may not be a root question at all.** Not found in decompiled Samsung
  framework/services; it is likely native, in the audio HAL or policy.
- **Practical blocker:** US Snapdragon Samsungs (mac's S24) cannot unlock the bootloader, so cannot root.
- **Workaround that needs no root:** Samsung's own recorder handles VoWiFi since One UI 6.1, and US models got
  a recorder in One UI 7 (announced to both parties, per call). Ask mac to try it on a Wi-Fi call: it is both a
  workaround and a diagnostic.
- Diagnostic for a Samsung owner during a Wi-Fi call (shell, no root): `dumpsys media.audio_policy` (mixes,
  `crwfcmix` rules), `dumpsys audio` (who registered it), `dumpsys media.audio_flinger` (which tracks play to
  that output).

## Recommendation

1. **Root mode step 1 (worth it):** start the same helper through `su` instead of ADB, as uid 2000 first
   (identical capture), with a third mode, setup screen and onboarding decision. Gain: reliability. The OP9
   can test it.
2. **Step 2:** app calls on Android 11–13 with the helper as uid 0. Needs an A11–13 rooted test device; the
   OP9 is on 14. An AVD (API 33, `adb root`) can prove the policy registers and the capture runs.
3. **Do not pursue:** audioserver hooking, WhatsApp hooking, root as a Samsung Wi-Fi Calling fix. Instead
   ask mac for the dumpsys trio and a try of Samsung's own recorder.
