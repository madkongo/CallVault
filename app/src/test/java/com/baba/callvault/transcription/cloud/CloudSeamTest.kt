/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.transcription.ChunkPlan
import com.baba.callvault.transcription.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between cloud passes, with the numbers measured on the OP9 on 2026-10-29 (9.4-min Hebrew
 * call, Groq): pass 1 ended "…זאת אומרת, לוינטר כ" at 294180–300020, cut at the chunk end, and pass 2
 * began "לוינטר כבר היום, לפי השכרים," at 299193–300952.
 */
class CloudSeamTest {

    private val plan = ChunkPlan.plan(totalMs = 564_000L, overlapMs = CloudSeam.CLOUD_OVERLAP_MS)

    private val before = TranscriptSegment(288_000, 294_180, "ומה שקרה אז")
    private val cutTail = TranscriptSegment(294_180, 300_020, "זאת אומרת, לוינטר כ")
    private val repeatedHead = TranscriptSegment(299_193, 300_952, "לוינטר כבר היום, לפי השכרים,")
    private val after = TranscriptSegment(301_053, 305_000, "וזה הכול")

    @Test
    fun `the plan gives the second pass a thirty-second run-up`() {
        assertEquals(2, plan.size)
        assertEquals(300_000L, plan[0].endMs)
        assertEquals(270_000L, plan[1].decodeFromMs)
        assertEquals(300_000L, plan[1].keepFromMs)
    }

    @Test
    fun `the measured seam keeps no repeated and no cut words`() {
        // Pass 2 with its run-up heard the cut sentence whole, plus a line pass 1 already has.
        val runUpRepeat = TranscriptSegment(285_100, 294_150, "ומה שקרה אז")
        val whole = TranscriptSegment(294_200, 299_150, "זאת אומרת,")
        val joined = CloudSeam.join(
            listOf(
                CloudSeam.Pass(plan[0], listOf(before, cutTail)),
                CloudSeam.Pass(plan[1], listOf(runUpRepeat, whole, repeatedHead, after)),
            )
        )
        assertEquals(listOf(before, whole, repeatedHead, after), joined)
        assertFalse("the cut tail survived", cutTail in joined)
        assertEquals("a line was repeated", joined.map { it.text }.distinct().size, joined.size)
        assertTrue("lines out of order", joined.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }

    @Test
    fun `exactly the measured segments give one copy of the seam words`() {
        val joined = CloudSeam.join(
            listOf(
                CloudSeam.Pass(plan[0], listOf(before, cutTail)),
                CloudSeam.Pass(plan[1], listOf(repeatedHead, after)),
            )
        )
        assertEquals(1, joined.count { it.text.contains("לוינטר") })
        assertNull(joined.firstOrNull { it.text.endsWith("לוינטר כ") })
    }

    @Test
    fun `a tail is kept when the next pass heard nothing`() {
        val joined = CloudSeam.join(listOf(CloudSeam.Pass(plan[0], listOf(before, cutTail)), CloudSeam.Pass(plan[1], emptyList())))
        assertEquals(listOf(before, cutTail), joined)
    }

    @Test
    fun `a segment ending well before the seam is not touched`() {
        val early = TranscriptSegment(280_000, 290_000, "earlier")
        val next = TranscriptSegment(300_500, 302_000, "next")
        val joined = CloudSeam.join(listOf(CloudSeam.Pass(plan[0], listOf(early)), CloudSeam.Pass(plan[1], listOf(next))))
        assertEquals(listOf(early, next), joined)
    }

    @Test
    fun `a single pass is returned as it came`() {
        val one = ChunkPlan.plan(60_000L)
        assertEquals(listOf(before), CloudSeam.join(listOf(CloudSeam.Pass(one[0], listOf(before)))))
    }
}
