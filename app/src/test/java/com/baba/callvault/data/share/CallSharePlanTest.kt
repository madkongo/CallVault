/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one Share sheet: what is offered, what starts ticked, and how it travels. */
class CallSharePlanTest {

    private fun call(audio: Boolean = true, transcript: Boolean = false, summary: Boolean = false) =
        ShareableCall("Mom_20261106_150812.ogg", "Mom", if (audio) "content://a" else null, transcript, summary)

    @Test
    fun `only what the call has is offered`() {
        assertEquals(setOf(SharePart.RECORDING, SharePart.TRANSCRIPT), CallSharePlan.available(listOf(call(transcript = true))))
    }

    @Test
    fun `a selection offers a part when any call has it`() {
        val parts = CallSharePlan.available(listOf(call(), call(audio = false, summary = true)))
        assertEquals(setOf(SharePart.RECORDING, SharePart.SUMMARY), parts)
    }

    @Test
    fun `the page's own part starts ticked`() {
        val all = SharePart.entries.toSet()
        assertEquals(setOf(SharePart.TRANSCRIPT), CallSharePlan.initialTicks(SharePart.TRANSCRIPT, all))
        assertEquals(setOf(SharePart.RECORDING), CallSharePlan.initialTicks(SharePart.RECORDING, all))
    }

    @Test
    fun `a transcript whose audio is gone starts on the transcript, not on nothing`() {
        val available = CallSharePlan.available(listOf(call(audio = false, transcript = true)))
        assertEquals(setOf(SharePart.TRANSCRIPT), CallSharePlan.initialTicks(SharePart.RECORDING, available))
    }

    @Test
    fun `with the recording it goes as files, without it as a message`() {
        assertTrue(CallSharePlan.asFiles(setOf(SharePart.RECORDING, SharePart.TRANSCRIPT)))
        assertFalse(CallSharePlan.asFiles(setOf(SharePart.TRANSCRIPT, SharePart.SUMMARY)))
    }

    @Test
    fun `text files are named after the recording`() {
        assertEquals("Mom_20261106_150812 — transcript.txt", CallSharePlan.textFileName("Mom_20261106_150812.ogg", "transcript"))
    }

    @Test
    fun `both texts go summary first, under headings`() {
        assertEquals(
            "Summary\n\nShort.\n\nTranscript\n\nLong text.",
            CallSharePlan.combinedText("Short.", "Long text.", "Summary", "Transcript"),
        )
    }

    @Test
    fun `one text goes without a heading, as before`() {
        assertEquals("Long text.", CallSharePlan.combinedText(null, "Long text.", "Summary", "Transcript"))
        assertNull(CallSharePlan.combinedText(null, " ", "Summary", "Transcript"))
    }
}
