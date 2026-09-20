/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stitching per-chunk speaker turns into one timeline (issue #38).
 *
 * The live detector counts its own windows from zero, which is right when it sees a call once from the
 * start. Offline it sees a call in **chunks**, each decoded separately, and each chunk's detector starts
 * its clock at zero again — so every chunk's turns have to be moved to where that chunk really began.
 *
 * Chunks are not tidy: `AudioDecoder.decodeRange` seeks to the previous sync point, so a chunk can start
 * *earlier* than it was asked for and overlap the one before. Left alone that produces duplicate and
 * out-of-order turns, and a timeline that walks backwards.
 */
class SpeakerTurnMergeTest {

    private fun turn(ms: Long, ch: SpeakerChannel) = SpeakerTurn(startMs = ms, channel = ch)

    @Test
    fun `a single chunk at the start is unchanged`() {
        val merged = SpeakerTurnMerge.stitch(
            listOf(0L to listOf(turn(0, SpeakerChannel.A), turn(500, SpeakerChannel.B))),
        )
        assertEquals(listOf(turn(0, SpeakerChannel.A), turn(500, SpeakerChannel.B)), merged)
    }

    @Test
    fun `a later chunk's turns are moved to where that chunk really began`() {
        val merged = SpeakerTurnMerge.stitch(
            listOf(
                0L to listOf(turn(0, SpeakerChannel.A)),
                30_000L to listOf(turn(0, SpeakerChannel.B), turn(400, SpeakerChannel.A)),
            ),
        )
        assertEquals(
            listOf(turn(0, SpeakerChannel.A), turn(30_000, SpeakerChannel.B), turn(30_400, SpeakerChannel.A)),
            merged,
        )
    }

    @Test
    fun `a run of the same speaker across a chunk boundary becomes one turn`() {
        // A speaks through the seam. Two chunks each report A; the timeline must not say A twice, or
        // the UI draws a turn change where the speaker never changed.
        val merged = SpeakerTurnMerge.stitch(
            listOf(
                0L to listOf(turn(0, SpeakerChannel.A)),
                30_000L to listOf(turn(0, SpeakerChannel.A), turn(1_000, SpeakerChannel.B)),
            ),
        )
        assertEquals(listOf(turn(0, SpeakerChannel.A), turn(31_000, SpeakerChannel.B)), merged)
    }

    @Test
    fun `an overlapping chunk does not push the timeline backwards`() {
        // decodeRange seeks to the previous sync point, so chunk 2 can really start before chunk 1
        // ended. Anything at or before what we already have is dropped rather than re-inserted.
        val merged = SpeakerTurnMerge.stitch(
            listOf(
                0L to listOf(turn(0, SpeakerChannel.A), turn(2_000, SpeakerChannel.B)),
                1_500L to listOf(turn(0, SpeakerChannel.B), turn(1_000, SpeakerChannel.A)),
            ),
        )
        // The re-heard B at 1500 is behind the B already at 2000, so it goes; A at 2500 is new.
        assertEquals(
            listOf(turn(0, SpeakerChannel.A), turn(2_000, SpeakerChannel.B), turn(2_500, SpeakerChannel.A)),
            merged,
        )
    }

    @Test
    fun `chunks handed over out of order still produce an ordered timeline`() {
        val merged = SpeakerTurnMerge.stitch(
            listOf(
                30_000L to listOf(turn(0, SpeakerChannel.B)),
                0L to listOf(turn(0, SpeakerChannel.A)),
            ),
        )
        assertEquals(listOf(turn(0, SpeakerChannel.A), turn(30_000, SpeakerChannel.B)), merged)
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertEquals(emptyList<SpeakerTurn>(), SpeakerTurnMerge.stitch(emptyList()))
        assertEquals(emptyList<SpeakerTurn>(), SpeakerTurnMerge.stitch(listOf(0L to emptyList())))
    }

    @Test
    fun `silence is kept, because it is what a pause looks like`() {
        // SILENCE is a real answer from the detector and the reading view uses it to space the turns.
        val merged = SpeakerTurnMerge.stitch(
            listOf(0L to listOf(turn(0, SpeakerChannel.A), turn(300, SpeakerChannel.SILENCE), turn(900, SpeakerChannel.B))),
        )
        assertEquals(3, merged.size)
        assertEquals(SpeakerChannel.SILENCE, merged[1].channel)
    }
}
