/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.baba.callvault.utils.AppLogger
import java.io.FileDescriptor

/**
 * Track selection and format agreement, shared by [AudioConcat] and [AudioSplit].
 *
 * Merging copies encoded frames without decoding them, which is what makes it lossless and instant —
 * and also what makes it fussy: two recordings can only be joined frame-for-frame if their frames
 * mean the same thing. Same codec, same sample rate, same channel count, or the join is refused.
 *
 * In practice recordings from one phone usually agree: the direct and VoIP paths both run at 48 kHz and
 * encode mono by default. Mismatches come from changing the codec between two calls, or — with stereo
 * output on (#74) — from one call on a route that can only record mono (Bluetooth) next to a stereo one.
 */
internal object MergeFormat {

    /** Thrown when two recordings cannot be joined without re-encoding. */
    class Incompatible(message: String) : IllegalArgumentException(message)

    /** The audio track's index, or -1. A CallVault recording has exactly one track. */
    fun audioTrackOf(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    /** Opens [fd] and selects its audio track, or throws if it has none. */
    fun openAudio(fd: FileDescriptor): Pair<MediaExtractor, MediaFormat> {
        val extractor = MediaExtractor()
        extractor.setDataSource(fd)
        val track = audioTrackOf(extractor)
        if (track < 0) {
            extractor.release()
            throw Incompatible("No audio track")
        }
        extractor.selectTrack(track)
        return extractor to extractor.getTrackFormat(track)
    }

    /** The three properties that must agree, rendered for a message a person can act on. */
    fun describe(f: MediaFormat): String {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
        val rate = f.optInt(MediaFormat.KEY_SAMPLE_RATE)
        val ch = f.optInt(MediaFormat.KEY_CHANNEL_COUNT)
        return "$mime ${rate}Hz ${ch}ch"
    }

    /** Throws unless [other] can be appended to [first] as raw frames. */
    fun requireCompatible(first: MediaFormat, other: MediaFormat) {
        val reason = incompatibility(
            Shape(first.getString(MediaFormat.KEY_MIME), first.optInt(MediaFormat.KEY_SAMPLE_RATE), first.optInt(MediaFormat.KEY_CHANNEL_COUNT)),
            Shape(other.getString(MediaFormat.KEY_MIME), other.optInt(MediaFormat.KEY_SAMPLE_RATE), other.optInt(MediaFormat.KEY_CHANNEL_COUNT)),
        ) ?: return
        // The exact formats go to the log for a bug report; the person who pressed Merge gets words.
        AppLogger.w("CV:MergeFormat", "Refusing to join: ${describe(first)} vs ${describe(other)}")
        throw Incompatible(reason)
    }

    /** The three properties that must agree, as plain values. */
    data class Shape(val mime: String?, val sampleRate: Int, val channels: Int)

    /**
     * Why two recordings cannot be joined, in words the person who pressed Merge can act on, or null.
     *
     * The message reaches the user as is, so it names the setting they changed rather than the codec
     * internals. Channels first: since stereo output (#74) it is the likeliest difference — the same
     * setting records mono on a route that cannot keep the sides apart, such as Bluetooth.
     */
    fun incompatibility(a: Shape, b: Shape): String? = when {
        a.channels != b.channels ->
            "One call kept each side on its own channel and the other did not, so they cannot be joined"
        a.mime != b.mime -> "These calls were saved in different formats (Opus and AAC), so they cannot be joined"
        a.sampleRate != b.sampleRate -> "These calls were recorded at different sample rates, so they cannot be joined"
        else -> null
    }

    /** MPEG-4 for AAC, Ogg for Opus — matching what the capture paths write. */
    fun muxerFormatFor(format: MediaFormat): Int =
        when (format.getString(MediaFormat.KEY_MIME)) {
            MediaFormat.MIMETYPE_AUDIO_OPUS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        }

    /**
     * Encoder delay and padding in microseconds, or 0 when the container does not carry them.
     *
     * These are the samples the decoder is meant to discard at each end. Plain concatenation drops
     * them, which is why they are recorded per part and re-applied on the split — see
     * [com.baba.callvault.data.recordings.db.MergePartEntry].
     */
    fun codecDelayUs(format: MediaFormat): Pair<Long, Long> {
        val rate = format.optInt(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: return 0L to 0L
        val delay = format.optInt(MediaFormat.KEY_ENCODER_DELAY)
        val padding = format.optInt(MediaFormat.KEY_ENCODER_PADDING)
        return delay.toLong() * 1_000_000L / rate to padding.toLong() * 1_000_000L / rate
    }

    /** [MediaFormat.getInteger] without throwing when the key is simply absent. */
    fun MediaFormat.optInt(key: String, fallback: Int = 0): Int =
        if (containsKey(key)) getInteger(key) else fallback

    /**
     * How many encoded frames [fd] holds.
     *
     * The unit a merge is verified in. Counting frames rather than comparing durations is deliberate:
     * a duration can round, and a file that lost a frame at the seam would still report the right
     * length to the nearest second.
     */
    fun frameCount(fd: FileDescriptor): Int {
        val (extractor, format) = openAudio(fd)
        return try {
            val capacity = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, FALLBACK_MAX_INPUT_SIZE)
                .coerceAtLeast(FALLBACK_MAX_INPUT_SIZE)
            val buffer = java.nio.ByteBuffer.allocate(capacity)
            var n = 0
            while (extractor.readSampleData(buffer, 0) >= 0) {
                n++
                if (!extractor.advance()) break
            }
            n
        } finally {
            extractor.release()
        }
    }

    /** A generous read buffer when the container declares no maximum. */
    const val FALLBACK_MAX_INPUT_SIZE = 128 * 1024
}
