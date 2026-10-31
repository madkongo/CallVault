# Rollback: decode time limit

🧪 VERIFYING — built on `fix/decode-time-limit` 2026-10-31; JVM tests only. Not yet seen on a phone, and no
real stalled file has been fed to it.

## What changed

- `transcription/DecodeStall.kt` (new): a pure rule. If the decoder gives back no output for 120 s, the decode
  is a stall. Output resets the clock; end-of-stream is a normal finish. It never looks at total time, so a
  long call (decoded in 5-minute windows) or a slow phone is never failed by it.
- `transcription/AudioDecoder.kt`: the `decodePcm` loop asks that rule once per turn and throws
  `DecodeStalledException` (an `IllegalStateException`) on a stall, after a `CV:AudioDecoder` warning
  "Decoder stalled: no output for N s …". Codec and extractor are released by the existing `finally` blocks.
- Callers unchanged; each already turns an exception into its normal failure: transcription → FAILED row and
  the failure notice; waveform → stays undrawn, nothing cached; import probe → "could not be decoded".
- No database, preference or file-format change.

## How to roll back

```
git revert -m 1 2f142765
```

(`2f142765` = the merge of `fix/decode-time-limit` into integration/next or main.) Nothing to migrate.

## What users notice after a rollback

A file the decoder chokes on goes back to hanging: transcription shows "working" until Stop is tapped, the
waveform never appears, an import of such a file sits on the probe. Normal files behave the same either way.

## When to roll back

- Transcriptions of long or ordinary calls failing with "Decoder stalled" in the log when the file plays fine.
- Waveforms of normal calls staying blank, with the same log line.

Either means the progress signal is wrong on some decoder (e.g. one that holds output back for minutes), not
that the files are broken.
