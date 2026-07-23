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
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.utils.AppLogger
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
        val androidSource = androidSourceFor(source)
            ?: throw UnsupportedOperationException("source ${source.cliKey} is not a mic-type source")
        val mime = encoderMimeFor(codec)

        // Try stereo first (matches scrcpy's 48 kHz stereo output); fall back to mono if the source
        // won't initialise in stereo (some OEM VOICE_CALL routes are mono-only).
        val (record, channelCount) = openAudioRecord(androidSource)
        audioRecord = record

        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val enc = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
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
        AppLogger.i(TAG, "Direct capture started: source=${source.cliKey} codec=${codec.cliKey} ch=$channelCount rate=$SAMPLE_RATE")

        readThread = Thread { runCatching { captureLoop(record, enc, mux) }
            .onFailure { AppLogger.w(TAG, "Direct capture loop ended: ${it.message}") } }
            .apply { isDaemon = true; name = "direct-capture" }
            .also { it.start() }
    }

    /**
     * Reads PCM from [record], feeds it to [enc], and muxes the encoded output into [mux] until [stop]
     * signals EOS. Standard synchronous MediaCodec drive: queue input with a monotonic sample-count PTS,
     * drain output, add the track on INFO_OUTPUT_FORMAT_CHANGED (its format carries the Opus/AAC CSD).
     */
    private fun captureLoop(record: AudioRecord, enc: MediaCodec, mux: MediaMuxer) {
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        val bytesPerFrame = 2 * record.channelCount // PCM-16

        while (!stopRequested.get()) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue

            val inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIdx >= 0) {
                val inBuf = enc.getInputBuffer(inIdx)!!
                inBuf.clear(); inBuf.put(pcm, 0, read)
                val ptsUs = totalFrames * 1_000_000L / SAMPLE_RATE
                enc.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                totalFrames += read / bytesPerFrame
            }
            muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
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
        // Let the capture loop notice the stop flag, flush EOS, and finalise the muxer.
        runCatching { readThread?.join(READ_JOIN_MS) }
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
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
        runCatching { audioRecord?.release() }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() } // MediaMuxer.release() does NOT close the fd — outFd stays usable
        audioRecord = null; encoder = null; muxer = null
    }

    private fun openAudioRecord(androidSource: Int): Pair<AudioRecord, Int> {
        for (channelMask in intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)) {
            val channels = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val rec = runCatching {
                @Suppress("MissingPermission") // shell uid holds CAPTURE_AUDIO_OUTPUT; the daemon is not an app.
                AudioRecord(androidSource, SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR)
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec to channels
            runCatching { rec?.release() }
        }
        throw IllegalStateException("AudioRecord would not initialise for source $androidSource (stereo or mono)")
    }

    companion object {
        private const val TAG = "CV:DirectCapture"

        /** Match scrcpy's output so the muxed file is equivalent (48 kHz). */
        private const val SAMPLE_RATE = 48_000
        private const val READ_CHUNK_BYTES = 4096
        private const val MAX_INPUT_SIZE = 16_384
        private const val BUFFER_FACTOR = 4
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val READ_JOIN_MS = 2_000L

        /**
         * True if the direct pipeline can handle this [source]+[codec] on THIS device: the source must be
         * a mic-type `AudioSource` (not output/playback capture) AND the device must have an encoder for
         * the codec's MIME. Otherwise [RecorderServer] uses the scrcpy fallback.
         */
        fun supports(source: ScrcpyAudioSource, codec: ScrcpyAudioCodec): Boolean {
            if (androidSourceFor(source) == null) return false
            return runCatching { hasEncoder(encoderMimeFor(codec)) }.getOrDefault(false)
        }

        /** Maps our scrcpy `audio_source` cliKey to an Android [MediaRecorder.AudioSource]; null = needs scrcpy. */
        private fun androidSourceFor(source: ScrcpyAudioSource): Int? = when (source.cliKey) {
            "voice-call" -> MediaRecorder.AudioSource.VOICE_CALL
            "mic-voice-communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "mic" -> MediaRecorder.AudioSource.MIC
            "mic-voice-recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            "mic-voice-performance" -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
            else -> null // "output"/playback-capture etc. — not a plain AudioRecord source
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
