/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.media.MediaExtractor
import com.baba.callvault.utils.AppLogger

/** The two calls the seek helper needs, so it can be tested without a real [MediaExtractor]. */
interface SeekableTrack {
    fun seekTo(timeUs: Long)

    /** Time of the sample the extractor is on, or negative when there is none. */
    val sampleTimeUs: Long
}

/** [SeekableTrack] over a real extractor, seeking to the sync frame at or before the time asked. */
class ExtractorTrack(private val extractor: MediaExtractor) : SeekableTrack {
    override fun seekTo(timeUs: Long) = extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    override val sampleTimeUs: Long get() = extractor.sampleTime
}

/**
 * Puts a track at or before a time (review 2026-11-20, C82). Every Opus and AAC frame is a sync frame, but Android's
 * Ogg seek is by page bisection and can land AFTER the target in a long file — the later step grows with the call's
 * length (measured on the OP9 2026-11-04: a 30 s part of a 57-minute call lost its first 5.5 s). Callers that report
 * where their audio really starts would otherwise silently skip the gap. So step back, with a growing back-off,
 * until the first sample is not later than the target. A short or normal file takes the single seek it always did.
 */
object SeekAtOrBefore {

    private const val TAG = "CV:Seek"
    private const val FIRST_BACK_OFF_US = 5_000_000L

    /**
     * @throws java.io.IOException when even a seek to the very start leaves no sample, so a caller fails loudly
     * instead of reading from nowhere or looping.
     */
    fun seek(track: SeekableTrack, targetUs: Long): Long {
        var backUs = 0L
        var tries = 0
        while (true) {
            val at = (targetUs - backUs).coerceAtLeast(0L)
            track.seekTo(at)
            tries++
            val landed = track.sampleTimeUs
            if (landed in 0..targetUs) {
                if (backUs > 0) AppLogger.i(TAG, "Seek landed late; stepped back ${backUs / 1000} ms ($tries seeks) to reach the start")
                return landed
            }
            if (at == 0L) {
                // Nothing at the very start: the seek failed, or the track is empty.
                if (landed < 0) throw java.io.IOException("Seek found no sample, even at the start ($tries seeks)")
                // A first sample after a target of 0 (a stream that starts late): the earliest there is.
                return landed
            }
            backUs = if (backUs == 0L) FIRST_BACK_OFF_US else backUs * 2
        }
    }
}
