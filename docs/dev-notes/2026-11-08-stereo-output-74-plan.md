# Stereo output setting (#74) — build plan

## ⏸️ PAUSED 2026-11-08 — waiting on the maintainer (read this first)

**State:** all 6 phases built + one review-fix commit on `feat/stereo-output-74` (off `main` 2.5.0, unmerged,
unpushed). 2647/2647 unit tests, checkTranslations green. Independent review: no CRITICAL/HIGH; MEDIUM 2+3 fixed
(details-file probe now only with stereo on). OP9 has `2.5.1-stereo1` (versionCode 20501, release-signed c875ffd0)
with the setting **ON**. On-device so far (by Claude): row placement, ⓘ text, persistence across relaunch. 🧪 Nothing
heard on a real call yet.

**2026-11-08 19:16–19:17, maintainer's OP9 test (2.5.1-stereo2, Shizuku mode), measured by Claude from the files:**

| Call | Channels | ch0 | ch1 | L−R | L+R | Avg rate |
|---|---|---|---|---|---|---|
| phone (out) | 2 | −33.8 dB | −30.6 dB | −34.9 | −34.9 | 39 kbps |
| phone (out) | 2 | −34.0 dB | −33.8 dB | −36.9 | −36.9 | 36 kbps |
| WhatsApp | 2 | −27.6 dB | −20.9 dB | −26.1 | −26.1 | 49 kbps |

L−R equal to L+R = the channels are genuinely different (separate voices); identical channels would give a near-silent
L−R. Rates are above the old 24 kbps mono (Opus VBR averages under the 48 kbps target on pauses). 🧪 Stereo-ON is
confirmed by measurement; the maintainer's own listening verdict is not recorded yet. **Not done: the OFF check** — the
switch was still ON afterwards, so no mono call has been made with it off.

**Needed from the maintainer before this can continue:**
1. **Call test on the OP9** (setting is already on): one phone call + one WhatsApp call; on headphones you should
   hear yourself in one ear and them in the other. Then switch it off and confirm the next call is normal mono.
2. ~~**Decision — handoff re-arm guard**~~ **DECIDED 2026-11-08: taken out of this branch** (stereo-off stays identical to `main`); the proper fix is its own backlog item. Original question (commit `4fe485c9`): Mid-call, resilient recording sometimes rebuilds the
   capture; if the rebuild comes back in another shape (e.g. mono after a Bluetooth switch), `main` keeps going
   but reads it wrongly (rest of the call double-speed/garbled, recoverable by slowing down). The guard instead
   ends the recording cleanly there (rest of call lost). Applies with stereo OFF too.
   - **Recommended:** take the guard out of this branch (stereo-off stays identical to `main`) and do the proper
     fix — carry on across the rebuild in the right format — as its own branch/backlog item.
   - Alternative: keep the clean stop.
3. (Later) the GitHub reply to jlp89 is the maintainer's to write.

**Then:** apply the guard decision, re-run suite, rebuild the test APK, maintainer soaks, decide the release (2.6).


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
