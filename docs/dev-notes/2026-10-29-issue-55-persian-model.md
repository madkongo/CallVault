# Issue #55 — Persian-tuned speech model

🧪 VERIFYING — built, unit tests pass, nothing run on a phone. To settle it: on a phone, pin the
transcription language to Persian, pick **Persian-tuned**, download it, and transcribe one recording.

## Decision

Maintainer's call (2026-10-29): make a Persian-tuned model **selectable and downloadable**, done
properly. **Not quality-tested on calls** — that was explicitly not wanted.

## Source and licence

- Model: `givia/whisper-large-v3-turbo-persian` — CC BY 4.0, fine-tune of `openai/whisper-large-v3-turbo`
  (MIT). Same architecture as our turbo tiers.
- File used: the q5_0 ggml re-upload (`whisper-quantize` of givia's own ggml export at revision
  `9626d38558f04c02f480b702c9db740eea567911`, nothing else changed):
  - https://huggingface.co/pejmans21/whisper-large-v3-turbo-persian-ggml/resolve/28739ad1094c007d67fc7e2276b1e9b709d58e2d/ggml-large-v3-turbo-persian-q5_0.bin
  - SHA-256 `d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8`, 574,041,195 bytes
    (from the HF API's LFS oid and the resolve endpoint's `x-linked-etag`/`x-linked-size`; the file
    was not downloaded here).
  - Chosen because its model card credits givia and states CC BY 4.0. The byte-identical file is at
    `hasanghaffari93/whisper-large-v3-turbo-persian-ggml-q5_0` (as `ggml-persian-v3-turbo-q5_0.bin`,
    commit `d1ee8515dc87215becad31f80c21a1cca6430906`) — a fallback if the first repo disappears.
- The URL is **pinned to the commit**, unlike the ggerganov tiers (resolve/main).

## What was built

- `TranscriptionModel.LARGE_V3_TURBO_PERSIAN_Q5_0` (id `large-v3-turbo-persian-q5_0`), with a new
  `language = "fa"` field; general tiers have `language = null`. realTimeFactor 2.16, same as turbo q5_0.
- `TranscriptionModelOffer.offered(language, selectedId, installedIds)` — the pure visibility rule.
  Used by the Settings model dropdown (classic and per-page screens) and the wizard's models step
  (dropdown and the ⓘ size note).
- Credit line under the model's download rows (always drawn, not folded into ⓘ), and NOTICE.md.
- Strings `transcription_model_persian`, `transcription_model_persian_licence` in all 15 locales.

## Visibility and language changes

- Listed when the language is pinned to Persian (`fa`).
- **Changing the language away does not switch the model.** If it is selected, it stays selected and
  stays listed, so the dropdown never names a different model from the one that runs, and nobody is
  silently re-pointed at a tier they have not downloaded. The user switches back when they choose.
- It also stays listed while its file is on the phone, because the delete row only offers the
  selected tier — hiding it would strand 574 MB.
- Auto-detect or any other language with nothing selected or installed: not listed.

## Known risks

- One outside tester (the pejmans21 model card) found it **~3x slower and less accurate with
  temperature fallback on**; they ran it with `-nf`. CallVault keeps fallback on (`entropy_thold`
  2.8 in `app/src/main/cpp/whispercv.cpp`). Not changed for this tier — not measured here.
- Same size to the byte as stock turbo q5_0, so import cannot tell them apart by size. The digest
  check still refuses the wrong one, but the error says "wrong file" rather than naming the other model.
- English quality is a little worse than stock turbo (card: FLEURS WER 6.4 vs 4.8) — irrelevant while
  it is only offered for Persian, relevant if someone keeps it selected after switching language.
