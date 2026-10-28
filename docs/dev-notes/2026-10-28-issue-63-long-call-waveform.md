# Issue #63: long calls have no waveform — 🧪 VERIFYING

**Status:** 🧪 VERIFYING. Built, all unit tests pass, and it works on the OP9 with an imported 45:00 Opus file.
To settle it: on the OP12 (256 MB heap), record or open a call over 40 minutes, leave its page at once,
come back about 5 minutes later, and check that the waveform is there.

## What the reporter claimed, and what was true

The report (AbdulAziz-Hatem) blamed non-monotonic Ogg timestamps for unplayable >40-min files. Not
supported: the quoted ffmpeg error comes from ffmpeg's own AAC encode step, and the maintainer's 44:57 call
plays fine. What the maintainer saw was **no waveform** on that call.

## Causes found (measured on the OP9, 2026-10-28)

1. **Each 5-minute window reserved the whole call.** `AudioDecoder.decodePcm` sized its buffer from the
   file's declared duration even for a range decode: ~264 MB per window of a 45-min call. Fine on the OP9's
   large heap, an OutOfMemoryError on a 256 MB heap like the OP12's. A concurrent import probe on the OP9
   did hit OutOfMemoryError. Also affects long-call transcription chunks.
   Fix: `PcmShortSink.spanUs` sizes from the range (+2 s seek run-up). Java heap while drawing: ~44–54 MB.
2. **Drawing is slow and nothing let it finish.** Android's Opus decoder runs at ~12–15x real time
   (20–26 s per 5-min window; resampling only ~1.4 s). A 45-min call takes ~3.5 min. The page's draw was
   cancelled when the page closed, the list's pre-draw skipped calls over 15 min, and the call-end draw ran in
   a bare coroutine that dies with the process once the call service stops.
   Fix: `WaveformDrawWorker`, a WorkManager queue (one draw at a time, waits out calls and transcriptions);
   the page watches the cache (`observeWaveform`) and shows the bars when they are saved.

Tried and dropped: keeping the codec's input queue full instead of one packet per output wait. No speed-up
(the time is inside the decoder), so it was reverted.

## Not done

- Faster drawing (e.g. decoding a sample of each bar's span instead of every packet). Would change what the
  bars show; only worth it if 3.5 min in the background turns out to matter.
