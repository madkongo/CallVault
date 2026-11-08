/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.baba.callvault.data.transcripts.CapturedSpeakerTurns
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.EncoderLimits
import com.baba.callvault.server.StereoBitRate
import com.baba.callvault.server.StereoEncoder
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.server.speakers.SpeakerTurnDetector
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PcmDownmix
import java.io.FileDescriptor
import java.io.InputStream

/**
 * APP-side encoder for a handed-off capture: reads raw interleaved PCM-16 from [pcmIn] (the cblk drain,
 * streamed over a pipe by `nativeDrainToPipe`) and encodes it to [outFd] via `MediaCodec` → `MediaMuxer`.
 *
 * This is the SAME synchronous drive as [com.baba.callvault.server.DirectAudioRecorderSession], but it
 * runs in the always-alive APP process and takes its input from a stream instead of an `AudioRecord` —
 * which is what lets the encode survive the privileged daemon dying mid-call.
 */
class HandoffEncoder(
    private val pcmIn: InputStream,
    private val outFd: FileDescriptor,
    private val sampleRate: Int = 16_000,
    /** Channels in the incoming PCM stream (1 mono, 2 interleaved stereo). */
    private val captureChannels: Int = 1,
    /**
     * true → downmix stereo to mono (the default file). false → keep L/R apart (#74 stereo output),
     * when the encoder accepts two channels; otherwise it still records mono.
     */
    private val downmixToMono: Boolean = true,
    private val mime: String = MediaFormat.MIMETYPE_AUDIO_AAC,
    private val muxerFormat: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    private val bitRate: Int = 64_000,
) {
    /**
     * Runs the read → encode → mux loop on the CALLING thread until [pcmIn] reaches EOF (native closed the
     * pipe write end at drain-end / stop), then flushes EOS and finalises the container. Blocking.
     */
    /** Seconds of audio actually written, readable after [encodeBlocking] returns. */
    @Volatile
    var encodedSeconds: Float = 0f
        private set

    fun encodeBlocking() {
        var encodeChannels = StereoBitRate.encodeChannels(!downmixToMono, captureChannels) {
            EncoderLimits.supportsFormat(mime, sampleRate, StereoBitRate.STEREO)
        }
        if (!downmixToMono && encodeChannels == StereoBitRate.MONO) {
            AppLogger.w(TAG, "Stereo output asked for but not possible here (captureCh=$captureChannels); recording mono")
        }
        val enc = runCatching { openEncoder(encodeChannels) }.getOrElse { refused ->
            // A stereo configure the device declines must not cost the call: record it mono instead.
            if (encodeChannels == StereoBitRate.MONO) throw refused
            AppLogger.w(TAG, "Stereo encode refused (${refused.message}); recording mono instead")
            encodeChannels = StereoBitRate.MONO
            openEncoder(encodeChannels)
        }
        val stereoCapture = captureChannels == StereoBitRate.STEREO
        val downmix = stereoCapture && encodeChannels == StereoBitRate.MONO
        val outFrameBytes = encodeChannels * 2 // PCM-16 output frame size
        val mux = MediaMuxer(outFd, muxerFormat)
        enc.start()
        AppLogger.i(TAG, "HandoffEncoder started: mime=$mime rate=$sampleRate captureCh=$captureChannels encodeCh=$encodeChannels bitRate=$bitRate")

        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val mono = ByteArray(READ_CHUNK_BYTES / 2) // downmix target (stereo → half the bytes)
        // Who was speaking when, taken from the same frames and at the same moment as the direct
        // path takes it: after the capture, before any downmix destroys the separation. Keyed on the
        // CAPTURE — keying it on the downmix is what cost the 2026-09-15 stereo probe its labels on
        // carrier calls. Only a stereo capture carries it — a mono one has nothing to compare.
        val speakers = if (stereoCapture) SpeakerTurnDetector(sampleRate) else null
        CapturedSpeakerTurns.clear()

        try {
            while (true) {
                val read = readFull(pcm, captureChannels * 2) // whole frames only (avoids split-frame downmix)
                if (read < 0) break // native closed the pipe → end of capture
                if (read == 0) continue

                // Guarded: a recording that works is worth more than a label, so a fault in
                // detection must cost the turns and nothing else.
                if (speakers != null) {
                    runCatching { speakers.accept(pcm, read) }
                        .onFailure { AppLogger.w(TAG, "Speaker detection failed; continuing without turns: ${it.message}") }
                }

                // Downmix interleaved stereo (average L+R), or pass the captured PCM through unchanged.
                val (buf, len) = if (downmix) mono to PcmDownmix.stereoToMono(pcm, read, mono) else pcm to read

                // Feed the chunk — NEVER drop it. The pipe is bursty, so all input buffers can be briefly
                // busy; if dequeue times out we drain output (frees a buffer) and retry, rather than
                // discarding audio (which caused periodic dropouts = choppiness).
                var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inIdx < 0) {
                    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }
                val inBuf = enc.getInputBuffer(inIdx)!!
                inBuf.clear(); inBuf.put(buf, 0, len)
                val ptsUs = totalFrames * 1_000_000L / sampleRate
                enc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                totalFrames += len / outFrameBytes // output frames (across all encoded channels)
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
            }

            // Published before the tail is flushed, so the turns are waiting by the time the
            // recording is catalogued and the app comes looking for them.
            if (speakers != null) {
                runCatching { CapturedSpeakerTurns.publish(SpeakerTurnCodec.encode(speakers.finish())) }
                    .onFailure { AppLogger.w(TAG, "Could not encode speaker turns: ${it.message}") }
            }

            // Flush the encoder tail, then drain to EOS so the container trailer is complete.
            val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
            AppLogger.i(TAG, "HandoffEncoder finished: ${totalFrames} frames (${totalFrames / sampleRate.toFloat()}s)")
            // Published so the receiver can reconcile what was encoded against how long the capture
            // was actually up. That comparison is the one check that catches a short recording no
            // matter what caused it, including causes nobody has thought of yet.
            encodedSeconds = totalFrames / sampleRate.toFloat()
        } finally {
            runCatching { enc.stop() }
            runCatching { enc.release() }
            runCatching { mux.stop() }   // writes the container trailer (without it the file won't play)
            runCatching { mux.release() }
            runCatching { pcmIn.close() }
        }
    }

    /**
     * Mono: one configure at [bitRate], exactly as before #74. Stereo: [StereoEncoder]'s clamp and floored
     * retry; it throws if both are refused, and [encodeBlocking] then records mono.
     */
    private fun openEncoder(channels: Int): MediaCodec {
        val codec = ScrcpyAudioCodec.entries.firstOrNull { it.mimeType == mime }
        if (channels == StereoBitRate.MONO || codec == null) {
            return MediaCodec.createEncoderByType(mime).apply {
                configure(StereoEncoder.format(mime, sampleRate, channels, bitRate, MAX_INPUT_SIZE), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        return StereoEncoder.open(mime, sampleRate, codec, bitRate, MAX_INPUT_SIZE) { AppLogger.w(TAG, it) }
    }

    /** Reads into [buf] and returns a byte count that is a whole multiple of [align] (or -1 at EOF). */
    private fun readFull(buf: ByteArray, align: Int): Int {
        var n = pcmIn.read(buf, 0, buf.size)
        if (n < 0) return -1
        while (n % align != 0) { // top up so a frame is never split across the downmix boundary
            val r = pcmIn.read(buf, n, align - (n % align))
            if (r < 0) break
            n += r
        }
        return n
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
                    if (drainToEos) continue else return muxerStarted
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

    companion object {
        private const val TAG = "CV:HandoffEnc"
        private const val READ_CHUNK_BYTES = 4096
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
    }
}
