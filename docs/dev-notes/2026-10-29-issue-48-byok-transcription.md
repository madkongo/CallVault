# Issue #48 — transcribe with your own key (cloud transcription, v1)

🧪 VERIFYING — built, JVM unit tests pass, **nothing run on a phone and no real request sent to any
service**. To settle it: on a phone, pick Groq in Settings → Where to transcribe, paste a Groq key, press
Test (expect "The key works."), then transcribe a two-sided call of a few minutes and a call over 5 minutes
(two passes) and check: words, timestamps (tap a line → playback seeks there), speaker labels, and the
engine shown in an export (`cloud:groq/whisper-large-v3-turbo`).

## Decisions (maintainer, before the build)

- Presets we chose; the user only pastes a key. Groq is the recommended default preset; Custom (advanced)
  for any OpenAI-compatible server.
- Approved settings design "A": a "Where to transcribe" radio list; the chosen cloud row opens to key /
  Test / "Get a … key"; a lock-icon note says where the audio goes. **No prices, costs or "free" anywhere.**
- Consent dialog the first time each cloud service is chosen; Cancel keeps the previous choice.
- Wi-Fi only by default; "Also use mobile data" switch (off).
- Not in the setup wizard (it needs a key). Recorded in `SettingDecisionsTest`.
- Summaries stay on the phone in v1.

## Providers

| Preset | Base URL | Model | Source checked 2026-10-29 |
|---|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `whisper-large-v3-turbo` | https://console.groq.com/docs/speech-to-text — `verbose_json` returns `segments` with `start`/`end` in seconds; flac/mp3/mp4/mpeg/mpga/m4a/ogg/wav/webm; 25 MB upload on the free tier; `language` is ISO-639-1. |
| OpenRouter | `https://openrouter.ai/api/v1` | `microsoft/mai-transcribe-2` | https://openrouter.ai/docs/guides/overview/multimodal/stt.md — accepts OpenAI-style multipart (25 MB cap); `verbose_json` gives `segments[{start,end,text,speaker?}]`; its own verbose example uses `microsoft/mai-transcribe-2`; it names `openai/gpt-4o-transcribe` and `microsoft/mai-transcribe-1.5` as models that **reject** `verbose_json` (400). It also says upstream providers time out after 60 s per request. |
| Custom | user's https URL | user's model | Must be OpenAI-compatible and return `verbose_json` segments. |

**OpenRouter is in v1** because its docs show `segments` coming back for MAI-Transcribe 2. What is NOT
confirmed: that this holds for a **multipart** request (the docs' verbose example is base64 JSON), and that
the `language` hint is honoured for every language. If OpenRouter's answer lacks timings, the run fails
with "answered without timings" (never stores untimed text) and the Test button says so.

## How it works

- `transcription/cloud/`: `TranscriptionService` + `CloudEndpoint` (presets, Custom validation — https
  only, no user-info/query/fragment), `WavEncoder` (16 kHz mono PCM-16), `MultipartForm` (RFC 7578, refuses
  CR/LF/quote in fields), `VerboseJsonParser`, `CloudError` (kind + whether retryable),
  `CloudTranscriptionClient` (+ `UrlConnectionPoster`, HttpURLConnection, redirects off), `CloudTranscriber`,
  `CloudKeyStore`.
- **Audio is still decoded on the phone**, one `ChunkPlan` pass at a time (`ChunkDecode`, now shared with
  the on-device engine). That decode feeds `OfflineSpeakerLabeller` exactly as before, so speaker labels
  work the same; each pass is uploaded as WAV (📐 ~9.6 MB per 5 min, max ~11.5 MB per 6-min pass — under
  25 MB). Segments are offset with `ChunkPlan.stitch` against where the decode really started.
- Fields sent: `model`, `response_format=verbose_json`, `timestamp_granularities[]=segment`,
  `temperature=0`, and `language` when one is pinned and it is a 2-letter code. **The contact-name prompt
  is not sent** (more than the service needs to hear).
- Skipped for cloud (by engine id prefix `cloud:` in `TranscriptionRunner`): VAD, SpeechGapSnap (both live
  in the on-device engine only), WrongScriptRetry, and speed calibration.
- The transcript row's `modelId` holds the engine, e.g. `cloud:groq/whisper-large-v3-turbo`.
- Worker: a cloud service never waits for a local model (`TranscriptionRouting.route`). No key / incomplete
  Custom: a tapped run fails that recording with "…is not set up: add your key in Settings…"; the automatic
  sweep does nothing (it would otherwise fail every waiting call).
- Errors: 401/403 → "refused the key", final. 413 → final. 429/5xx/IO → the recording is left QUEUED with
  the reason, the batch stops, the worker returns `Result.retry()` (WorkManager back-off), up to **5
  attempts** (`TranscriptionRouting.MAX_CLOUD_ATTEMPTS`), then FAILED. Other 4xx → "check the model name".
  Server bodies are never shown or logged.
- Constraints: cloud runs need `UNMETERED`, or `CONNECTED` with mobile data allowed — on the nightly sweep
  **and** on a tapped run. Charging still follows the existing setting (the local decode is real CPU).
- **Known v1 limit:** a run already waiting for Wi-Fi keeps waiting after "Also use mobile data" is turned
  on — the switch changes the constraint of the nightly sweep and of runs started after it, not of work
  WorkManager already holds (and a tap queued behind it waits too). Re-enqueueing waiting runs safely needs
  each one's per-recording language, so it was left out of v1.
- Home / share-import: "No speech model yet" is not shown for cloud; the confirm dialog quotes 📐
  3 s + 0.1 × call length (a guess with headroom, not measured) and never the first-run wording.
- Length limit stays at 60 min for cloud too (unchanged; relaxing it is trivial later, but the local
  decode is the same, and a longer cap is a separate decision).

## The key

- Android Keystore AES-256-GCM (`KeystoreSecretCipher`), sealed value in its **own** prefs file
  `callvault_cloud_secrets`, one entry per service. No new dependency (androidx.security not used).
- Never in the settings export (that walks an allow-list of the main prefs file, and none of the five new
  settings are in it — tested), never logged (only exception class names are), the app has backups off.
  `LogRedaction` now also replaces `gsk_…`, `sk-…`, `sk-or-v1-…` and `Bearer …` with `[KEY_REDACTED]` as a
  net under that rule — tested.
- The key field is a password field (show/hide toggle, no suggestions) and saves 400 ms after typing stops,
  off the main thread.

## Settings

- `TranscriptionServiceRows` in both the classic Transcription section and the new screen's "Model and
  language" group. When a cloud service is chosen, the model picker and download rows are hidden (they only
  matter on the phone; switching back shows them again).
- Device-only keys: `transcription_service`, `transcription_cloud_mobile_data`,
  `transcription_cloud_consent`, `transcription_custom_base_url`, `transcription_custom_model`.
- Strings in all 15 locales.

## Verified vs not

- ✅ unit-tested only: request shape, multipart body, WAV header, verbose_json parsing (seconds → ms, empty
  and untimed segments, words-without-timings refused, silence allowed), per-chunk offsets, HTTP status
  mapping, key sealed/not exported/redacted, network constraint choice, worker routing, runner retry/fail.
- 🧪 not run: any real request to Groq/OpenRouter; the Keystore cipher (Robolectric has no AndroidKeyStore;
  tests use a fake cipher); the settings UI on a screen; speaker labels on a cloud transcript; a long call.
- 📐 calculated: upload sizes; the quoted estimate.

## Phone test plan (needs a Groq key)

1. Settings → Where to transcribe → Groq → consent dialog → "Use Groq". Paste key → Test → "The key works."
   Try a wrong key → "The key was refused…".
2. On Wi-Fi, transcribe a 2–3 min two-sided call. Expect seconds, timestamps that seek, speaker labels.
3. Transcribe a call over 5 minutes (two passes); check lines around the 5:00 seam are neither lost nor
   doubled.
4. Wi-Fi off, mobile data on, switch off: tap Transcribe → it waits (spinner). Connect Wi-Fi → it runs.
   Then turn on "Also use mobile data", Wi-Fi off, tap Transcribe on another call → it runs on mobile data.
5. Settings → export settings → open the file: no key, no where-to-transcribe settings.
6. Settings → generate report → search the log for `gsk_` → nothing.
7. Switch back to On this phone → model rows return; a tap transcribes on the phone as before.
