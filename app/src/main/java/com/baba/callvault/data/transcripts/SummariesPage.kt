/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import androidx.work.WorkInfo
import com.baba.callvault.summary.SummaryScheduler

/**
 * What the Summaries page shows, decided away from the screen that draws it.
 *
 * Pure, for the reason [TranscriptsPage] is: every wrong answer here still renders perfectly. A row
 * under the wrong heading, or a count that disagrees with the hub card the user has just tapped, are
 * both silent — nothing crashes and nothing is logged.
 *
 * ## Why this one knows about WorkManager and [TranscriptsPage] does not
 *
 * A transcript has a row of its own from the moment it is asked for, so QUEUED, RUNNING and FAILED
 * are all readable from the database. **A summary has no row until it succeeds.** So the only place
 * that knows a summary is being written, or that one failed, is WorkManager — which makes the work
 * infos an input to the page rather than an implementation detail of a card.
 *
 * One flow of them serves the whole list. Asking
 * [com.baba.callvault.ui.common.rememberSummaryState] per row would spawn one WorkManager observer
 * and two database observers for every visible line of a list that can run to thousands.
 */
object SummariesPage {

    /**
     * One run of the summariser, as this page needs it.
     *
     * [percent] is only meaningful while the run is in flight — WorkManager clears a worker's
     * progress the moment it finishes, which is the trap [SummaryScheduler.tagFor] records.
     */
    data class Job(
        val displayName: String,
        val state: WorkInfo.State,
        val percent: Int = 0,
    )

    /** A summary being written right now, with how far through it is. */
    data class Working(val displayName: String, val percent: Int)

    /**
     * The page's three groups, in the order they are drawn.
     *
     * @param working Being written, or waiting to be. Nowhere else in the app says so: unlike a
     *                transcription there is no pill beside the title and no row state to read.
     * @param failed  The last attempt produced nothing. Kept visible because nothing retries a
     *                failed summary, so without a heading it is invisible for ever.
     * @param ready   **Exactly the rows in the summaries table, all of them, in the order given.**
     *                That is the invariant this type exists for: the hub's card is a `COUNT(*)` over
     *                the same table and cannot see the recordings catalog, so anything dropped here
     *                is a number the user can catch the app lying about by counting.
     */
    data class Groups(
        val working: List<Working>,
        val failed: List<String>,
        val ready: List<String>,
    ) {
        /** Nothing at all to show — the page's empty state, not merely "nothing readable". */
        val isEmpty: Boolean get() = working.isEmpty() && failed.isEmpty() && ready.isEmpty()
    }

    /**
     * Groups [summarised] — every recording with a stored summary, newest first — against [jobs],
     * every run the queue still remembers.
     *
     * **A rewrite appears twice, and that is deliberate.** Asking for a summary to be written again
     * leaves the old one in place and readable until the new one lands, so the recording is honestly
     * both "being summarised" and "summarised": it is under the heading at the top *and* in the list
     * below. Removing it from the list for those ninety seconds would drop the page's count one
     * below the hub card the user had just tapped, which is the defect this whole page was rebuilt
     * to avoid.
     *
     * Input order is preserved inside each group, so the caller's ORDER BY is the page's order.
     */
    fun group(summarised: List<String>, jobs: List<Job>): Groups {
        val stored = summarised.toSet()
        val working = mutableListOf<Working>()
        val failed = mutableListOf<String>()

        jobs.groupBy { it.displayName }.forEach { (displayName, runs) ->
            val current = interesting(runs) ?: return@forEach
            when {
                !current.state.isFinished -> working += Working(displayName, current.percent)

                // A failed REWRITE is not listed: the earlier summary survived it, is still
                // readable, and is already a row in `ready` below. Saying "didn't finish" about it
                // as well would tell the user there is nothing there to open.
                current.state == WorkInfo.State.FAILED && displayName !in stored ->
                    failed += displayName

                // SUCCEEDED with no stored summary means the recording was deleted after its
                // summary was written — the cascade took the row. Nothing to show and nothing
                // wrong: dropping it is the whole of the correct behaviour.
                else -> Unit
            }
        }

        return Groups(working = working, failed = failed, ready = summarised)
    }

    /**
     * Which of one recording's runs describes its state.
     *
     * **A live job always wins**, the same rule
     * [com.baba.callvault.ui.common.indexOfInteresting] follows and for the same measured reason:
     * every run for one recording carries the same tag, so after the first rewrite there are
     * several, and taking the last of them picked an arbitrary one. Nothing else is finished-aware
     * here — BLOCKED counts as live, because work appended to an existing chain starts BLOCKED and
     * reading that as "nothing is happening" is exactly how a running summary went unreported once.
     */
    private fun interesting(runs: List<Job>): Job? =
        runs.firstOrNull { !it.state.isFinished } ?: runs.lastOrNull()

    /**
     * The queue's work infos as this page's [Job]s, dropping anything that is not a summary run.
     *
     * Not unit-tested, and deliberately three lines for that reason: `WorkInfo` cannot be built
     * outside WorkManager, so everything with a decision in it — which tag names a recording, which
     * run counts, which group it lands in — lives in [group] and [SummaryScheduler.displayNameOfTag]
     * where it can be.
     */
    fun jobsOf(infos: List<WorkInfo>): List<Job> = infos.mapNotNull { info ->
        val displayName = info.tags.firstNotNullOfOrNull(SummaryScheduler::displayNameOfTag)
        displayName?.let {
            Job(it, info.state, SummaryScheduler.percentOf(info.progress) ?: 0)
        }
    }
}
