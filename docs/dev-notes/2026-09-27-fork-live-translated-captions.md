# Fork review: live translated captions (prayagkhandelwal10-hub/CallVault)

**Status:** 📐 reviewed 2026-09-27 from the diff only — not built, not run. **PARKED by the maintainer.**
No PR or issue was opened to us.

## The fork

https://github.com/prayagkhandelwal10-hub/CallVault — forked 2026-09-26 from our `main` at `d136d976`.
Three "Add files via upload" commits in ~35 minutes; 3 ahead, 2 behind. 14 files, ~660 lines added.

## What it adds

Live translated captions of the **far party**, during **app calls only**, off by default.

1. **Daemon:** `IRecorderService.registerLiveCaptionListener` / `unregisterLiveCaptionListener`, appended
   last (transaction-code test updated); a new `oneway ILiveCaptionListener.onFarPartyAudio(pcm, slotNanos)`.
   `VoipCaptureSession` gets a `liveCaptionSink` that receives every far-party 20 ms slot from the capture
   loop — the same bytes that go into the file.
2. **App (`livecaption/`):** `LiveCaptionCoordinator` buffers 3 s windows, skips windows under ~100 ms of
   signal, wraps them as WAV (`WavEncoder`) and sends them to `SpeechTranslationClient`, which POSTs to
   **Groq's cloud Whisper** (`api.groq.com/openai/v1/audio/translations`, `whisper-large-v3`, output
   English) with the user's own free API key. `CaptionOverlay` shows the text in a
   `TYPE_APPLICATION_OVERLAY` window (new `SYSTEM_ALERT_WINDOW` permission), clearing after 6 s.
   Started/stopped from `VoipRecordingCoordinator` with the recording.
3. **Settings:** toggle, API-key field, overlay-permission prompt; English strings only.

## Assessment

**Good:** additive and wrapped in `runCatching` throughout — a failure costs captions, not the recording;
AIDL rules followed; the idea fills a real gap (our transcription runs only after the call).

**Concerns:**
- **Privacy / consent:** the other party's voice is streamed to a third-party cloud during the call.
  CallVault is on-device by design.
- **Scope:** app calls only (not carrier), far party only, translation into English only, 3–6 s behind.
- **Loose ends:** API key stored in plain preferences; strings not in our 11 locales; no new tests
  beyond the transaction codes; no evidence it was built or run.
- **Unchecked:** the cost of a `oneway` binder call every 20 ms from the capture loop on a slow phone.

## If picked up later

An on-device version (the Whisper engine we already ship, fed live windows) would keep the audio on the
phone; feasibility on phone CPUs in real time is unmeasured.
