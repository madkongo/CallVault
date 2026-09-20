/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.server.speakers.SpeakerTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerLabellerTest {

    @Test
    fun `labels a segment that falls entirely inside one side's turn`() {
        // Arrange
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.A))

        // Act
        val label = SpeakerLabeller.label(turns, startMs = 1_000, endMs = 3_000)

        // Assert
        assertEquals("A", label)
    }

    @Test
    fun `labels by which side held most of the segment, not by which came first`() {
        // Whisper cuts on pauses, not on turn boundaries, so a segment routinely straddles a
        // handover. The side that did most of the talking is the one that said the words.
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(1_200, SpeakerChannel.B)
        )

        assertEquals("B", SpeakerLabeller.label(turns, startMs = 1_000, endMs = 5_000))
    }

    @Test
    fun `returns null when the two sides split the segment evenly`() {
        // A confident wrong attribution is worse than none: it puts one person's words in the
        // other's mouth, on a record of a real conversation.
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(1_000, SpeakerChannel.B)
        )

        assertNull(SpeakerLabeller.label(turns, startMs = 0, endMs = 2_000))
    }

    @Test
    fun `returns null when the segment is mostly double-talk`() {
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.BOTH))

        assertNull(SpeakerLabeller.label(turns, startMs = 0, endMs = 2_000))
    }

    @Test
    fun `names the only side that ever spoke alone, even when its voice bled into the other channel`() {
        // Measured on the OP9 on 2026-09-20, a Shizuku call: one person spoke for 8 s and nobody else
        // did. Their voice also reached the other channel at about half level, so 2.1 s of it read as
        // BOTH. 3.0 s of 5.1 s voiced is 59% — under two thirds — and the segment went unlabelled with
        // the other side holding exactly nothing. Bleed is not a second speaker.
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(3_000, SpeakerChannel.BOTH)
        )

        assertEquals("A", SpeakerLabeller.label(turns, 0, 5_100))
    }

    @Test
    fun `still returns null when one side's lead is a sliver beside the double-talk`() {
        // The relaxation above must not turn a segment that is nearly all double-talk into a name.
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(1_000, SpeakerChannel.BOTH)
        )

        assertNull(SpeakerLabeller.label(turns, 0, 10_000))
    }

    @Test
    fun `still returns null when both sides spoke alone and neither clearly led`() {
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(3_000, SpeakerChannel.B),
            SpeakerTurn(5_000, SpeakerChannel.BOTH)
        )

        assertNull(SpeakerLabeller.label(turns, 0, 7_000))
    }

    @Test
    fun `ignores silence, which is most of a call and belongs to nobody`() {
        // A segment surrounded by silence is still attributable: only the voiced part counts.
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.SILENCE),
            SpeakerTurn(1_000, SpeakerChannel.A),
            SpeakerTurn(1_400, SpeakerChannel.SILENCE)
        )

        assertEquals("A", SpeakerLabeller.label(turns, startMs = 0, endMs = 5_000))
    }

    @Test
    fun `returns null when nothing was voiced in the segment`() {
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.SILENCE))

        assertNull(SpeakerLabeller.label(turns, startMs = 0, endMs = 2_000))
    }

    @Test
    fun `treats the last turn as running to the end of the recording`() {
        // Turns are contiguous and only starts are stored, so the final one has no end of its own.
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.B))

        assertEquals("B", SpeakerLabeller.label(turns, startMs = 600_000, endMs = 605_000))
    }

    @Test
    fun `returns null when there are no turns at all`() {
        // Mono capture, an older daemon, or a recording made before any of this existed.
        assertNull(SpeakerLabeller.label(emptyList(), startMs = 0, endMs = 2_000))
    }

    @Test
    fun `returns null for a segment with no duration rather than dividing by zero`() {
        val turns = listOf(SpeakerTurn(0, SpeakerChannel.A))

        assertNull(SpeakerLabeller.label(turns, startMs = 1_000, endMs = 1_000))
    }

    @Test
    fun `labels every segment of a transcript in one pass`() {
        val turns = listOf(
            SpeakerTurn(0, SpeakerChannel.A),
            SpeakerTurn(2_000, SpeakerChannel.B)
        )

        val labels = SpeakerLabeller.labelAll(
            turns,
            listOf(0L to 1_500L, 2_000L to 3_500L)
        )

        assertEquals(listOf("A", "B"), labels)
    }

    @Test
    fun `labels nothing when the turns cannot be decoded`() {
        val labels = SpeakerLabeller.labelAll(
            SpeakerLabeller.decode("not a turn list"),
            listOf(0L to 1_500L)
        )

        assertEquals(listOf(null), labels)
    }
}
