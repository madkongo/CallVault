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
import org.junit.Test

/**
 * One row per turn, not one row per sentence.
 *
 * The layout in the first tests is the OP9's 11:52 call of 2026-09-20: whisper returned seven short
 * sentences for two turns, so the screen showed the contact three times and "You" twice — and the last two
 * rows had no name at all, because that speaker's voice bled into the other channel for most of them.
 * The maintainer: "it would have made much more sense that 0:04-0:08 is one line and 0:15-0:22 another".
 */
class SpeakerTurnLinesTest {

    private val turns = listOf(
        SpeakerTurn(0, SpeakerChannel.SILENCE),
        SpeakerTurn(4_000, SpeakerChannel.B),
        SpeakerTurn(13_000, SpeakerChannel.SILENCE),
        SpeakerTurn(15_000, SpeakerChannel.A),
        // From here the speaker's own voice reaches the other channel too, and reads as double-talk.
        SpeakerTurn(19_000, SpeakerChannel.BOTH),
        SpeakerTurn(23_000, SpeakerChannel.SILENCE),
    )
    private val sentences = listOf(
        TranscriptSegment(4_000, 6_000, "בדיקה, בדיקה, בדיקה."),
        TranscriptSegment(6_000, 8_000, "זה 1. פלוס 12."),
        TranscriptSegment(8_000, 13_000, "אחת, שתיים, שלוש, ארבע, חמש."),
        TranscriptSegment(15_000, 17_000, "בדיקה, בדיקה, בדיקה."),
        TranscriptSegment(17_000, 19_000, "זה 1. פלוס תשע,"),
        TranscriptSegment(19_000, 22_000, "שש, שבע, שמונה, תשע."),
        TranscriptSegment(22_000, 23_000, "עשר."),
    )

    @Test
    fun `the sentences of one turn become one row`() {
        val rows = SpeakerTurnLines.merge(sentences, turns)

        assertEquals(listOf(4_000L to 13_000L, 15_000L to 23_000L), rows.map { it.startMs to it.endMs })
        assertEquals("בדיקה, בדיקה, בדיקה. זה 1. פלוס 12. אחת, שתיים, שלוש, ארבע, חמש.", rows[0].text)
    }

    @Test
    fun `a row nobody could be named for joins the speaker before it, and the joined row gets a name`() {
        val rows = SpeakerTurnLines.merge(sentences, turns)

        assertEquals(listOf("B", "A"), SpeakerLabeller.labelAll(turns, rows.map { it.startMs to it.endMs }))
    }

    @Test
    fun `a long pause starts a new row even when the same person carries on`() {
        val sameSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.A))
        val paused = listOf(TranscriptSegment(0, 2_000, "first thought."), TranscriptSegment(5_000, 7_000, "second thought."))

        assertEquals(2, SpeakerTurnLines.merge(paused, sameSpeaker).size)
    }

    @Test
    fun `a monologue is not poured into one endless row`() {
        // A row is also what a tap seeks to. Thirty seconds of it is a paragraph; five minutes is a wall.
        val sameSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.A))
        val monologue = (0 until 20).map { TranscriptSegment(it * 5_000L, (it + 1) * 5_000L, "sentence $it.") }

        val rows = SpeakerTurnLines.merge(monologue, sameSpeaker)

        assertEquals(true, rows.size > 1)
        assertEquals(true, rows.all { it.endMs - it.startMs <= SpeakerTurnLines.MAX_ROW_MS })
        assertEquals(monologue.joinToString(" ") { it.text }, rows.joinToString(" ") { it.text })
    }

    @Test
    fun `a row may reach the limit exactly, and one sentence past it starts the next row`() {
        val sameSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.A))
        val limit = SpeakerTurnLines.MAX_ROW_MS
        val exactly = listOf(TranscriptSegment(0, limit - 1_000, "most of it."), TranscriptSegment(limit - 1_000, limit, "and the rest."))
        val over = exactly + TranscriptSegment(limit, limit + 1, "too far.")

        assertEquals(1, SpeakerTurnLines.merge(exactly, sameSpeaker).size)
        assertEquals(2, SpeakerTurnLines.merge(over, sameSpeaker).size)
    }

    @Test
    fun `a line that starts before the previous one ended is an overlap, not a pause, and is not joined`() {
        // Only a chunk seam produces this: the next chunk re-transcribes the last seconds of the one
        // before, and the segment straddling the seam starts inside them. Glued into one row, the same
        // seconds would read twice in a single line, with nothing to show where the repeat begins.
        val sameSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.A))
        val acrossTheSeam = listOf(
            TranscriptSegment(295_000, 300_000, "the end of the chunk before."),
            TranscriptSegment(297_000, 303_000, "of the chunk before, and on it goes."),
        )

        assertEquals(2, SpeakerTurnLines.merge(acrossTheSeam, sameSpeaker).size)
    }

    @Test
    fun `a call that opens with double-talk joins the first speaker who can be named`() {
        val opensInBoth = listOf(
            SpeakerTurn(0, SpeakerChannel.BOTH),
            SpeakerTurn(2_000, SpeakerChannel.A),
            SpeakerTurn(6_000, SpeakerChannel.B),
        )
        val lines = listOf(
            TranscriptSegment(0, 2_000, "hello?"),
            TranscriptSegment(2_000, 6_000, "yes, hello."),
            TranscriptSegment(6_000, 8_000, "hi there."),
        )

        val rows = SpeakerTurnLines.merge(lines, opensInBoth)

        assertEquals(listOf("hello? yes, hello.", "hi there."), rows.map { it.text })
        assertEquals("A", SpeakerLabeller.label(opensInBoth, rows[0].startMs, rows[0].endMs))
    }

    @Test
    fun `a transcript with no speaker data is left exactly as whisper wrote it`() {
        assertEquals(sentences, SpeakerTurnLines.merge(sentences, emptyList()))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<TranscriptSegment>(), SpeakerTurnLines.merge(emptyList(), turns))
    }
}
