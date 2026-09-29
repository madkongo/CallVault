/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.androidAudioSource
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.server.speakers.SpeakerTurnDetector
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FAST capture pipeline: a direct `AudioRecord` → `MediaCodec` (encode) → `MediaMuxer` (mux) chain,
 * running IN the privileged daemon process. Replaces the scrcpy-server child for the common case.
 *
 * **Why it's faster.** The scrcpy path spawns a second `app_process`, extracts+verifies the scrcpy jar,
 * and does an abstract-socket handshake before a single sample is captured — ~1–2 s that clips the front
 * of the call, and it also adds the jar extraction to every daemon boot. Here the daemon (already a warm,
 * shell-uid process that holds `CAPTURE_AUDIO_OUTPUT`) opens `AudioRecord` directly: `startRecording()`
 * is ~milliseconds, so capture begins at the first frame with no child process and no jar.
 *
 * **Format parity.** The app creates the SAF output file from the chosen [ScrcpyAudioCodec] (Opus→.ogg,
 * AAC→.m4a), so this encodes to that exact codec/container — no app-side change. [supports] gates use to
 * a mic-type source AND a device that actually has the needed encoder; anything else falls back to scrcpy.
 */
internal class DirectAudioRecorderSession(
    private val source: ScrcpyAudioSource,
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. The muxer writes through it; [stop] closes it after finalising. */
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var readThread: Thread? = null
    @Volatile private var encodeThread: Thread? = null

    /** Set once the reader has left its loop and closed the queue, so the encoder knows to finish. */
    private val readerFinished = AtomicBoolean(false)

    /** PCM in flight between the two threads. Created in [startInternal], once the ring size is known. */
    @Volatile private var chunks: CaptureChunkQueue? = null

    /**
     * Speaker turns, published by the capture loop as it ends and read after [stop] joins it.
     *
     * Stays empty when the capture was mono, or if the loop never reached its end — degrading to "no
     * speaker data" rather than to a broken recording is the rule for everything in this file.
     */
    @Volatile private var speakerTurnsEncoded: String = ""

    override fun speakerTurns(): String = speakerTurnsEncoded

    /**
     * What the reader saw, published as it finishes so [stop] finds it — the same arrangement the
     * speaker turns use, and for the same reason: after [stop] there is nothing left to ask.
     */
    @Volatile private var captureDiagnosticsLine: String = ""

    override fun captureDiagnostics(): String = captureDiagnosticsLine

    override fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // Release our OWN resources but do NOT close outFd — the caller may retry over scrcpy with it.
            // MediaMuxer(FileDescriptor) does not own the fd, so release() leaves it open for the fallback.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val androidSource = source.androidAudioSource
            ?: throw UnsupportedOperationException("source ${source.cliKey} is not a mic-type source")
        val mime = encoderMimeFor(codec)

        // Capture stereo when the route allows it — that reliably gets BOTH directions (uplink on one
        // channel, the remote party's downlink on the other); mono routes fall back to 1 channel.
        val (record, captureChannels) = openAudioRecord(androidSource)
        auditId = CaptureAudit.opened("direct capture (source=$androidSource, ch=$captureChannels)")
        audioRecord = record

        // ...but always ENCODE MONO. A phone call is mono content, and encoding the captured stereo as
        // stereo Opus splits the bitrate across the two channels — at the default 24 kbps that leaves
        // ~12 kbps per side and audibly degrades the FAR party (their downlink channel gets starved).
        // Downmixing to one channel gives the whole bitrate to the (mono) call, restoring quality at the
        // same setting. See [captureLoop]'s downmix.
        // Ask the encoder what it will accept before handing it a bit rate. An out-of-range value is
        // not reliably rejected — MediaCodec can clamp it, or emit frames that decode to nothing,
        // which is a full-length recording that plays silent. Also logs the encoder and its limits,
        // so a future bug report can answer in one line what issue #18 never could.
        val effectiveBitRate = EncoderLimits.resolveBitRate(mime, bitRate, SAMPLE_RATE, ENCODE_CHANNELS)
        val enc = openEncoder(mime, effectiveBitRate)
        encoder = enc

        // Create the muxer LAST — the risky AudioRecord/encoder setup above has succeeded, so if we get
        // here the output fd is only now consumed (keeps a clean fd for the scrcpy fallback if we'd thrown).
        val mux = MediaMuxer(outFd.fileDescriptor, codec.outputFormat)
        muxer = mux

        enc.start()
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord failed to enter RECORDING state")
        }
        AppLogger.i(TAG, "Direct capture started: source=${source.cliKey} codec=${codec.cliKey} captureCh=$captureChannels encodeCh=$ENCODE_CHANNELS rate=$SAMPLE_RATE")

        // Two threads, not one — see [CaptureChunkQueue]. The reader's only job is to keep the ring
        // empty; everything that can stall (encode, mux, file write) happens behind the queue.
        val queue = CaptureChunkQueue(READ_CHUNK_BYTES, QUEUE_CAPACITY_CHUNKS)
        chunks = queue

        encodeThread = Thread { runCatching { encodeLoop(queue, enc, mux, captureChannels) }
            .onFailure { AppLogger.w(TAG, "Direct encode loop ended: ${it.message}") } }
            .apply { isDaemon = true; name = "direct-capture-encode" }
            .also { it.start() }

        readThread = Thread { runCatching { readLoop(record, queue, captureChannels) }
            .onFailure { AppLogger.w(TAG, "Direct capture loop ended: ${it.message}") } }
            .apply { isDaemon = true; name = "direct-capture-read" }
            .also { it.start() }
    }

    /**
     * Creates the encoder and configures it, retrying once at the codec's own recommended bit rate.
     *
     * **Why the retry (issue #28c).** "Changing from Opus to AAC failed to record the next call." A
     * `configure()` that a device refuses used to end the attempt: the direct path threw, the scrcpy
     * fallback ran with the same rejected settings, and the call recorded nothing at all. AAC-LC at
     * 48 kHz mono is refused by some hardware encoders at the low rates that suit Opus, and no clamp can
     * prevent it — the encoder advertises a range that includes the rate and then declines it anyway.
     *
     * Retrying at the codec's own recommended rate turns "nothing was recorded" into "recorded, at a
     * rate you did not pick", which is the better failure by a wide margin, and it says so in the log.
     *
     * The codec is created BY NAME, so the encoder that runs is the one whose limits were read — see
     * [EncoderLimits.encoderNameFor].
     */
    private fun openEncoder(mime: String, requestedBitRate: Int): MediaCodec {
        val encoderName = EncoderLimits.encoderNameFor(mime, SAMPLE_RATE, ENCODE_CHANNELS)
        val rates = listOf(requestedBitRate, codec.defaultBitRate).distinct()
        var lastFailure: Throwable? = null

        for ((attempt, rate) in rates.withIndex()) {
            val created = runCatching {
                val c = if (encoderName != null) MediaCodec.createByCodecName(encoderName)
                        else MediaCodec.createEncoderByType(mime)
                runCatching { c.configure(formatFor(mime, rate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
                    .onFailure { runCatching { c.release() } } // a refused configure leaves it unusable
                    .getOrThrow()
                c
            }
            created.getOrNull()?.let { codecInstance ->
                if (attempt > 0) {
                    AppLogger.w(
                        TAG,
                        "Encoder ${encoderName ?: mime} refused $requestedBitRate bps; recording at " +
                            "$rate bps (${codec.cliKey}'s recommended rate) instead",
                    )
                }
                return codecInstance
            }
            lastFailure = created.exceptionOrNull()
            AppLogger.w(TAG, "Encoder ${encoderName ?: mime} would not configure at $rate bps: ${lastFailure?.message}")
        }
        throw IllegalStateException(
            "No encoder for $mime would configure at ${rates.joinToString(" or ")} bps",
            lastFailure,
        )
    }

    private fun formatFor(mime: String, bitRate: Int): MediaFormat =
        MediaFormat.createAudioFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

    /**
     * Empties the `AudioRecord` ring and does nothing else — issue #28b.
     *
     * **This loop must never wait.** The ring holds ~80 ms; anything it waits for is audio Android
     * discards silently. So a chunk is read, handed to [queue], and the loop comes straight back. All
     * the work that can stall — speaker detection, downmix, encode, mux, file write — happens on the
     * encoder thread behind the queue. The native handoff path has worked this way for years
     * (`audiohandoff.cpp`, *"DECOUPLE ring consumption from downstream"*); the direct path did not, and
     * a stall anywhere in its chain came out as the same chunk-aligned splice as issue #28a.
     *
     * Losses that happen anyway are counted rather than silent: [RingOverrunLedger] compares frames the
     * hardware produced against frames we read, so the reporter's next debug report can finally say
     * whether his phone overruns the ring or not.
     */
    private fun readLoop(record: AudioRecord, queue: CaptureChunkQueue, captureChannels: Int) {
        // The audio thread priority the platform reserves for exactly this: a loop that must not be late.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val pcm = ByteArray(READ_CHUNK_BYTES)
        val captureBytesPerFrame = 2 * captureChannels
        // We always trail the hardware by up to the ring's own occupancy; only a deficit LARGER than the
        // ring means frames fell out of it. The floor keeps an unusually small ring from crying wolf.
        val ringFrames = runCatching { record.bufferSizeInFrames }.getOrDefault(0)
        val ledger = RingOverrunLedger(SAMPLE_RATE, maxOf(ringFrames, MIN_TOLERANCE_FRAMES))
        val startNanos = System.nanoTime()
        var framesRead = 0L
        var sinceSample = 0

        while (!stopRequested.get()) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            framesRead += read / captureBytesPerFrame
            queue.offer(pcm, read) // counted inside; never blocks

            if (++sinceSample < LEDGER_SAMPLE_CHUNKS) continue
            sinceSample = 0
            if (ledger.sample(framesRead, System.nanoTime() - startNanos)) {
                AppLogger.w(TAG, "Capture fell behind: ${ledger.lostMillis} ms of audio lost so far to ring overrun")
            }
        }

        readerFinished.set(true)
        queue.close()

        // Published for the app to read after stop(), because the log below only survives in a bug
        // report if the reporter had debug logging on before the call — see IRecorderService.
        captureDiagnosticsLine = listOfNotNull(
            ledger.summary()?.let { "overrunMs=${ledger.lostMillis} overruns=${ledger.overrunEvents}" },
            "droppedChunks=${queue.droppedChunks}".takeIf { queue.droppedChunks > 0 },
            "peakBacklog=${queue.peakDepth}".takeIf { queue.peakDepth > QUEUE_DEPTH_WORTH_REPORTING },
        ).joinToString(" ")

        // Silent when the device kept up, which is the normal case on every phone we have.
        ledger.summary()?.let { AppLogger.w(TAG, it) }
        if (queue.droppedChunks > 0) {
            AppLogger.w(
                TAG,
                "Encoder backlog: ${queue.droppedChunks} chunk(s) dropped because the encode thread " +
                    "stayed more than $QUEUE_CAPACITY_CHUNKS chunks behind (peak depth ${queue.peakDepth})",
            )
        } else if (queue.peakDepth > QUEUE_DEPTH_WORTH_REPORTING) {
            AppLogger.i(TAG, "Encoder backlog peaked at ${queue.peakDepth} chunk(s) — nothing lost")
        }
    }

    /**
     * Takes PCM from [queue], feeds it to [enc], and muxes the encoded output into [mux] until the reader
     * has finished and the queue is drained. Standard synchronous MediaCodec drive: queue input with a
     * monotonic sample-count PTS, drain output, add the track on INFO_OUTPUT_FORMAT_CHANGED (its format
     * carries the Opus/AAC CSD).
     *
     * Everything here may stall without costing audio — that is the whole point of the queue in front.
     */
    private fun encodeLoop(queue: CaptureChunkQueue, enc: MediaCodec, mux: MediaMuxer, captureChannels: Int) {
        val mono = ByteArray(READ_CHUNK_BYTES / 2)   // downmix target (half the samples of stereo input)
        val downmix = captureChannels == 2
        // Speaker turns come free from the stereo buffer we already hold: the two directions are on
        // separate channels here, and that information is destroyed by the downmix below. Only a
        // stereo capture carries it — a mono route has nothing to compare.
        val speakers = if (downmix) SpeakerTurnDetector(SAMPLE_RATE) else null
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        // Counted so a device that cannot keep up is visible in a bug report rather than silent.
        var recoveredChunks = 0L
        var droppedChunks = 0L
        val bytesPerFrame = 2 * ENCODE_CHANNELS // PCM-16, mono → 2 bytes/frame (matches what we feed the encoder)

        while (true) {
            val chunk = queue.take(TAKE_TIMEOUT_MS)
            if (chunk == null) {
                if (readerFinished.get()) break // reader gone and nothing left in the queue
                continue
            }
            val pcm = chunk.bytes
            val read = chunk.length

            // Read the channels BEFORE the downmix averages them away. Guarded: a recording that works
            // is worth more than a label, so a fault here must cost the turns and nothing else.
            if (speakers != null) {
                runCatching { speakers.accept(pcm, read) }
                    .onFailure { AppLogger.w(TAG, "Speaker detection failed; continuing without turns: ${it.message}") }
            }

            // Feed MONO to the encoder: downmix a stereo capture (average L+R), or pass a mono capture through.
            val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read

            // Never drop a chunk just because the encoder was momentarily busy. Draining frees an
            // input buffer, so a retry after a drain almost always succeeds.
            //
            // The old code discarded it — and because it did NOT advance totalFrames, the timeline
            // stayed continuous. So the file was not gappy, it was SPLICED: 21 ms of waveform cut
            // out with the two ends joined. A splice in silence is inaudible; a splice mid-vowel is
            // a step discontinuity whose click scales with the signal level, which is exactly what
            // issue #28 reported — "popping when audio is present… silence doesn't have it".
            //
            // BOUNDED, unlike the equivalent loop in HandoffEncoder. An encoder that is genuinely
            // wedged would spin that one forever; here that no longer starves the ring (the reader
            // is on its own thread), but it would still back the queue up until chunks are dropped.
            // Past the budget we give up, but we COUNT it.
            var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            var attempts = 0
            while (inIdx < 0 && attempts < MAX_FEED_ATTEMPTS) {
                attempts++
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            }
            if (inIdx >= 0) {
                if (attempts > 0) recoveredChunks++
                val inBuf = enc.getInputBuffer(inIdx)!!
                inBuf.clear(); inBuf.put(buf, 0, len)
                val ptsUs = totalFrames * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                totalFrames += len / bytesPerFrame
            } else {
                droppedChunks++
            }
            queue.recycle(chunk)
            muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
        }

        // Silent when nothing went wrong, which is the normal case on every device we have.
        if (recoveredChunks > 0 || droppedChunks > 0) {
            AppLogger.i(
                TAG,
                "Encoder feed: recovered $recoveredChunks chunk(s) by draining and retrying, " +
                    "dropped $droppedChunks after $MAX_FEED_ATTEMPTS attempts",
            )
        }

        // Publish the turns before finalising, so stop() finds them once it has joined this thread.
        if (speakers != null) {
            runCatching { speakerTurnsEncoded = SpeakerTurnCodec.encode(speakers.finish()) }
                .onFailure { AppLogger.w(TAG, "Could not encode speaker turns: ${it.message}") }
        }
        // Signal end-of-stream so the encoder flushes its tail, then drain what's left.
        val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
        if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
    }

    /** Drains available encoder output into the muxer. Returns whether the muxer is (now) started. */
    private fun drainEncoder(
        enc: MediaCodec, mux: MediaMuxer, info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean, drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        var track = if (muxerStarted) 0 else -1
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, if (drainToEos) END_OF_STREAM_TIMEOUT_US else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat) // format carries codec-specific data (CSD)
                    mux.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (drainToEos) continue else return muxerStarted // no output ready right now
                }
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(track, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted
                }
            }
        }
    }

    override fun stop() {
        AppLogger.i(TAG, "Stopping direct capture session")
        stopRequested.set(true)
        // The reader leaves its loop on the flag and closes the queue, which is what tells the encoder
        // nothing more is coming.
        runCatching { readThread?.join(READ_JOIN_MS) }
        // Releasing the record also unblocks a read wedged in the HAL, which is the only way the reader
        // can miss the flag.
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        // Belt and braces for exactly that case: close the queue ourselves, so the encoder is never left
        // waiting for a chunk that is not coming.
        readerFinished.set(true)
        runCatching { chunks?.close() }

        // The encoder now finishes the backlog, writes EOS and finalises the track. Joining it before
        // touching the encoder, the muxer or the fd is not optional: releasing any of them under a live
        // writer crashes the daemon, which loses the whole recording rather than the tail of it.
        runCatching { encodeThread?.join(ENCODE_JOIN_MS) }
        if (encodeThread?.isAlive == true) {
            AppLogger.e(
                TAG,
                "Encode thread still running after $ENCODE_JOIN_MS ms — leaving the encoder, muxer and " +
                    "fd to the process. The file may lack its trailer, but the daemon survives to record " +
                    "the next call.",
            )
            return
        }

        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        // Muxer LAST among writers — stop() writes the container trailer (without it the file won't play).
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        AppLogger.i(TAG, "Direct capture session stopped")
    }

    /** Releases capture resources on a failed [start] WITHOUT closing [outFd] (the caller retries scrcpy). */
    private fun cleanupPartial() {
        CaptureAudit.released(auditId, runCatching { audioRecord?.release() }.exceptionOrNull())
        auditId = 0
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        CaptureAudit.assertNoneLive("after stopping direct capture") // MediaMuxer.release() does NOT close the fd — outFd stays usable
        audioRecord = null; encoder = null; muxer = null
    }

    /** Ledger id for the capture this session holds, so a leak names itself in the report. */
    @Volatile private var auditId: Int = 0

    private fun openAudioRecord(androidSource: Int): Pair<AudioRecord, Int> {
        for (channelMask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            // shell uid holds CAPTURE_AUDIO_OUTPUT; the daemon is not an app. Opened through the host's one opener,
            // which survives vivo's modified constructor (otherwise every vivo phone call fell back to scrcpy).
            val rec = HostAudioRecord.open(
                "direct capture (source=$androidSource, ch=$channels)", androidSource, SAMPLE_RATE, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
            )
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec to channels
            runCatching { rec?.release() }
        }
        throw IllegalStateException("AudioRecord would not initialise for source $androidSource (stereo or mono)")
    }

    companion object {
        private const val TAG = "CV:DirectCapture"

        /** Match scrcpy's output so the muxed file is equivalent (48 kHz). */
        private const val SAMPLE_RATE = 48_000

        /** Always encode mono — a call is mono content, so this gives the full bitrate to the voice. */
        private const val ENCODE_CHANNELS = 1
        private const val READ_CHUNK_BYTES = 4096

        private const val MAX_INPUT_SIZE = 16_384
        private const val BUFFER_FACTOR = 4
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * How many drain-and-retry attempts a chunk gets before it is given up on.
         *
         * Bounded on purpose. The equivalent loop in HandoffEncoder is unbounded, which is fine
         * until an encoder wedges — then it spins the capture thread forever while the AudioRecord
         * ring overruns behind it, and a crackle becomes a dead recording. Three attempts, each
         * after a drain that frees a buffer, comfortably covers a codec that is merely busy;
         * anything past that is a codec that is not coming back, and losing one chunk loudly beats
         * losing the call quietly.
         *
         * Verified by measurement, not by reasoning: with a fault injected every 20th chunk on an
         * OP12, 82 of 82 forced failures recovered and none were dropped, and the splice signature
         * present in the un-fixed recording (p = 4.9e-3 at the predicted spacing) was gone
         * (p = 0.92). See docs/dev-notes/2026-09-05-github-issues-25-28-triage.md.
         */
        private const val MAX_FEED_ATTEMPTS = 3
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val READ_JOIN_MS = 2_000L

        /**
         * How long [stop] waits for the encoder to finish the backlog and finalise the container.
         *
         * Generous on purpose: at this point the reader has stopped, so the only work left is encoding
         * whatever is queued, and encoding runs far faster than real time. Anything past this is an
         * encoder that has wedged, and then the daemon matters more than the tail of one file.
         */
        private const val ENCODE_JOIN_MS = 5_000L

        /**
         * Chunks the reader may run ahead of the encoder — ~5 seconds at 21.3 ms a chunk, under 1 MB.
         *
         * It only has to cover a transient: a stall this long is not an encoder hiccup, it is an encoder
         * that has stopped. Beyond it chunks are dropped and counted, which is the same loss the ring
         * overrun used to cause but visible in the log instead of silent.
         */
        private const val QUEUE_CAPACITY_CHUNKS = 240

        /** ~1 s of backlog: healthy runs sit at 0–1, so anything near this is worth saying out loud. */
        private const val QUEUE_DEPTH_WORTH_REPORTING = 48

        /** How long the encoder waits for a chunk before re-checking whether the reader has finished. */
        private const val TAKE_TIMEOUT_MS = 100L

        /** Overrun accounting runs every ~0.5 s; `getTimestamp` is cheap but not free. */
        private const val LEDGER_SAMPLE_CHUNKS = 24

        /**
         * The smallest deficit that counts as loss (100 ms), whatever the ring size says.
         *
         * The ring is the honest tolerance — we legitimately trail the hardware by up to its occupancy —
         * but a device reporting an unusually small one would otherwise turn ordinary scheduling jitter
         * into a stream of overrun warnings.
         */
        private const val MIN_TOLERANCE_FRAMES = 4_800


        /**
         * True if the direct pipeline can handle this [source]+[codec] on THIS device: the source must be
         * a mic-type `AudioSource` (not output/playback capture) AND the device must have an encoder for
         * the codec's MIME. Otherwise [RecorderServer] uses the scrcpy fallback.
         */
        fun supports(source: ScrcpyAudioSource, codec: ScrcpyAudioCodec): Boolean {
            if (source.androidAudioSource == null) return false
            val mime = encoderMimeFor(codec)
            return runCatching {
                // An encoder EXISTING is not the same as it accepting our format. Recording 48 kHz
                // mono into an encoder that advertises neither is how a full-length silent file is
                // produced; better to fall back to scrcpy, which brings its own pipeline.
                hasEncoder(mime) && EncoderLimits.supportsFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS)
            }.getOrDefault(false)
        }

        private fun encoderMimeFor(codec: ScrcpyAudioCodec): String = when (codec) {
            ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        }

        private fun hasEncoder(mime: String): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
    }
}
