/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures whether a file's two channels actually differ, from interleaved stereo PCM-16.
 *
 * Deliberately the **same shape as [SpeakerTurnDetector.accept]** — `(pcm, len)`, a running sum, no
 * per-chunk allocation — so one decode pass can feed both: this one decides *whether* the channels may
 * be trusted, that one decides *who spoke*. Decoding a call twice to ask two questions about the same
 * samples would be the expensive way round.
 *
 * Accumulates mean square of the mid (`0.5*(L+R)`) and side (`0.5*(L−R)`) channels, then hands the two
 * levels to [StereoSeparation] to judge. See that class for why the gate exists at all.
 *
 * Not thread-safe: one instance per file, fed from the thread that does the decoding.
 */
class StereoSeparationMeter {

    private var frames = 0L
    private var sumMidSq = 0.0
    private var sumSideSq = 0.0

    /**
     * Accumulates [len] bytes of interleaved stereo PCM-16 from [pcm].
     *
     * [len] is what the decoder vouches for, which is normally less than the buffer; reading further
     * would score stale audio from the previous chunk. A trailing partial frame is left unconsumed,
     * matching [SpeakerTurnDetector] and `PcmDownmix.stereoToMono`.
     */
    fun accept(pcm: ByteArray, len: Int) {
        val limit = min(len, pcm.size)
        var index = 0

        while (index + BYTES_PER_FRAME <= limit) {
            val left = sampleAt(pcm, index).toDouble()
            val right = sampleAt(pcm, index + BYTES_PER_SAMPLE).toDouble()

            val mid = (left + right) * HALF
            val side = (left - right) * HALF

            sumMidSq += mid * mid
            sumSideSq += side * side
            frames++

            index += BYTES_PER_FRAME
        }
    }

    /**
     * Accumulates [length] samples of interleaved PCM-16 held as shorts, with [channels] per frame.
     *
     * This is the shape `AudioDecoder` already has, so the offline path costs no byte round-trip. A
     * file with anything other than two channels is ignored outright: there is no second party to
     * find in a mono recording, and folding 5.1 into a left/right question would invent an answer.
     */
    fun accept(pcm: ShortArray, length: Int, channels: Int) {
        if (channels != STEREO) return
        val limit = min(length, pcm.size)
        var index = 0

        while (index + STEREO <= limit) {
            val mid = (pcm[index] + pcm[index + 1]) * HALF
            val side = (pcm[index] - pcm[index + 1]) * HALF

            sumMidSq += mid * mid
            sumSideSq += side * side
            frames++

            index += STEREO
        }
    }

    /**
     * The verdict for everything accepted so far.
     *
     * [StereoSeparation.UNKNOWN] when nothing was ever offered — a mono file decoded through here, or a
     * decode that produced nothing. Callers must read that as "no speaker data", never as an error and
     * never as "the channels are mixed".
     */
    fun separation(): StereoSeparation {
        if (frames == 0L) return StereoSeparation.UNKNOWN
        return StereoSeparation.of(
            sumDb = StereoSeparation.dbOf(rms(sumMidSq)),
            diffDb = StereoSeparation.dbOf(rms(sumSideSq)),
        )
    }

    /** Root mean square, scaled to 0..1 full scale so the decibels are dBFS. */
    private fun rms(sumOfSquares: Double): Double = sqrt(sumOfSquares / frames) / FULL_SCALE

    /** Reads a little-endian PCM-16 sample at [offset]. */
    private fun sampleAt(pcm: ByteArray, offset: Int): Int =
        ((pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_FRAME = BYTES_PER_SAMPLE * 2
        const val FULL_SCALE = 32767.0
        const val HALF = 0.5
        const val STEREO = 2
    }
}
