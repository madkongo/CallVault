# Which features work on which Android version — the map for backlog item #1

**Status:** 📐 research only, 2026-09-29. Sources: the app's code, dev-notes, memory, field reports, AOSP. Nothing built.
Confidence: **M** measured on a phone · **C** read from code · **D** docs/AOSP · **G** guess.
API: 30 = Android 11, 31 = 12, 33 = 13, 34 = 14, 36 = 16, 37 = 17. App: `minSdk 30`, `targetSdk 36`, arm64 only.

**Headline:** `ModeCapability` gates features by mode (built-in vs Shizuku), never by Android version. No Settings
toggle checks `SDK_INT`; the only version-aware UI is the audio-source dropdown (`SettingsScreen.kt:1596-1624`).

**Correction to earlier notes:** app calls need Android 14 because the shell only gets
`CAPTURE_VOICE_COMMUNICATION_OUTPUT` from `android14-release` (absent from the 12 and 13 Shell manifests).
`AudioMixingRule.Builder.setTargetMixRole` exists from Android **13**; its absence is only why Android 12 fails
*earlier* (coonrw, NoSuchMethodError). Android 13 gets past it and fails at `registerAudioPolicy rc=-1` (#42).
The earlier "setTargetMixRole is API 34" was wrong; the conclusion (app calls need 34+) stands.

| Feature / setting | Min API | Known broken | Untested | Evidence | Conf. | OEM exceptions |
|---|---|---|---|---|---|---|
| Phone-call recording, direct `AudioRecord(VOICE_CALL)` | 30 | — | 30-33 in-house | `BypassedAudioRecord.kt:114,135`; memory `direct-audiorecord-capture-idea` | M on 34/36, C on 30-33 | Samsung A17 Wi-Fi call inaudible (benjamin, pending); vivo ctor NPE worked around (2.4.4) |
| Resilient recording (handoff) | 33 in practice | **31** (coonrw A12: 0 bytes) | 30, 32 | `HandoffGeometry.kt:78-80` (A11/12 offsets only derived) | M | Xiaomi A13 every-second-call (fix 🧪); Samsung A17 Wi-Fi call |
| App-call (VoIP) recording | **34** | 30-33 (A12 missing method; A13 rc=-1) | 35 | `VoipAudioPolicy.kt:69`; `2026-09-26-issue-42-android13-voip.md` | M + D | vivo: far side zero-filled on any version |
| VoIP auto-start | 34 | as above | 35 | `AppPreferences.kt:953` | C | as above |
| VoIP caller name | 35 | 34 (1 of 6 named on OP9) | — | memory `voip-recording-feasibility` | M | — |
| Record from answer | 30 phone / 34 app | — | other than A14 | memory `record-from-answer` | M on 34 | Telegram/Signal: no timer |
| Offline recording (loopback) | 30 | — | 31-32 | `OfflineRecording.kt`; memory `loopback-tcpip-offwifi` | M on 34/36 | cleared by reboot, needs Wi-Fi once |
| Built-in ADB pairing | 30 | A17 detection (#40 fix 🧪) | 37 real device | `UsbDebuggingPolicy.kt:42-44`, `DeveloperOptionsPolicy.kt:58` | D/C | OPPO/OnePlus/realme + Xiaomi block `pm grant` until a dev option; **pairing lapses ~7 days after pairing unless loopback or timeout 0** (#43) |
| Wireless-debugging control / auto-toggle | 30 | A17 reads settings as "0" — never gate on `SDK_INT` alone | 37 | `SettingsScreen.kt:2423-2429, 2479-2486, 2557` | D | same grant block |
| Daemon keep-alive | 30 | — | 37 | `AppPreferences.kt:961`; `RecordingForegroundService.kt:812` | C | OnePlus kills (voarch) |
| Silent update install | 30 (adb) / 31 (PackageInstaller fallback) | — | 30 | `UpdateInstaller.kt:161` | C | off in Shizuku mode |
| Shizuku mode | 30 (`audio_dup` 33+) | — | 30-33 | `ScrcpyConfig.kt:150` | M on 34/36 | grant block hits Shizuku's prompt too |
| Speaker separation (stereo) | follows capture path | Shizuku; mic-voice-communication (mono) | 30-33 | `ModeCapability.kt:45-50` | M on 36 | HAL must return 2 ch (G) |
| Bluetooth capture | 30 | — | LE Audio/LC3 | memory `bluetooth-capture-works-le-audio-untested` | M | — |
| On-device transcription | 30 arm64 | — | 30-33 | `2026-08-16-on-device-transcription-design.md:157` | M on 36 | RAM, not version |
| Summaries (llama.cpp) | 30 arm64 | — | 30-33; <4 GB RAM | memory `transcription-engine-status` | M on 36 | **no RAM gate in code** |
| Drive sync / storage | 30 | — | — | `ModeCapability.CLOUD_SYNC` | C | — |
| Dynamic colour | 31 | — | — | `Theme.kt:120` | C | — |
| Notification permission prompt | 33 | — | — | `PermissionChecks.kt:31` | C | — |

## Not gated today (what item #1 must fix)

- App-call recording on API ≤ 33 — fails at `VoipAudioPolicy.kt:69` (A12) or registration (A13); a one-shot hint
  at `SettingsScreen.kt:2314` but the toggle stays on.
- Resilient recording on API ≤ 32.
- Summaries: no RAM check.
- Existing gates are mode-only: `SettingsScreen.kt:2252` (`capabilityAvailable`), `AppPreferences.kt:880-965`.

## Gaps and the test that settles each

1. Resilient on A11/A12 — ring offset or handoff itself? An A12 device or API 31 emulator, 3 calls, look for `streamed>0`.
2. Resilient on A13 after the stale-cblk fix — 3 calls in a row (meti.sh or OP9).
3. Direct capture on 30-33 — one call per version (emulator can run the daemon).
4. Android 17 on real hardware — benjamin's two pending calls; any A17 Pixel through onboarding.
5. App calls on A15 — one WhatsApp call on any A15 phone.
6. README's "A11: screen must stay unlocked during a call" has no source — one locked-screen call on A11.
7. Summaries on a 4 GB phone — check for an OOM kill.
8. LE Audio capture — an LE Audio headset.
