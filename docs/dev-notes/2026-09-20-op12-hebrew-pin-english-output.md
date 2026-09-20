# 2026-09-20 — Hebrew pinned, English out (first seen on the OP12). Cause NOT found

Status: **❌ NOT WORKING 2026-09-20** — open. One explanation was proposed, built, installed and
**disproved the same day**; it is recorded here so it is not proposed again.

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
