/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the Summaries page divides what it is handed.
 *
 * Plain JUnit, no Robolectric: this is a partition of two lists, and every interesting failure — a
 * row under the wrong heading, a count one short of the hub card the user just tapped — renders
 * without complaint.
 */
class SummariesPageTest {

    private fun job(name: String, state: WorkInfo.State, percent: Int = 0) =
        SummariesPage.Job(displayName = name, state = state, percent = percent)

    @Test
    fun `every stored summary is listed, so the page and the hub card cannot disagree`() {
        val groups = SummariesPage.group(
            summarised = listOf("a.ogg", "b.ogg", "c.ogg"),
            jobs = emptyList(),
        )

        // The hub card is a COUNT(*) over the same table. This is the number printed beside the
        // same list, and it has to be the same set rather than two that happen to agree.
        assertEquals(listOf("a.ogg", "b.ogg", "c.ogg"), groups.ready)
    }

    @Test
    fun `the order the query returned is the order the page draws`() {
        val groups = SummariesPage.group(
            summarised = listOf("newest.ogg", "older.ogg"),
            jobs = emptyList(),
        )

        assertEquals(listOf("newest.ogg", "older.ogg"), groups.ready)
    }

    @Test
    fun `a first summary being written is listed as working, with how far through it is`() {
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.RUNNING, percent = 42)),
        )

        assertEquals(listOf("a.ogg"), groups.working.map { it.displayName })
        assertEquals(42, groups.working.single().percent)
        assertTrue(groups.ready.isEmpty())
    }

    @Test
    fun `a summary only queued still counts as working`() {
        // The seconds after a tap, before the worker starts. Nothing else in the app says so: a
        // summary has no database row until it succeeds.
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.ENQUEUED)),
        )

        assertEquals(listOf("a.ogg"), groups.working.map { it.displayName })
    }

    @Test
    fun `work waiting behind another run counts as working`() {
        // BLOCKED is what a job appended to an existing unique-work chain starts as, and reading it
        // as "nothing is happening" is how a running summary went unreported once already.
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.BLOCKED)),
        )

        assertEquals(listOf("a.ogg"), groups.working.map { it.displayName })
    }

    @Test
    fun `a rewrite is both being summarised and summarised`() {
        // The old summary is still there and still readable while the new one is written, so the
        // row appears under the heading AND in the list. Dropping it from the list for those
        // ninety seconds would put the page one below the hub card that was just tapped.
        val groups = SummariesPage.group(
            summarised = listOf("a.ogg"),
            jobs = listOf(
                job("a.ogg", WorkInfo.State.SUCCEEDED),
                job("a.ogg", WorkInfo.State.RUNNING, percent = 10),
            ),
        )

        assertEquals(listOf("a.ogg"), groups.working.map { it.displayName })
        assertEquals(listOf("a.ogg"), groups.ready)
    }

    @Test
    fun `a live run wins over a finished one for the same recording`() {
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(
                job("a.ogg", WorkInfo.State.FAILED),
                job("a.ogg", WorkInfo.State.RUNNING, percent = 7),
            ),
        )

        assertEquals(listOf("a.ogg"), groups.working.map { it.displayName })
        assertTrue(groups.failed.isEmpty())
    }

    @Test
    fun `a summary that failed with nothing to show for it is listed as failed`() {
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.FAILED)),
        )

        assertEquals(listOf("a.ogg"), groups.failed)
    }

    @Test
    fun `a failed rewrite is not listed as failed, because the earlier summary survived it`() {
        val groups = SummariesPage.group(
            summarised = listOf("a.ogg"),
            jobs = listOf(
                job("a.ogg", WorkInfo.State.SUCCEEDED),
                job("a.ogg", WorkInfo.State.FAILED),
            ),
        )

        assertTrue(groups.failed.isEmpty())
        assertEquals(listOf("a.ogg"), groups.ready)
    }

    @Test
    fun `a finished run whose summary is gone is dropped`() {
        // The recording was deleted after its summary was written and the cascade took the row.
        // Nothing to show, and nothing wrong.
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.SUCCEEDED)),
        )

        assertTrue(groups.working.isEmpty())
        assertTrue(groups.failed.isEmpty())
        assertTrue(groups.ready.isEmpty())
    }

    @Test
    fun `a cancelled run leaves nothing behind`() {
        // Stop was tapped. There is no summary and nothing failed, so there is nothing to say.
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.CANCELLED)),
        )

        assertTrue(groups.isEmpty)
    }

    @Test
    fun `nothing anywhere is the empty page`() {
        assertTrue(SummariesPage.group(emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun `a page with only a failed run is not empty`() {
        // The heading is the only place that failure is ever mentioned, so an empty state over it
        // would hide the one thing the page had to say.
        val groups = SummariesPage.group(
            summarised = emptyList(),
            jobs = listOf(job("a.ogg", WorkInfo.State.FAILED)),
        )

        assertFalse(groups.isEmpty)
    }
}
