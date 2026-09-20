/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.baba.callvault.utils.AppLogger
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a stored recording into the audio whisper.cpp requires: 16 kHz mono float in [-1, 1].
 *
 * CallVault writes 48 kHz mono Opus, but stereo is handled too — recordings made before the mandatory
 * mono downmix (v1.4.4) are stereo and the back-catalogue still contains them.
 *
 * **Known limit, deliberately not solved here.** The whole file is still decoded into memory, so cost
 * remains proportional to call length — but it is now one buffer of PCM plus the 16 kHz output, rather
 * than the four overlapping full-length buffers this used to build. For fifteen minutes at 48 kHz that
 * is roughly 144 MB where it was about 259 MB, which is what
 * [TranscriptionLengthLimit.MAX_MINUTES] was really measuring. See [pcm16ToMono16k] and [PcmShortSink]
 * for the two allocations that were removed.
 *
 * The remaining fix is chunked, VAD-segmented decoding, which the pipeline wants anyway for
 * resumability. Note that whisper's own `offset_ms`/`duration_ms` are **not** a route to it: the mel is
 * built for the whole file before those parameters are read, so looping over them costs full memory
 * every pass and recomputes the mel each time.
 */
object AudioDecoder {

    private const val TAG = "CV:AudioDecoder"

    const val TARGET_SAMPLE_RATE = 16_000

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * How far the decoded length may stray from the container's declared duration before it is
     * treated as a bug rather than as ordinary imprecision. Generous on purpose: the point is to catch
     * an order-of-magnitude error, not to police a few dropped frames.
     */
    private const val DURATION_TOLERANCE = 2.0

    /**
     * Audio for part of a recording, and where that part really starts.
     *
     * [startMs] is not the caller's request: seeking lands on the container's previous sync point, so
     * the audio may begin slightly earlier. Reporting the true start rather than trimming to the
     * request keeps every downstream timestamp exact without threading a sample offset through the
     * resampler — see [decodeRange].
     */
    data class DecodedRange(val audio: FloatArray, val startMs: Long) {
        // Kotlin generates array identity for equals/hashCode on a data class, which is a trap for a
        // value that looks comparable. These are never compared; the overrides state that plainly.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Decoded PCM-16, interleaved, plus the format it is *actually* in — the decoder's output, not
     * the file's input.
     *
     * [pcm] is the accumulator's live buffer and is usually **longer than the audio** — read exactly
     * [length] samples. Returning a trimmed array instead would mean allocating a second full-length
     * buffer while the first is still alive, at the precise moment memory is tightest.
     */
    private data class DecodedPcm(
        val pcm: ShortArray,
        val length: Int,
        val sampleRate: Int,
        val channels: Int,
        /** Where this audio really begins in the recording, which a seek may move earlier. */
        val startMs: Long = 0L,
    )

    /**
     * Interleaved PCM-16 to mono float. Extra channels are averaged in rather than dropped, so a
     * stereo call where each party sits on one side keeps both voices.
     *
     * A trailing partial frame is ignored: integer division bounds the loop, which is what stops a
     * truncated file reading past the end of the array.
     */
    fun pcm16ToMonoFloat(pcm: ShortArray, channels: Int): FloatArray {
        require(channels >= 1) { "channels must be >= 1, was $channels" }
        val frames = pcm.size / channels
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += pcm[f * channels + c] / 32767f
            out[f] = (acc / channels).coerceIn(-1f, 1f)
        }
        return out
    }

    /**
     * Downmix to mono **and** resample to [TARGET_SAMPLE_RATE] in one pass, reading the 16-bit PCM
     * directly.
     *
     * This exists for one reason: peak memory. [pcm16ToMonoFloat] followed by [resampleTo16k] holds a
     * float32 copy of the entire call *at the input rate*, alongside the PCM it was widened from. At
     * 48 kHz that is 4 bytes per sample where the source had 2, so fifteen minutes costs 86.4 MB of
     * `ShortArray` plus 172.8 MB of `FloatArray` — **259 MB against a 256 MB heap**.
     *
     * That is where [TranscriptionLengthLimit.MAX_MINUTES] came from. The limit was set empirically at
     * the point transcription started failing, and this allocation is what it was measuring; nobody
     * realised the number had a cause. Reading the shorts directly and only ever materialising the
     * 16 kHz output takes the peak to roughly 144 MB, because the resampled array is a third the
     * length and the wide intermediate never exists.
     *
     * **Identical output to the two-step path, deliberately.** The anti-alias filter was tuned against
     * thirteen real recordings from this device and must not shift, so this reproduces the same
     * arithmetic in the same order rather than reimplementing it — see the equivalence tests. The
     * per-tap cost of converting a sample on demand instead of once up front is real but small against
     * whisper's own runtime, and it buys back a hundred megabytes.
     */
    fun pcm16ToMono16k(
        pcm: ShortArray,
        channels: Int,
        inputRate: Int,
        /** Samples of [pcm] that are real audio; the decoder's buffer is normally longer. */
        length: Int = pcm.size,
    ): FloatArray {
        require(channels >= 1) { "channels must be >= 1, was $channels" }
        require(length in 0..pcm.size) { "length $length outside 0..${pcm.size}" }
        val frames = length / channels
        if (frames == 0) return FloatArray(0)

        // Already at the target rate: widen to float and stop. Matches resampleTo16k returning its
        // input untouched, which is the branch this replaces.
        if (inputRate == TARGET_SAMPLE_RATE) return FloatArray(frames) { monoAt(pcm, channels, it) }

        val ratio = inputRate.toDouble() / TARGET_SAMPLE_RATE
        val outLen = (frames / ratio).toInt()
        val out = FloatArray(outLen)
        val taps = if (ratio > 1.0) antiAliasKernel(inputRate) else null
        for (i in 0 until outLen) {
            val src = i * ratio
            val a = src.toInt()
            val frac = (src - a).toFloat()
            val sa = filteredMonoAt(pcm, channels, frames, a, taps)
            out[i] =
                if (frac == 0f) sa
                else sa * (1 - frac) + filteredMonoAt(pcm, channels, frames, a + 1, taps) * frac
        }
        return out
    }

    /**
     * One frame of [pcm] as a mono sample, with exactly the arithmetic [pcm16ToMonoFloat] uses.
     *
     * The `coerceIn` is not redundant: `-32768 / 32767f` is `-1.00003`, and whisper is handed a range
     * it is entitled to assume.
     */
    private fun monoAt(pcm: ShortArray, channels: Int, frame: Int): Float {
        val base = frame * channels
        var acc = 0f
        for (c in 0 until channels) acc += pcm[base + c] / 32767f
        return (acc / channels).coerceIn(-1f, 1f)
    }

    /** [filteredSampleAt], reading frames of [pcm] on demand instead of a pre-widened array. */
    private fun filteredMonoAt(
        pcm: ShortArray,
        channels: Int,
        frames: Int,
        center: Int,
        taps: FloatArray?,
    ): Float {
        if (taps == null) return monoAt(pcm, channels, center.coerceIn(0, frames - 1))
        val start = center - taps.size / 2
        var acc = 0f
        if (start >= 0 && start + taps.size <= frames) {
            for (k in taps.indices) acc += taps[k] * monoAt(pcm, channels, start + k)
        } else {
            for (k in taps.indices) acc += taps[k] * monoAt(pcm, channels, (start + k).coerceIn(0, frames - 1))
        }
        return acc
    }

    /**
     * Low-pass cutoff as a fraction of the 8 kHz Nyquist limit of [TARGET_SAMPLE_RATE]. 0.9 puts the
     * −3 dB knee near 6.8 kHz and −40 dB by 8.6 kHz: telephony speech loses nothing measurable (0.01 dB
     * below 3.4 kHz, 0.24 dB from 3.4–7 kHz on the recordings this was tuned against) while the
     * stopband is fully developed before anything can fold back.
     */
    private const val ANTI_ALIAS_CUTOFF_FRACTION = 0.9

    /**
     * Transition-band width in Hz, which is what actually sets the cost: a Hamming-windowed sinc needs
     * about `3.3 * inputRate / width` taps. 3 kHz buys 53 taps at 48 kHz — roughly one extra second of
     * CPU on a 15-minute call, against whisper's minutes.
     */
    private const val ANTI_ALIAS_TRANSITION_HZ = 3_000.0

    /** Kernel length bounds. Odd on both ends so the filter keeps an integer group delay. */
    private const val MIN_ANTI_ALIAS_TAPS = 15
    private const val MAX_ANTI_ALIAS_TAPS = 129

    /**
     * Resample to [TARGET_SAMPLE_RATE], low-passing first so nothing folds back into the band whisper
     * reads.
     *
     * The filter is not optional garnish. CallVault records at 48 kHz and 48000/16000 is exactly 3, so
     * the interpolation fraction below is identically zero on the production path: without a filter
     * this is keep-one-discard-two, and every component from 8–24 kHz lands back in 0–8 kHz at full
     * amplitude — precisely the range whisper's mel filterbank reads.
     *
     * This used to be defended on the grounds that telephony carries nothing above ~3.4 kHz. That is
     * true of the *carrier downlink*, and it was measured on the PCM before encoding — but it is not
     * true of the files the transcriber actually opens. Every capture path mixes in a local microphone
     * that the network never band-limits: `VOICE_CALL` carries the uplink alongside the downlink, and
     * the VoIP path adds a plain full-band `MIC`. Measured across 13 real recordings from this device,
     * the energy that folds into 0–4 kHz sat at −26 dB relative to the signal there on average and
     * −17.5 dB at worst, outgoing carrier calls being the dirtiest. Filtering drops that to −94 dB on
     * average and −85 dB at worst.
     *
     * For calibration: upstream whisper.cpp resamples through miniaudio, whose default 4th-order
     * low-pass would reach −45 dB on the same material. A short windowed sinc is both cheaper to reason
     * about and better than the reference we are matching.
     *
     * The interpolation still matters for 44.1 kHz input, the one rate that does not divide evenly.
     */
    fun resampleTo16k(input: FloatArray, inputRate: Int): FloatArray {
        if (inputRate == TARGET_SAMPLE_RATE || input.isEmpty()) return input
        val ratio = inputRate.toDouble() / TARGET_SAMPLE_RATE
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        // Upsampling has no stopband to reject — there is nothing above the *source* Nyquist to fold —
        // and filtering there would only throw away signal the source legitimately carries.
        val taps = if (ratio > 1.0) antiAliasKernel(inputRate) else null
        for (i in 0 until outLen) {
            val src = i * ratio
            val a = src.toInt()
            val frac = (src - a).toFloat()
            val sa = filteredSampleAt(input, a, taps)
            // Skipping the second convolution when frac is zero halves the work on every rate that
            // divides evenly — which is all of ours except 44.1 kHz.
            out[i] = if (frac == 0f) sa else sa * (1 - frac) + filteredSampleAt(input, a + 1, taps) * frac
        }
        return out
    }

    /**
     * [input] at [center], low-passed by [taps], or the raw sample when there is no filter.
     *
     * Edges clamp to the first and last sample rather than zero-padding: zero-padding rings audibly
     * over the first and last few milliseconds, and a call's opening word is exactly where that would
     * cost a transcript something.
     */
    private fun filteredSampleAt(input: FloatArray, center: Int, taps: FloatArray?): Float {
        if (taps == null) return input[center.coerceIn(0, input.size - 1)]
        val start = center - taps.size / 2
        var acc = 0f
        if (start >= 0 && start + taps.size <= input.size) {
            // Interior fast path: the overwhelming majority of samples, with no bounds arithmetic.
            for (k in taps.indices) acc += taps[k] * input[start + k]
        } else {
            for (k in taps.indices) acc += taps[k] * input[(start + k).coerceIn(0, input.size - 1)]
        }
        return acc
    }

    /**
     * Hamming-windowed sinc low-pass for [inputRate], cut below the 8 kHz Nyquist of the target rate.
     *
     * Hamming rather than a sharper window because its ~53 dB stopband is already far more rejection
     * than the −17.5 dB worst case needs, and a better window would only cost taps. The tap count is
     * derived from [inputRate] so the transition band stays the same *width in Hz* whatever the source
     * rate: a 32 kHz file needs two thirds of the work a 48 kHz one does for the same result.
     *
     * The length is forced odd so the group delay is a whole number of samples and the output stays
     * aligned with the input — a half-sample skew is inaudible but it smears every timestamp.
     */
    internal fun antiAliasKernel(inputRate: Int): FloatArray {
        val length = ((3.3 * inputRate / ANTI_ALIAS_TRANSITION_HZ).toInt() or 1)
            .coerceIn(MIN_ANTI_ALIAS_TAPS, MAX_ANTI_ALIAS_TAPS)
        val half = length / 2
        // Cutoff normalised to the *input* Nyquist, which is what the sinc argument is in terms of.
        val cutoff = TARGET_SAMPLE_RATE * ANTI_ALIAS_CUTOFF_FRACTION / inputRate
        val taps = FloatArray(length)
        var sum = 0.0
        for (k in 0 until length) {
            val x = (k - half).toDouble()
            val sinc = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
            val window = 0.54 - 0.46 * cos(2.0 * PI * k / (length - 1))
            val tap = sinc * window
            taps[k] = tap.toFloat()
            sum += tap
        }
        // Normalise to unity DC gain: without this the filter quietly rescales the whole recording,
        // and whisper's front end is sensitive to level.
        for (k in 0 until length) taps[k] = (taps[k] / sum).toFloat()
        return taps
    }

    /**
     * Whether [frames] at [sampleRate] is a believable decode of a file declaring [expectedDurationUs].
     *
     * This exists because of a real failure: handing whisper an over-long or malformed buffer does not
     * throw — it simply runs for as long as the buffer implies. A 45-second clip once transcribed for
     * over eleven minutes before anyone could tell anything was wrong. Checking the arithmetic costs
     * nothing and converts that into an immediate, legible error.
     *
     * Returns true when the duration is unknown ([expectedDurationUs] <= 0), because some containers
     * genuinely do not declare one and that is not evidence of a bug.
     */
    fun isPlausibleDuration(frames: Int, sampleRate: Int, expectedDurationUs: Long): Boolean {
        if (expectedDurationUs <= 0L) return true
        if (sampleRate <= 0) return false
        val decodedMs = frames * 1000.0 / sampleRate
        val expectedMs = expectedDurationUs / 1000.0
        if (decodedMs == 0.0) return expectedMs == 0.0
        val ratio = decodedMs / expectedMs
        return ratio in (1.0 / DURATION_TOLERANCE)..DURATION_TOLERANCE
    }

    /**
     * The recording's length in milliseconds, read from the container without decoding it.
     *
     * Milliseconds of work, because it only reads the track format — which is what makes an estimate
     * possible the instant Transcribe is tapped, with nothing to wait for. Returns 0 when the container
     * declares no duration; callers must treat that as "unknown", not "empty".
     */
    fun durationMs(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                requireNotNull(pfd) { "Cannot open $uri" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return 0L
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000L else 0L
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read the duration of $uri: ${e.message}")
            0L
        } finally {
            extractor.release()
        }
    }

    /**
     * Decodes [uri] to 16 kHz mono float. Blocking and CPU-bound — [TranscriptionEngine] is what moves
     * it off the main thread; do not call it directly from UI code.
     *
     * @param shouldStop consulted while decoding so a stop is honoured here too. Decoding a long
     *   recording is otherwise uninterruptible — a 1.5-hour call kept burning CPU for ~12 seconds
     *   after Stop was tapped, because it had not reached whisper yet.
     * @throws IllegalStateException if the decode produces an implausible amount of audio, rather than
     *   letting a malformed buffer reach whisper where it costs minutes of silent CPU burn.
     * @throws InterruptedException if [shouldStop] becomes true while decoding.
     */
    fun decodeToMono16k(
        context: Context,
        uri: Uri,
        shouldStop: () -> Boolean = { false }
    ): FloatArray = decodeRange(context, uri, shouldStop = shouldStop).audio

    /**
     * 16 kHz mono float for part of [uri], and where that audio really starts.
     *
     * The point of the range: peak memory stops depending on call length. Decoding a five-minute slice
     * costs the same whether the call is five minutes or two hours, so
     * [TranscriptionLengthLimit.MAX_MINUTES] stops being a memory limit and can eventually go. See
     * [ChunkPlan] for how the slices are chosen.
     *
     * **[DecodedRange.startMs] is the honest answer, not the request.** Seeking lands on the container's
     * previous sync point, at or before what was asked for, so the audio returned may begin slightly
     * early. Reporting that instead of trimming to the exact request keeps timestamps exact with no
     * offset arithmetic threaded through the resampler — and the extra frames are run-up, which is
     * useful rather than wasted.
     *
     * @param fromMs where to start; 0 for the beginning.
     * @param toMs where to stop, or [Long.MAX_VALUE] for the end of the recording.
     */
    fun decodeRange(
        context: Context,
        uri: Uri,
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE,
        /**
         * Offered the interleaved PCM-16 **before** the mono downmix, with the channel count and the
         * chunk's real start — which a seek may move earlier than [fromMs].
         *
         * Exists so a caller that is already decoding can also ask who was speaking, without decoding
         * the file a second time (issue #38). Null by default, so every existing caller behaves exactly
         * as before; the samples are the accumulator's live buffer and must not be retained.
         *
         * Declared **before** [shouldStop] on purpose: [shouldStop] has to stay the last parameter or
         * every existing trailing-lambda call site silently rebinds to this one instead. The compiler
         * caught that immediately, but only because the types differ — it would not always.
         */
        onInterleaved: ((pcm: ShortArray, length: Int, channels: Int, sampleRate: Int, startMs: Long) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): DecodedRange {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
                requireNotNull(pfd) { "Cannot open $uri" }
                extractor.setDataSource(pfd.fileDescriptor)
            }
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in $uri")

            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            val expectedDurationUs =
                if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            val wholeFile = fromMs <= 0L && toMs == Long.MAX_VALUE
            val decoded = decodePcm(
                extractor, inputFormat, mime, shouldStop,
                fromUs = if (fromMs > 0L) fromMs * 1000L else 0L,
                toUs = if (toMs == Long.MAX_VALUE) Long.MAX_VALUE else toMs * 1000L,
            )
            // Frame count from arithmetic rather than from a widened copy: the copy is exactly what
            // this path no longer makes. See pcm16ToMono16k.
            val frames = decoded.length / decoded.channels.coerceAtLeast(1)

            // Only meaningful for a whole-file decode. A range is *expected* to produce less audio
            // than the container declares, so applying this to a chunk would reject every one of them
            // — turning the guard that catches a malformed decode into one that blocks the feature.
            check(!wholeFile || isPlausibleDuration(frames, decoded.sampleRate, expectedDurationUs)) {
                "Decoded $frames frames at ${decoded.sampleRate} Hz " +
                    "(${frames * 1000L / decoded.sampleRate.coerceAtLeast(1)} ms) but the container " +
                    "declares ${expectedDurationUs / 1000} ms — refusing to transcribe a malformed decode"
            }

            AppLogger.i(
                TAG,
                "Decoded $uri: $frames frames @ ${decoded.sampleRate} Hz, ${decoded.channels} ch " +
                    "(${frames * 1000L / decoded.sampleRate.coerceAtLeast(1)} ms)" +
                    if (wholeFile) "" else " from ${decoded.startMs} ms",
            )
            // Before the downmix, because the downmix is exactly what destroys the answer.
            onInterleaved?.invoke(decoded.pcm, decoded.length, decoded.channels, decoded.sampleRate, decoded.startMs)

            return DecodedRange(
                audio = pcm16ToMono16k(decoded.pcm, decoded.channels, decoded.sampleRate, decoded.length),
                startMs = decoded.startMs,
            )
        } finally {
            extractor.release()
        }
    }

    /**
     * Drains the decoder into raw little-endian PCM-16, reporting the format the decoder **actually
     * produced**.
     *
     * Taking the sample rate and channel count from the extractor's input format is a bug: a decoder
     * may emit a different layout, and believing the wrong one silently rescales the whole recording.
     * `INFO_OUTPUT_FORMAT_CHANGED` is therefore honoured rather than ignored.
     *
     * Bytes are accumulated rather than boxed `Short`s on purpose: a ten-minute call is ~29 million
     * samples, and a `List<Short>` of that would cost hundreds of megabytes in object headers alone.
     */
    private fun decodePcm(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        shouldStop: () -> Boolean,
        fromUs: Long = 0L,
        toUs: Long = Long.MAX_VALUE,
    ): DecodedPcm {
        val codec = MediaCodec.createDecoderByType(mime)

        // Seeking lands on the previous sync point, which is at or before what was asked for. Rather
        // than trimming to the exact request — which would mean tracking a sample offset through every
        // later stage — the decoder REPORTS where its audio really starts, and the caller stitches
        // from that. Exact timestamps, no offset arithmetic, and a few extra frames of run-up that
        // whisper is glad of anyway.
        if (fromUs > 0L) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var firstOutUs = -1L

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // Sized from the declared duration so a whole call normally fits without ever growing. A
        // container that lies only costs a reallocation; the capacity is capped so it cannot cost an
        // OutOfMemoryError.
        val declaredUs =
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val sink = PcmShortSink(PcmShortSink.capacityFor(declaredUs, sampleRate, channels))

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false

            while (!sawOutputEnd) {
                // Decoding a long recording takes a while and is otherwise uninterruptible: a
                // 1.5-hour call spent ~12 seconds here after Stop was tapped, still burning CPU. The
                // check is per output buffer, so it costs nothing and reacts within milliseconds.
                if (shouldStop()) throw InterruptedException("decode stopped")

                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        // Past the requested end is the same as end-of-file for this pass. Without
                        // this the decoder would run to the end of the recording for every chunk,
                        // which is the cost chunking exists to avoid.
                        if (size < 0 || extractor.sampleTime > toUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        // The decoder is the authority on what it emits; the extractor only describes
                        // the file. Where they disagree, believing the file rescales the recording.
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels)

                        val encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "Decoder emitted PCM encoding $encoding; this reader only understands 16-bit. " +
                                "Reading float samples as shorts would double the sample count and produce noise."
                        }
                        AppLogger.i(TAG, "Decoder output format: ${sampleRate} Hz, $channels ch")
                    }

                    else -> if (outIndex >= 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            if (firstOutUs < 0L) firstOutUs = info.presentationTimeUs
                            // A view over the codec's own memory, sliced to exactly what it reported.
                            // slice() does not inherit byte order, so it is set on the view.
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            sink.append(buf.slice().order(ByteOrder.LITTLE_ENDIAN), info.size)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }

        // The sink's buffer, not a trimmed copy: trimming would allocate a second full-length array
        // at the one moment the first is still alive, which is what this rewrite removes.
        return DecodedPcm(sink.array, sink.size, sampleRate, channels, maxOf(0L, firstOutUs) / 1000L)
    }
}
