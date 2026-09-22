# 2026-09-20 — Hebrew pinned, English out (first seen on the OP12). Cause NOT found

Status: **✅ VERIFIED 2026-09-20 for the one call it was found on** — the maintainer opened the 12:25
"Feroza" call on the OP12 after the fix and confirmed it reads correctly ("ok it looks good"). That is one
clip. 2026-09-22: a real ~15-minute Hebrew call transcribed on the OP12 with this build "looks good"
(maintainer) — so the retry did no visible harm on a real call; whether it fired at all is unknown, the
log was lost. Still 🧪: whether ordinary Hebrew calls ever trip it, the fallback order (n = 1), and
Arabic / Russian / Chinese, which share the code and have never been tried.
Fix: `fix/wrong-script-retry`, unmerged. History: ❌ NOT WORKING 2026-09-20; cause narrowed to "nothing is
broken, the decode is low-margin".

## The report

Maintainer's OP12 (built-in mode, published 2.4.0, later the `feat/split-lines-at-speaker-change` build):
Settings ▸ Language = Hebrew, "Ask which language each time" off. The 12:25 test call, spoken in Hebrew
("בדיקה, בדיקה… וואן פלוס 12"), transcribes as `God, God, God, God, God is 1 plus 12.` — every run,
byte-identical.

## Established

- The pin IS applied: `Transcribing … lang=he` on every run. Do not re-check the settings plumbing.
- Same FILE on the desktop (`whisper-cli`, `ggml-large-v3-turbo-q8_0`, the app's flags, `-l he`, ffmpeg
  decode): Hebrew, with and without a prompt, Metal and CPU.
- The OP9's own (stereo, Shizuku) recording of the same call, on the OP9: Hebrew.
- The OP12 can produce Hebrew: another call there came out properly (maintainer's report).

## Disproved: "the Latin contact name turns the pin off"

The contact is saved as `Feroza` on the OP12 and `פרוזה` on the OP9, and the app primes whisper with the
contact's name. `fix/prompt-script-mismatch` stops sending a name whose script differs from the pinned
language. Installed on the OP12 at 15:10 with a new log line:

```
15:15:33 Prompt: 0 char(s), language he
15:15:34 Transcribing with 6 threads, lang=he, beam=1 ctx=-1 vad=on
15:15:45 Produced 2 segments
```

Output: unchanged, English. **No prompt, and still English.** The only desktop evidence for the theory was
that a Latin prompt corrupted the script of one word on Metal; it never produced English. The fix is
harmless and stays, but its commit message states a cause this run disproved.

## What is left, and the test that splits it

Same file: Hebrew through ffmpeg on the desktop, English through the app on the OP12. Either
- the app's decode/resample path for this **mono 48 kHz** file (every OP9 file is stereo), or
- something specific to the OP12: its SoC's ggml kernels, or its copy of the model file.

**Discriminator, not yet run:** transcribe the OP12's file on the OP9 (import, so no contact and no
prompt; same app build). Hebrew → OP12-specific. English → the app's mono decode path.

## Later on 2026-09-20 — it is NOT the OP12, and four more things are ruled out

The discriminator was run by the maintainer: the OP12's file imported on the OP9 ("Transcribe only", so no
contact and no prompt; log: `lang=he`, `Decoder output format: 48000 Hz, 1 ch`). Result: **the same
English.** So it is not the OP12's SoC or its copy of the model. It is this file through the app, on any
phone — while the same file through the desktop tool is Hebrew.

Ruled out on the desktop, each against the same file with `-l he` and the app's flags:

| suspect | how it was tested | result |
|---|---|---|
| the app's 48→16 kHz resampler | `AudioDecoder.pcm16ToMono16k` run on the JVM over the ffmpeg-decoded PCM, output fed to `whisper-cli` | Hebrew |
| flash attention (CLI default on) | `-nfa` | Hebrew |
| GPU vs CPU | `-ng -t 6` | Hebrew |
| decoder parameters | every `params.*` in `whispercv.cpp` compared with the CLI's defaults and flags | match |
| context parameters | both use `whisper_context_default_params()` | match |

Two things the phones share and the desktop does not:
1. **MediaCodec's Opus decode** of this mono 24 kbps file (the desktop test used ffmpeg's decode).
2. **whisper.cpp built for Android/ARM** — different ggml kernels for q8_0, so different numerics. The
   desktop result itself wobbles between backends on this clip ("1 + 12" / "1 פלוס 12" / "1 Plus 12"),
   which says the decode is low-margin here.

**Next discriminator:** a 16 kHz mono WAV of the same audio, made by ffmpeg, imported on a phone
(`/sdcard/Download/op12_test_wav.wav` is on the OP9). Hebrew → the Opus decode is the culprit. English →
it is whisper on ARM, and the next step is `whisper-cli` built with the NDK and run on the phone itself.

## Evening of 2026-09-20 — the WAV came out Hebrew, and the phone's decode is NOT broken

Maintainer ran the WAV discriminator on the OP9: **Hebrew.** So whisper on the phone is fine with this
audio, and the difference is upstream of it — which pointed at MediaCodec's Opus decode. Measured on the
OP9 with a throwaway instrumented test (`AudioDecoder.decodeToMono16k` on the file, compared sample by
sample with the ffmpeg decode run through the same resampler):

```
samples phone=195952 ref=196056          rms phone=-36.2 dB ref=-36.2 dB    peak 0.386 / 0.386
best lag=-104 samples (-6.5 ms), correlation=0.9880        per-second levels: equal to 0.0–0.2 dB
```

**The two decodes are the same audio.** The only structural difference is 104 samples = 312 @ 48 kHz =
Opus's standard pre-skip, which Android does not trim; the rest is ordinary decoder-to-decoder rounding.
On the desktop, shifting the good audio by 6.5 / 13 / 50 ms changes whisper's SEGMENTATION every time
(4, 2 and 1 segments) but never the language.

So nothing in the pipeline is broken. On this clip whisper sits on a knife-edge: on the desktop every
perturbation tried lands on Hebrew, on ARM the WAV lands on Hebrew and the Opus decode lands on English.
**The language pin is a strong hint to whisper, not a guarantee** — and a clip of "בדיקה, בדיקה… וואן פלוס
12" (a loanword-heavy test phrase, spoken twice) is about the easiest thing there is to tip over.

## Where a fix can come from

Not from the decode. From noticing the failure, which is cheap and unambiguous: **a language pinned to a
non-Latin script, and a transcript that is (nearly) all Latin letters.** `TranscriptionPrompt` already
knows each language's script. On that signal, decode again another way. Which way actually recovers Hebrew
ON A PHONE is being measured with `DecodeVariantBenchmark` on the OP9 (baseline / vad / beam / vad_beam
over this file) — results below when it finishes. Do not pick the retry from the desktop: the desktop has
never once reproduced the English.

## The on-phone retry measurement did not happen (2026-09-20, ~15:45)

`DecodeVariantBenchmark` was run on the OP9 under the isolated test app (installed by hand, because the
Gradle-run install has no read access to `/sdcard/Download` and is uninstalled before one can be granted;
`appops set … MANAGE_EXTERNAL_STORAGE allow` fixes that). The first variant was still decoding a 12 s clip
after ten minutes and the run was stopped. Native code is already `-O3` in debug (`CMakeLists.txt:32`), so
the slowness is unexplained — not investigated. The test app and the 874 MB model were removed afterwards.

**So which retry recovers Hebrew on a phone is still unknown.** The honest options for finding out:
the benchmark again with the cause of the slowness found, or building the script check with a retry
behind it and measuring THAT on a release build, on the 12:25 "Feroza" file, which both phones still have.

## The fix, and what it measured on the OP12 (2026-09-20 16:11, 🧪)

`WrongScriptRetry`: a non-Latin language pinned and a transcript under 20% in that script (≥ 12 letters) is
a failed decode. A recording of KNOWN length ≤ 3 min is decoded again through `FALLBACKS` — VAD off, then
beam 5, then both — and the first CLEAN result wins. "Clean" was added after the first device run: VAD-off
recovered Hebrew but wrote `זה 1 Behindração שת달ים` for "זה 1 פלוס 12", so a letter from a third
alphabet (neither the pin's nor Latin) now marks a candidate dirty; a dirty one is kept in hand and used
only if nothing clean turns up. Nothing recovers → the FIRST transcript is kept. Retries get no speaker
detector; a stop is still a stop. `LanguageScript` is the one table this and `TranscriptionPrompt` share.

Measured, the same "Feroza" file, release build, "Transcribe again":

```
16:11:10 lang=he beam=1 vad=on                         → English            (15 s)
16:11:25 Transcript is not in the pinned language's script (he); decoding again
16:11:26 lang=he beam=1 vad=off                        → Hebrew + a stray alphabet  (17 s)
16:11:44 lang=he beam=5 vad=on                         → clean Hebrew       (12 s)
```

On screen: `0:00 You — בדיקה, בדיקה, בדיקה זה 1 פלוס 12.` / `0:07 Feroza — בדיקה, בדיקה, בדיקה זה 1 פלוס 19.`

Costs and unknowns, plainly: this call took 45 s instead of 15 s. The fallback ORDER is still reasoned
rather than measured — one clip, and on it the second fallback was the one that worked, which argues for
trying beam first; not changed on a sample of one. The percentage shown during a run restarts with each
retry (see `progress-state-belongs-to-the-run`). Arabic, Russian and Chinese share the code path and have
never been tried. A long call is never retried by design.
