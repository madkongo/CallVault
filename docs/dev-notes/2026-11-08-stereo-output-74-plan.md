# Stereo output setting (#74) — build plan

Status: 🧪 PLAN — branch `feat/stereo-output-74` off `main` (2.5.0). Nothing merged. Target: a later 2.6
minor, NOT the 2.5.1 patch line.

Implements the 2026-09-15 decision (Mono/Stereo as a user setting) and issue #74 (jlp89). Full background:
`2026-09-15-mono-vs-stereo-setting-investigation.md` and its 2026-11-08 triage section.

## Scope decisions (made by us, not re-asked — stated so they can be redirected)

- **Floor policy: `max(perceptual floor, 2× the user's mono bit rate)`.** Perceptual floor = Opus 48 kbps,
  AAC 64 kbps. So the default 24 kbps Opus user lands at 48 (what #74 describes); a 16 kbps user is lifted
  to 48 (not 32, where libopus narrows the image); a 64 kbps user keeps intent at 128. `EncoderLimits`
  still clamps down to the device encoder's hardware max afterwards (rare, acceptable).
- **Off by default.** The downmix (today's behaviour) is unchanged for everyone who leaves it off.
- **No onboarding wizard step.** Off-by-default power feature for people transcribing per-channel off-device.
  Settings-only keeps onboarding lean. (Verify this does not trip SettingDecisionsTest/ModeAwareUiTest.)
- ~~Shizuku/scrcpy: grey the row~~ **CORRECTED 2026-11-08 during Phase 3: no greying.** The Sept note's
  "Shizuku = scrcpy, always stereo" is out of date: since ShellAttribution (2026-10-19) both hosts run the
  same `RecorderServiceImpl` and record through `DirectAudioRecorderSession` (`ModeCapability`: "every
  capture-side capability now works in both modes"). So the setting works in both modes through the one
  AIDL method. scrcpy is only the fallback; when stereo is on, the fallback now gets the stereo floor too.
  With stereo OFF the scrcpy fallback is unchanged (still stereo at the mono rate, §2.1) — backlog it.
- **Playback: warn-only.** The setting's description says files play with you in one ear / them in the
  other on earpiece/headphones, and are ~2× larger. Media3 downmix migration deferred.
- **No in-app per-channel transcription.** CallVault's decode keeps averaging to mono (works unchanged).
  The setting helps external ASR and makes speaker labels recoverable from the file.

## The four traps a drop-in PR would miss (why we build it)

1. `EncoderLimits.resolveBitRate` clamps only to the encoder's hardware range, not a perceptual floor —
   "just double" under-floors a low setting. → floor logic above.
2. Handoff + VoIP encoders have **no** `EncoderLimits` clamp/retry today (only Direct does). Stereo AAC at
   low rates is the #28 silent-empty-file shape. → add the clamp to both.
3. Handoff **re-arm** does not check channel count (`HandoffReceiver`): a mono re-arm into a stereo encoder
   corrupts the rest of the file. → guard.
4. AIDL transaction ids are **positional** (`RecorderTransactionCodesTest`); a warm daemon from a previous
   APK may answer. → append one method, tolerate-absent → mono.

## Where the downmix runs (decides AIDL surface)

| Path | Downmix side | AIDL change? |
|---|---|---|
| Handoff / resilient (`AudioRecordingEngine` + `HandoffEncoder`) | **app-side** (`downmixToMono`) | none — flip the app flag |
| Direct (`DirectAudioRecorderSession`, in daemon) | daemon-side (`ENCODE_CHANNELS=1`) | needs daemon to know stereo |
| VoIP (`VoipCaptureSession`, in daemon) | daemon-side (`ENCODE_CHANNELS=1`) | needs daemon to know stereo |

AIDL: append `void setEncodeStereo(boolean stereo)` at the END. App calls it before every
`startRecording`/`startVoipRecording`, wrapped so an older daemon that lacks it (`DeadObjectException`/
no-op) leaves the daemon at its mono default. Daemon stores it; Direct + VoIP read it for encode channels.

## Phases (TDD, one concern per commit)

1. **Floor logic (pure, no device).** `StereoBitRate.floorFor(codec, monoRate)` = `max(codecFloor, 2×)`;
   constants Opus 48000 / AAC 64000. Unit tests: default 24→48, 16→48, 64→128, AAC 32→64, 128→256.
2. **Preference.** `AppPreferences` key `record_stereo` (default false) + getter/setter; the bit rate passed
   to the daemon/encoders is floored through phase 1 when the pref is on. Tests: additive key diff,
   floored-rate resolution, off = unchanged.
3. **AIDL + daemon (Direct + VoIP).** Append `setEncodeStereo`; `DirectAudioRecorderSession` and
   `VoipCaptureSession` take a per-session channel count (2 when stereo), feed the stereo buffer, fix
   `bytesPerFrame`/`totalFrames` divisors, run `EncoderLimits` at the stereo channel count, keep the
   speaker detector keyed on *captured* channels. Update `RecorderTransactionCodesTest`. App calls the
   setter (tolerant). Tests: transaction-code pin, divisor math, EncoderLimits at ch=2.
4. **Handoff path.** `downmixToMono` from the pref; `HandoffEncoder` encodes 2 channels at the floored
   rate with `EncoderLimits`; re-arm channel-count guard; speaker detector keyed on captured channels.
   Tests: re-arm mono-into-stereo refusal, encoder floor.
5. **Settings UI + 15 locales.** Row after the bit-rate picker; greyed in Shizuku mode; description warns
   (~2× size, ear split). No wizard step.
6. **Metadata + docs.** `MetadataSidecar` real channel count; debug-report header line; README/CHANGELOG
   entry noting the 1.4.4 "calls are mono content" reversal is now opt-in; update the
   `direct-audiorecord-capture-idea` memory headline; backlog the scrcpy floor fix.

Fix-first blockers from the Sept note (§2.5 chunked decode OOM, §2.6 waveform OOM) are **already resolved**
by the #63 long-call work (2026-10-28) — no longer gating.

Rollback: `main` is untouched; each phase is its own commit; delete the branch to abandon.
