# Mono vs Stereo as a user setting — investigation (2026-09-15)

Status: 📐 INVESTIGATION ONLY — no code written. Four read-only sweeps (capture side, every consumer of a
finished file, product surfaces + AIDashboard, external research), synthesised here. Nothing below is
measured on a device except where it cites the 2026-09-12 probe or the maintainer's own report.

The maintainer's framing: **Mono = smaller files, lower-quality transcription. Stereo = bigger files,
higher-quality transcription.** No second copy (sidecar) — the maintainer rejected that.

---

## 1. Plain answer

- **A setting is feasible.** Capture is already stereo on every CallVault-owned path; mono is produced by
  a downmix just before the encoder. Switching that downmix off per call is a small core change. The work
  is everything around it (below).
- **"Stereo = bigger files" is only true if the setting also raises the bit rate.** The bit rate everywhere
  is the total for all channels. Stereo at today's 24 kbps is the *same size* as mono and brings back the
  1.4.4 bug (far party starved). Stereo needs its own floor: Opus ≥ 48 kbps (≥ 32 absolute minimum —
  below that libopus narrows the stereo image and goes mono under ~19 kbps), AAC ≥ 64 kbps. So ~2× size.
- **"Stereo = better transcription" is not proven, and not automatic.**
  - CallVault's own transcription *averages the two channels back to mono* before whisper, so a stereo
    file transcribes identically unless per-channel transcription is also built.
  - AIDashboard *does* use stereo automatically (per-channel path with a mono fallback).
  - Evidence is mixed: industry practice records calls dual-channel; but a whisper user with perfectly
    separated call audio found per-channel *worse* (long silences → hallucinations, lost cross-talk
    context), AIDashboard's 2026-08-09 split produced Arabic garbage, and our own probe's isolated VoIP far
    channel transcribed worse than the mix. The gain, if any, is under double-talk — never measured.
  - What stereo reliably gives is **speaker separation**, not necessarily better words.
- **Stereo is not the same on every phone.** Qualcomm HALs document uplink-left / downlink-right (matches
  the OP12 probe, ~60 dB isolation). MediaTek (old HAL) mixes to mono; older Pixels give identical
  channels; some Qualcomm builds compile stereo out. A stereo setting must detect a non-separated capture.

## 2. Surprises the sweep found (true today, independent of the setting)

1. **Recordings are not all mono today.** The scrcpy path writes stereo at the user's total bit rate
   (`integrations/scrcpy/ScrcpyConfig.kt:95-96`, `ScrcpyAudioMuxer.kt:226`). That path is **all of Shizuku
   mode**, the standalone fallback when direct capture fails, and local-ADB. So Shizuku users already have
   the 1.4.4 shape: 24 kbps split over two channels (📐 inference; issue #25–28 triage notes a report).
   A "Mono" setting cannot be honoured there without re-encoding.
2. **Handoff path has no speaker detection when not downmixing** (`HandoffEncoder.kt:78`) — the probe bug
   the maintainer saw (no speaker labels on cell calls).
3. **Handoff re-arm does not check channel count/sample rate** (`HandoffReceiver.kt:340-440`): a re-armed
   capture that comes back mono into a stereo encoder would corrupt the rest of the file (📐 inference).
4. **Handoff and VoIP encoders have no bit-rate clamp or retry** (only direct uses `EncoderLimits`).
   Stereo AAC at low rates is where encoders refuse (#28c) → silent/empty recording risk.
5. **Chunked transcription sizes its PCM buffer from the whole file, not the chunk**
   (`AudioDecoder.kt:482-484`, `PcmShortSink.kt:81-84`). 📐 Mono passes a 256 MB heap at ~43 min; stereo
   at ~22 min. The "60 min is safe" claim was never tested. Fix before any stereo work.
6. **Waveform decodes the whole file** (`RecordingExtrasRepository.kt:64-91`). 📐 A stereo waveform runs
   out of heap from ~16 min; the call-end waveform draw has no length gate
   (`RecordingForegroundService.kt:735`, `VoipRecordingCoordinator.kt:457`).
7. **Stale claims:** `MetadataSidecar.kt:93` hardcodes `channelCount = 1` and records the preference bit rate,
   not the one used; `MergeFormat.kt:23` says every path encodes mono.
8. **AIDashboard's hourly compress sweep** re-encodes every call above 32 kbps to `-ac 2 libopus 24k` and
   trashes the Drive original (`AIDashboard/src/lib/calls/compress.ts:11-12,62-70`,
   `calls-repository.ts:211-231`). A stereo file at 48–64 kbps would be squeezed to starved 24 kbps stereo
   after its first transcription — destroying the benefit.

## 3. Where mono happens today (capture side)

| Path | Who uses it | Capture | Mono made at | Encoder ch | Bit-rate checks |
|---|---|---|---|---|---|
| Direct (`server/DirectAudioRecorderSession.kt`) | Standalone carrier calls when handoff is off | Stereo first, mono fallback (:449-462) | `PcmDownmix.stereoToMono` (:303-309) | `ENCODE_CHANNELS = 1` (:470) | `EncoderLimits` + retry at codec default (:115, :162-193) |
| Handoff / resilient (`AudioRecordingEngine.kt:535-563`, `handoff/HandoffEncoder.kt`) | Standalone carrier calls with resilient on (the OP12's path) | Stereo if VOICE_CALL (`preferredChannels`) | `downmixToMono = true` hard-coded (:545) | 1 if downmixing | **None** |
| VoIP (`server/VoipCaptureSession.kt`) | Standalone VoIP | Far = loopback mono, near = MIC mono, interleaved L=near/R=far | `PcmDownmix` (:291) | `ENCODE_CHANNELS = 1` (:498) | **None** |
| scrcpy (`integrations/scrcpy/*`, `server/RecorderSession.kt`) | Shizuku mode; standalone fallback; local ADB | scrcpy-server stereo | **Never** — writes stereo | 2 | scrcpy's own |

Things that read the stereo PCM before the downmix: `SpeakerTurnDetector` (turns → speaker labels),
VoIP far-party audibility check. Everything else (audit, mic-op heal, overrun ledger) is channel-agnostic.

Channel meaning: VoIP L=you/R=them by construction. Carrier: OEM-defined (`data/ChannelMap.kt:11-18`);
measured ch0 = you only on the OP12.

Why mono exists: commit `3d98663` / v1.4.4 (2026-07-24) — stereo Opus at 24 kbps split ~12 kbps per side
starved the far party (OnePlus CPH2653 field report); CHANGELOG 1.4.4 says "calls are mono content".
The cause was the bit rate, not stereo itself.

## 4. What reads a finished recording

| Consumer | Today | With a stereo file |
|---|---|---|
| Playback (`RecordingPlaybackController.kt`, framework `MediaPlayer`) | Plays as-is | **You left ear / them right** on headphones/earpiece; `MediaPlayer` cannot downmix. Needs Media3 ≥ 1.1.0 `ChannelMixingAudioProcessor` (matrix per input channel count, incl. 1→1) or own decode→AudioTrack. |
| Share (`RecordingShare.kt`), Drive, other players | Raw file | Everyone else hears the split too; nothing in-app can fix that. |
| Waveform (`RecordingExtrasRepository.kt`) | Whole-file decode, averaged | Same shape, ~1.6× memory → OOM earlier (§2.6) |
| Transcription decode (`AudioDecoder.kt:128-190`) | Averages channels | Works unchanged (tested) but no benefit; ~2× decode memory (§2.5) |
| Per-channel transcription | Does not exist | Needs: channel-select decode, 2 whisper passes per chunk (📐 ~1.2–2× time), merge by timestamps, pin language once, per-channel VAD, speed estimate keyed by channel count, mono fallback |
| Speaker labels (`SpeakerTurnsRepository`, `SpeakerLabeller`) | Live turns from the daemon | Turns become recoverable from the file after the call (bonus); handoff bug §2.2 must be fixed |
| Summaries, export | Text only | Unaffected |
| Merge (`MergeFormat.kt:54-60`) | Requires same channel count | A mono and a stereo part of the same redialled call are refused with raw technical text; candidates not filtered |
| Storage cap (`StorageCapPolicy.kt`) | Size-based eviction | 📐 1 GiB ≈ 99 h at 24 kbps vs ≈ 49 h at 48 kbps |
| Metadata sidecar | `channelCount = 1` | Wrong |
| Tests | Several pin mono (`MergeTestAudio` mono AAC only, `BcrMetadataTest`, speaker "no turns = mono") | Need stereo variants |

## 5. External findings (sources in the research report)

- **BCR** (chenxiaolong/BCR) offers stereo as an *audio source* option ("uplink+downlink, separate channels")
  and charges **bit rate per channel** (`KEY_BIT_RATE = param × channels`), so stereo doubles the file. Its
  README warns stereo is only known to work on Pixels; issue #943 shows single-stream `VOICE_CALL` stereo
  (what CallVault does) working on a Qualcomm Sony.
- **ShizuCallRecorder / Ever-Call-Recorder**: always stereo via scrcpy, 16 kbps Opus default — starved.
- **CallMonitor fork**: always-on stereo on the direct path only, Opus floor 48 kbps, no AAC floor, no
  handoff/VoIP, no playback fix.
- **LineageOS Dialer**: mono. **Skvalex, Cube ACR, NLL**: no documented stereo option (unverified).
- **Android encoders**: MediaCodec Opus = one coupled stereo stream, total bit rate; libopus narrows below
  32 kbps, mono below ~19. FDK AAC bandwidth depends on bit rate per channel.
- **whisper.cpp `--diarize`** does *not* transcribe channels separately — it transcribes the mix once and
  labels segments by channel energy (what our `SpeakerTurnDetector` already does).
- **Cloud ASR** (Google, AWS, Deepgram, AssemblyAI) all support per-channel transcription as standard for
  call audio — practice, not published numbers. No paper found measuring per-channel vs mixed WER on
  two-party telephone calls.

## 6. What a setting would have to touch (build list, not a plan)

1. `AppPreferences`: new key + default; choosing Stereo moves the bit rate to a stereo floor (like
   `chooseAudioCodec` does for codec changes).
2. Settings ▸ Audio: a row after bit rate; wizard `AudioStep` decision (contested default → arguably yes);
   11 locales.
3. Per-call plumbing: `AudioRecordingEngine.startPipeline` (+ handoff re-arm closure), `VoipRecordingCoordinator`;
   **AIDL change** — `startRecording`/`startVoipRecording` have no channel parameter (daemon/app version
   coupling; stale Shizuku services run old code).
4. Direct: `ENCODE_CHANNELS` → per-call value in `resolveBitRate`, `encoderNameFor`, `formatFor`,
   `bytesPerFrame` (PTS runs 2× fast if missed), `supports()`, stereo-aware retry rate.
5. Handoff: `downmixToMono` from the setting; speaker detector keyed on capture channels; `EncoderLimits`
   + retry; re-arm channel/rate guard.
6. VoIP: encoder channels, feed the stereo buffer, `totalFrames` divisor, `EncoderLimits`, `supports()`.
7. Mono-capture routes (BT/speaker/non-Qualcomm): define "Stereo when the phone separates the sides",
   detect separation (e.g. AIDashboard's sum−diff < 6 dB test) and fall back or warn.
8. Shizuku/scrcpy: always stereo — grey the setting ("Shizuku always records stereo") and apply the stereo
   bit-rate floor there regardless (fixes §2.1).
9. Playback downmix (Media3 migration of a 193-line controller).
10. Fix first: chunk-sized decode buffer (§2.5), waveform decode memory + call-end length gate (§2.6).
11. Merge: filter candidates by format, readable refusal.
12. Metadata sidecar channel count; debug-report header gains the channel setting.
13. Per-channel transcription in CallVault (optional second phase) — without it, Stereo only helps AIDashboard
    and speaker labels.
14. AIDashboard: compress sweep must skip or preserve separated stereo (§2.8).
15. Docs/claims: README :46, :71, mode table; CHANGELOG entry explaining the reversal of 1.4.4's "calls are
    mono content"; ReleaseHighlights; readme-impact-log; backlog heuristic "mono output proves the direct
    path" (`backlog.md:1385`); memory `direct-audiorecord-capture-idea` headline.

## 7. Rules on record and whether they forbid this

- `direct-audiorecord-capture-idea` "ENCODE MONO — do NOT restore stereo": its evidence condemns *starved*
  low-bit-rate stereo as the default. Default Mono + opt-in Stereo with a bit-rate floor does not contradict
  the evidence, but reverses the headline rule — the maintainer's explicit decision.
- `per-channel-transcription-separation`: lists "stereo main file at a higher bit rate" as an option; notes
  the ear split argues for a sidecar (now rejected by the maintainer).
- `callmonitor-fork-assessment` "do NOT adopt its stereo encode": about adopting that always-on patch, not a
  user choice.
- `no-second-voice-capture-during-call`: not affected (stereo uses the same single capture).
- `new-features-consider-onboarding`: requires an explicit wizard decision.

## 8. Open questions for the maintainer

1. Stereo floor: Opus 48 kbps / AAC 64 kbps (≈ 2× files)? Or let the user pick any rate ≥ 32?
2. Shizuku mode: grey the row as "always stereo" and apply the floor there too (fixes today's starvation)?
3. Onboarding wizard as well as Settings?
4. Playback: migrate to Media3 so in-app playback sounds normal in both ears? (Shared/Drive files stay split.)
5. Phones that don't separate sides: silently record mono, or record stereo and warn?
6. CallVault's own per-channel transcription: build it with the setting, or later? Without it the setting's
   "better transcription" only applies in AIDashboard.
7. AIDashboard's compress sweep: skip separated stereo files?
8. Before any UI says "higher-quality transcription": run the A/B on a real call with double-talk (a probe
   recording already on the OP12 would do) — a stereo recording transcribed per channel vs its mono mix.
