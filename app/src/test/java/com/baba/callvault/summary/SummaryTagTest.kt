/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag that says which recording a summary job belongs to, both ways round.
 *
 * It is the only durable link between a run and a call — WorkManager clears a worker's progress the
 * moment it finishes — and the Summaries page now reads it for a whole list at once. A name that
 * failed to come back out would put a row under the wrong heading, or lose it entirely, in silence.
 */
class SummaryTagTest {

    @Test
    fun `a name survives the round trip`() {
        val name = "20260916_120000_in_Dad.ogg"

        assertEquals(name, SummaryScheduler.displayNameOfTag(SummaryScheduler.tagFor(name)))
    }

    @Test
    fun `a name with a colon in it survives too`() {
        // The prefix ends in a colon, so a name containing one is the case a naive split would get
        // wrong. Contact labels are user data and can hold anything.
        val name = "20260916_120000_in_Meeting: budget.m4a"

        assertEquals(name, SummaryScheduler.displayNameOfTag(SummaryScheduler.tagFor(name)))
    }

    @Test
    fun `WorkManager's own tags are not recordings`() {
        // Every job carries the worker's class name and the unique-work name besides ours, and a
        // caller reading tags has to be able to tell them apart.
        assertNull(SummaryScheduler.displayNameOfTag("com.baba.callvault.summary.SummaryWorker"))
        assertNull(SummaryScheduler.displayNameOfTag(SummaryScheduler.WORK_NAME))
    }

    @Test
    fun `a transcription job is not a summary job`() {
        assertNull(SummaryScheduler.displayNameOfTag("cv_transcribe:a.ogg"))
    }

    @Test
    fun `the bare prefix names no recording`() {
        assertNull(SummaryScheduler.displayNameOfTag("cv_summary:"))
    }
}
