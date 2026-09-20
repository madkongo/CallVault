/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading who spoke out of a recording that has already been made (issue #38).
 *
 * Fed the way `AudioDecoder.decodeRange`'s `onInterleaved` feeds it: one chunk at a time, each
 * reporting where it really began.
 */
class OfflineSpeakerLabellerTest {

    private val rate = 48_000

    /** Interleaved stereo PCM-16 for [ms] milliseconds, at a fixed level per channel. */
    private fun chunk(ms: Int, left: Double, right: Double): ShortArray {
        val frames = rate * ms / 1000
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            out[i * 2] = (left * Short.MAX_VALUE).toInt().toShort()
            out[i * 2 + 1] = (right * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    @Test
    fun `one side talking then the other gives a turn for each`() {
        val labeller = OfflineSpeakerLabeller()
        val a = chunk(500, left = 0.5, right = 0.0)
        val b = chunk(500, left = 0.0, right = 0.5)
        labeller.accept(a, a.size, channels = 2, sampleRate = rate, startMs = 0L)
        labeller.accept(b, b.size, channels = 2, sampleRate = rate, startMs = 500L)

        val turns = labeller.finish()
        assertEquals(StereoSeparation.SEPARATED, labeller.separation())
        assertEquals(listOf(SpeakerChannel.A, SpeakerChannel.B), turns.map { it.channel })
        assertEquals(0L, turns[0].startMs)
        assertEquals(500L, turns[1].startMs)
    }

    @Test
    fun `a collapsed stereo file produces no labels at all`() {
        // The 24 kbps risk: an encoder that folded the two channels into one. Better to say nothing
        // than to label every window BOTH and call it speaker detection.
        val labeller = OfflineSpeakerLabeller()
        val same = chunk(1000, left = 0.5, right = 0.5)
        labeller.accept(same, same.size, channels = 2, sampleRate = rate, startMs = 0L)

        assertEquals(StereoSeparation.MIXED, labeller.separation())
        assertEquals(emptyList<SpeakerTurn>(), labeller.finish())
    }

    @Test
    fun `a mono recording produces no labels and is not an error`() {
        // Every standalone recording is mono by design, and they all come through here.
        val labeller = OfflineSpeakerLabeller()
        val mono = ShortArray(rate) { 8_000 }
        labeller.accept(mono, mono.size, channels = 1, sampleRate = rate, startMs = 0L)

        assertEquals(StereoSeparation.UNKNOWN, labeller.separation())
        assertEquals(emptyList<SpeakerTurn>(), labeller.finish())
    }

    @Test
    fun `a silent recording produces no labels`() {
        val labeller = OfflineSpeakerLabeller()
        val quiet = chunk(1000, left = 0.0, right = 0.0)
        labeller.accept(quiet, quiet.size, channels = 2, sampleRate = rate, startMs = 0L)

        assertEquals(emptyList<SpeakerTurn>(), labeller.finish())
    }

    @Test
    fun `nothing decoded gives nothing, without throwing`() {
        assertEquals(emptyList<SpeakerTurn>(), OfflineSpeakerLabeller().finish())
    }

    @Test
    fun `a speaker talking across a chunk seam is one turn, not two`() {
        val labeller = OfflineSpeakerLabeller()
        val first = chunk(400, left = 0.5, right = 0.0)
        val second = chunk(400, left = 0.5, right = 0.0)
        labeller.accept(first, first.size, channels = 2, sampleRate = rate, startMs = 0L)
        labeller.accept(second, second.size, channels = 2, sampleRate = rate, startMs = 400L)

        assertEquals(1, labeller.finish().size)
    }

    @Test
    fun `a malformed chunk is ignored rather than throwing mid-transcription`() {
        // This runs inside a transcription. Anything it throws would take the transcript with it, and
        // a transcript is worth far more than its speaker labels.
        val labeller = OfflineSpeakerLabeller()
        val good = chunk(500, left = 0.5, right = 0.0)
        labeller.accept(ShortArray(0), 0, channels = 2, sampleRate = rate, startMs = 0L)
        labeller.accept(good, good.size, channels = 2, sampleRate = 0, startMs = 0L)
        labeller.accept(good, -1, channels = 2, sampleRate = rate, startMs = 0L)
        labeller.accept(good, good.size, channels = 2, sampleRate = rate, startMs = 0L)

        assertTrue(labeller.finish().isNotEmpty())
    }
}
