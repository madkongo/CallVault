# CallVault

CallVault is a modified fork of ShizuCallRecorder
(https://github.com/kitsumed/ShizuCallRecorder), Copyright (C) kitsumed (Med),
licensed under GNU GPL-3.0 with additional Section 7 terms (see LICENSE).

This is a MODIFIED version, distinct from the original. It is not endorsed by,
affiliated with, or supported by the original author. The names, trademarks, and
logos of the original project are the property of their respective owner and are
not used here beyond this attribution.

CallVault remains licensed under GPL-3.0. Source: this repository.

## Bundled third-party model weights

`app/src/main/assets/ggml-silero-v5.1.2.bin` is the Silero voice-activity-detection
model, converted to ggml format. It is used to trim non-speech audio before
transcription and is redistributed here under the MIT licence.

- Silero VAD — Copyright (c) 2024 Silero Team — MIT licence
  (https://github.com/snakers4/silero-vad)
- ggml conversion — https://huggingface.co/ggml-org/whisper-vad — MIT licence

MIT licence text:

> Permission is hereby granted, free of charge, to any person obtaining a copy of
> this software and associated documentation files (the "Software"), to deal in
> the Software without restriction, including without limitation the rights to
> use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
> the Software, and to permit persons to whom the Software is furnished to do so,
> subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
> FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
> COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
> IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
> CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Downloaded third-party model weights

These are **not** bundled: the app downloads them only when the user chooses them.

- **Persian-tuned speech model** (offered when transcription is pinned to Persian, GitHub #55) —
  "Whisper large-v3-turbo — Persian (Farsi)" by givia
  (https://huggingface.co/givia/whisper-large-v3-turbo-persian), licensed under
  CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). It is a fine-tune of
  openai/whisper-large-v3-turbo (MIT). Changes: quantised to q5_0 for whisper.cpp,
  as published at https://huggingface.co/pejmans21/whisper-large-v3-turbo-persian-ggml
  (file `ggml-large-v3-turbo-persian-q5_0.bin`, SHA-256
  d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8). CallVault does
  not modify the file. The credit is also shown in the app under the model's download rows.
