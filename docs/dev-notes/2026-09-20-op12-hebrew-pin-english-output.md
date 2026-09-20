# 2026-09-20 — OP12: Hebrew pinned, English out. Cause NOT found

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
