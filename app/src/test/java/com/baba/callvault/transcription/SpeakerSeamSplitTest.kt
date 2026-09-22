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
import com.baba.callvault.transcription.SpeakerSeamSplit.Word
import com.baba.callvault.transcription.SpeechGapSnap.Speech
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A line both people share is cut at the pause between them.
 *
 * The numbers in the first test are the OP9's 12:25 call of 2026-09-20, as measured: B talks 4.9–8.7 s,
 * A talks 11–15 s, the VAD kept one stretch for each, and whisper — in Hebrew — returned a single segment
 * 4.50 → 15.20 s whose tokens sit on the VAD's compressed timeline, 0.01 → 4.74 s and 5.07 → 8.53 s.
 */
class SpeakerSeamSplitTest {

    private val speech = listOf(Speech(4_500, 9_100), Speech(10_600, 15_200))
    private val turns = listOf(
        SpeakerTurn(0, SpeakerChannel.SILENCE),
        SpeakerTurn(4_900, SpeakerChannel.B),
        SpeakerTurn(8_700, SpeakerChannel.SILENCE),
        SpeakerTurn(11_000, SpeakerChannel.A),
        SpeakerTurn(15_000, SpeakerChannel.SILENCE),
    )
    private val shared = TranscriptSegment(4_500, 15_200, "בדיקה, זה 1+12, בדיקה, זה 1+9.")
    private val words = listOf(
        Word("בדיקה,", 10), Word("זה", 2_820), Word("1+12,", 3_180),
        Word("בדיקה,", 5_070), Word("זה", 6_920), Word("1+9.", 7_230),
    )

    @Test
    fun `cuts a line at the pause where one speaker hands over to the other`() {
        val pieces = SpeakerSeamSplit.split(shared, words, speech, turns)

        assertEquals(listOf("בדיקה, זה 1+12,", "בדיקה, זה 1+9."), pieces.map { it.text })
    }

    @Test
    fun `each piece sits inside the stretch it was spoken in, so the labeller can name it`() {
        val pieces = SpeakerSeamSplit.split(shared, words, speech, turns)

        assertEquals(listOf(4_500L to 9_100L, 10_600L to 15_200L), pieces.map { it.startMs to it.endMs })
        assertEquals(
            listOf("B", "A"),
            SpeakerLabeller.labelAll(turns, pieces.map { it.startMs to it.endMs }),
        )
    }

    @Test
    fun `leaves a line alone when the same person spoke on both sides of the pause`() {
        // Someone who stops to think is not two people. This is the case that would shred transcripts.
        val oneSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.B))

        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, words, speech, oneSpeaker))
    }

    @Test
    fun `leaves a line alone when either side of the pause cannot be named`() {
        val unknownAfter = listOf(
            SpeakerTurn(0, SpeakerChannel.B),
            SpeakerTurn(10_000, SpeakerChannel.BOTH),
        )

        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, words, speech, unknownAfter))
    }

    @Test
    fun `leaves a line alone when there is nothing to cut it with`() {
        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, emptyList(), speech, turns))
        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, words, emptyList(), turns))
        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, words, speech, emptyList()))
        assertEquals(listOf(shared), SpeakerSeamSplit.split(shared, words, speech.take(1), turns))
    }

    @Test
    fun `counts whisper's bridge between stretches when placing a word`() {
        // Stretch 1 is 4.6 s long, so stretch 2 begins at 4.6 s + the 100 ms whisper puts between kept
        // stretches. A word at 4.65 s is still in the bridge and belongs with what came before it.
        val onTheSeam = listOf(Word("first", 10), Word("late", 4_650), Word("second", 5_070))

        val pieces = SpeakerSeamSplit.split(shared, onTheSeam, speech, turns)

        assertEquals(listOf("first late", "second"), pieces.map { it.text })
    }

    @Test
    fun `a word timed past the last stretch lands in the last piece rather than nowhere`() {
        // Both stretches together are 9.2 s of compressed time; whisper's token times can overshoot it.
        val overshoot = listOf(Word("first", 10), Word("second", 5_070), Word("stray", 9_900))

        val pieces = SpeakerSeamSplit.split(shared, overshoot, speech, turns)

        assertEquals(listOf("first", "second stray"), pieces.map { it.text })
    }

    @Test
    fun `cuts more than once when the speakers swap more than once`() {
        val three = listOf(Speech(1_000, 3_000), Speech(4_000, 6_000), Speech(7_000, 9_000))
        val swapping = listOf(
            SpeakerTurn(0, SpeakerChannel.A), SpeakerTurn(3_500, SpeakerChannel.B), SpeakerTurn(6_500, SpeakerChannel.A),
        )
        val line = TranscriptSegment(1_000, 9_000, "one two three")
        val spoken = listOf(Word("one", 100), Word("two", 2_200), Word("three", 4_300))

        val pieces = SpeakerSeamSplit.split(line, spoken, three, swapping)

        assertEquals(listOf("one", "two", "three"), pieces.map { it.text })
    }

    @Test
    fun `the engine's half cuts at every pause, knowing nothing about speakers`() {
        // The engine decodes chunk by chunk, before the turns exist, so it can only offer candidates.
        val offered = SpeakerSeamSplit.atSeams(shared, words, speech)

        assertEquals(listOf("בדיקה, זה 1+12,", "בדיקה, זה 1+9."), offered.parts.map { it.text })
        assertEquals(shared.text, offered.text)
    }

    @Test
    fun `the runner's half joins the candidates one speaker held back together`() {
        val offered = SpeakerSeamSplit.atSeams(shared, words, speech)
        val oneSpeaker = listOf(SpeakerTurn(0, SpeakerChannel.B))

        assertEquals(listOf(shared.text), SpeakerSeamSplit.joinSameSpeaker(offered, oneSpeaker).map { it.text })
        assertEquals(2, SpeakerSeamSplit.joinSameSpeaker(offered, turns).size)
    }

    @Test
    fun `what comes out of the runner's half carries no leftover candidates`() {
        val offered = SpeakerSeamSplit.atSeams(shared, words, speech)

        assertEquals(emptyList<TranscriptSegment>(), SpeakerSeamSplit.joinSameSpeaker(offered, turns).flatMap { it.parts })
        assertEquals(emptyList<TranscriptSegment>(), SpeakerSeamSplit.joinSameSpeaker(offered, emptyList()).flatMap { it.parts })
    }

    @Test
    fun `reads the native layer's words, one per line as milliseconds and text`() {
        assertEquals(
            listOf(Word("בדיקה,", 10), Word("זה", 2_820)),
            SpeakerSeamSplit.parseWords("10\tבדיקה,\n2820\tזה\n"),
        )
        assertEquals(emptyList<Word>(), SpeakerSeamSplit.parseWords(""))
        assertEquals(listOf(Word("ok", 5)), SpeakerSeamSplit.parseWords("garbage\n5\tok\nx\ty\n"))
    }

    @Test
    fun `a part nobody can be named for does not hide the handover around it`() {
        // B, then a stretch of double-talk, then A. Comparing only neighbours sees "B vs nobody" and
        // "nobody vs A", cuts nowhere, and hands B's words and A's words back as one shared line.
        val three = listOf(Speech(1_000, 3_000), Speech(4_000, 6_000), Speech(7_000, 9_000))
        val withDoubleTalk = listOf(
            SpeakerTurn(0, SpeakerChannel.B), SpeakerTurn(3_500, SpeakerChannel.BOTH), SpeakerTurn(6_500, SpeakerChannel.A),
        )
        val line = TranscriptSegment(1_000, 9_000, "one two three")
        val spoken = listOf(Word("one", 100), Word("two", 2_200), Word("three", 4_300))

        val pieces = SpeakerSeamSplit.split(line, spoken, three, withDoubleTalk)

        assertEquals(listOf("one two", "three"), pieces.map { it.text })
    }
}
