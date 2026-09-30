# Samsung Wi-Fi Calling (VoWiFi) records a full-length but SILENT file — not fixable without root

**Status: 📐 RESEARCH ONLY, 2026-09-30. Nothing built.** Verdict from three parallel research passes
(GitHub → vendor/AOSP docs → web), all converging at **HIGH confidence**. Issue #45 (Benjamin-67).
Confidence markers: **M** measured on a device · **C** read from code · **D** docs/AOSP/vendor · **G** inference.

## The report

benjamin (Samsung Galaxy Z Fold, **SM-F976B "q8q"**, One UI 9.0 / **Android 17**, Germany; STANDALONE,
Resilient on, AAC). A **38-minute Wi-Fi call** (2026-09-28) was detected correctly as a carrier call and
**handoff captured the full 2316 s with no drops — but both sides are inaudible** (M: full-length file;
the silence is his report, no level lines exist for the handoff path). With **Wi-Fi Calling turned OFF**
(the call falls back to VoLTE/cellular), recording works perfectly, both sides audible (M, his report).

So this is not a cut-off and not a transport problem: the tap delivers frames, but they are silent.

## Verdict

**On this Samsung, VoWiFi call audio never reaches the telephony tap we record from
(`AUDIO_SOURCE_VOICE_CALL` / `AUDIO_DEVICE_IN_TELEPHONY_RX`), and no non-root Android capture source can
recover it. This is a Samsung modem/audio-HAL/IMS limitation, not a CallVault bug, and every other
third-party recorder hits the same wall.** (D+G, HIGH.)

VoLTE records because it is **modem/DSP-terminated** — the ADSP vocodes and mixes downlink onto the
telephony RX device, which `VOICE_CALL` taps (D, HIGH; Qualcomm CVD notes). VoWiFi transport is an
IKEv2/IPsec tunnel to the carrier ePDG over Wi-Fi, and Samsung's IMS stack handles that media on a
**different path** (host/communication-usage stream), which `VOICE_CALL` does not see. The proof it is a
different path: Samsung's **own** recorder could not record VoWiFi for years (OS pop-up "cannot record
Wi-Fi calls") and had to **add** it in **One UI 6.1 (Feb 2024, dialer 15.1.66+, S24-class first)** via a
proprietary in-dialer hook. If VoWiFi were on the same telephony RX device as VoLTE, no special fix would
have been needed. (D, HIGH that VoWiFi ≠ VoLTE path on Samsung; MEDIUM on the exact host-vs-modem detail.)

Corroborating hint from benjamin's own log: at hang-up Samsung briefly flipped to
`MODE_IN_COMMUNICATION` (the VoIP/communication mode), not just `MODE_IN_CALL` — consistent with VoWiFi
living on the communication path rather than the pure telephony path. (Don't over-read a teardown flip;
MEDIUM.)

## Why no other capture route saves it (the doors we checked)

- **`AUDIO_SOURCE_VOICE_COMMUNICATION`** = a mic capture source (near-end + AEC/NS), NOT a downlink tap.
  Gives your own voice, never the far party. (D, HIGH.)
- **`REMOTE_SUBMIX` / AudioPlaybackCapture with `CAPTURE_VOICE_COMMUNICATION_OUTPUT`** (the very path we
  use to record app/VoIP calls like WhatsApp on A14+): only captures a rendering app's stream when that
  app allows capture. A **system/IMS-rendered** `USAGE_VOICE_COMMUNICATION` stream is privacy-sensitive
  and opts out of capture for non-platform apps, so even if VoWiFi is rendered this way, a third-party
  app cannot capture it. Our shell/WRITE_SECURE_SETTINGS privilege is **not** the platform-signature /
  `CAPTURE_AUDIO_OUTPUT`-on-VOICE_COMMUNICATION privilege Samsung's own dialer uses. (D, MEDIUM-HIGH.)
- **Root doesn't reliably help either.** On a Galaxy A155F, root + a Samsung-driver custom kernel still
  produced loud static, not audio (BCR #668). (D, HIGH.)

## Everyone else lands in the same place

- **BCR** (chenxiaolong — the authoritative non-root `VOICE_CALL` implementer): #173/#176/#461/#642/#668/
  #709. "This is a ROM/firmware issue and cannot be fixed or worked around from BCR." On **Pixel**,
  Wi-Fi calling records fine (treated like a normal call) — so the failure is **Samsung-firmware-specific**,
  not an Android rule. #709: `VOICE_CALL` is "Android's official (and only) call recording API"; the
  `PROPERTY_WIFI` call flag is detectable but there is no audio-stream-change callback.
- **ShizuCallRecorder** (the lineage this app forked from) + scrcpy recorders: identical `VOICE_CALL`
  path, inherit the same silence.
- **Cube ACR** FAQ and **Skvalex** help both document VoWiFi as an unfixable device limitation and tell
  users to **disable Wi-Fi calling**. (Truecaller is the one app community anecdotes say catches Samsung
  Wi-Fi calls, via its own floating/VoIP path — undocumented, unverified.)
- **GrapheneOS os-issue-tracker #6099**: VoLTE↔VoWiFi handover creates a *new* audio stream recorders
  don't re-attach to; MIUI/HyperOS re-attach via privileged dialer logic — again vendor-privileged.

## Confirmed counter-example on our own hardware — VoWiFi DOES record on OnePlus/Qualcomm

**✅ 2026-09-30, maintainer-confirmed on the OP9 (OnePlus 9 Pro, Snapdragon, OxygenOS).** A live Wi-Fi
call was watched over adb: telecom showed `Call id=TC@5, state=ACTIVE, prop=[ HD wifi], voip=false` (the
`wifi` = `Call.Details.PROPERTY_WIFI`, so a genuine VoWiFi call, treated as a carrier call). CallVault
captured it — `CV:AudioRecordingEngine: Published … OP9Recordings/…_in_….ogg (33217 bytes)`, decoded to
`470736 frames @ 48000 Hz, 1 ch (9807 ms)`, ~27 kbps — and the maintainer listened: **it recorded
properly.** (M.) This is the direct proof of the Pixel/Qualcomm branch: on modem-terminated VoWiFi the
audio lands on the telephony tap and records like any call. Same Android feature, opposite outcome from the
Samsung Fold — because it is different silicon/firmware. (Unrelated aside: the OP9's earlier "line busy"
was a new router breaking the VoWiFi/IMS tunnel, not a recording matter.)

## The one evidence gap

No primary report on the exact **SM-F976B** ("both sides silent" VoWiFi). The verdict is by strong
analogy to S20FE/S21FE/A155F/S23-class cases, not a direct match. "Both sides silent" (vs the more common
"only my side") means this firmware doesn't even route the near-side TX into the tap during VoWiFi.

## What we can actually do (options — nothing decided)

1. **Detect + warn (recommended).** Detect the Wi-Fi/IMS call state — `Call.Details.PROPERTY_WIFI`
   (`hasProperty`), or IMS-over-IWLAN — and instead of silently saving a dead file, surface an honest
   "Wi-Fi Calling can't be recorded on this phone — set Phone → Settings → Calling preference to *Mobile
   network preferred* (or turn Wi-Fi Calling off) to record." Avoids shipping a silent recording and
   tells the user the exact fix everyone converges on. Needs a device with a call to confirm the property
   fires (we have no VoWiFi-capable test phone).
2. **Document it** in `docs/SUPPORT.md` under Device-specific limitations (alongside vivo). Draft ready.
3. **Accept VoWiFi as unsupported** for capture — which is what Samsung itself did before One UI 6.1.

The far side is **not** recoverable without root (and root gives static on some Samsungs). We cannot make
VoWiFi record; we can stop pretending it did.

## Sources
BCR #173/#176/#461/#642/#668/#709 (github.com/chenxiaolong/BCR/issues); Gizmochina 2024-02-12 &
ProPakistani 2024-02-13 (One UI 6.1 VoWiFi recording); Samsung Members "No call recording feature for
VoWiFi calls" (td-p/7382669) & "Samsung smartphones can now record VoWiFi calls" (td-p/15525191);
cubeacr.app/faq.html; callrecorder.skvalex.com/help; Qualcomm CVD (ShapeShifter499 PR #9);
insinuator.net VoWiFi security; AOSP AudioManager mode semantics; GrapheneOS os-issue-tracker #6099.
