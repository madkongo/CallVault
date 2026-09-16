/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState

/**
 * What the Transcripts page shows, decided away from the screen that draws it.
 *
 * Pure because every wrong answer here still renders perfectly. A transcript listed under the wrong
 * heading, a row for a recording that was deleted months ago, or a count that disagrees with the hub
 * card the user just tapped are all silent: nothing crashes, nothing is logged, and the only way to
 * find out is to notice. So the grouping is a function over two lists and is tested as one.
 */
object TranscriptsPage {

    /**
     * The page's three groups, in the order they are drawn.
     *
     * @param working  Queued or running — what the queue is about to produce, or is producing.
     * @param failed   The last attempt did not finish. Kept visible rather than dropped: the
     *                 scheduler deliberately never retries a FAILED row, so a transcription that
     *                 died is invisible for ever unless something says so.
     * @param ready    Finished, and therefore readable. **This group alone is what the hub counts**,
     *                 and it holds every DONE row without exception, so the card's number and the
     *                 page's list are the same set rather than two that happen to agree.
     */
    data class Groups(
        val working: List<TranscriptEntry>,
        val failed: List<TranscriptEntry>,
        val ready: List<TranscriptEntry>,
    ) {
        /** Nothing at all to show — the page's empty state, not merely "nothing readable". */
        val isEmpty: Boolean get() = working.isEmpty() && failed.isEmpty() && ready.isEmpty()
    }

    /**
     * Groups [entries] for the page.
     *
     * **Every entry is kept, including one whose recording is gone.** That was not the first answer:
     * dropping them looked tidier, and on the emulator it immediately produced the defect this page
     * exists to avoid — the hub card said "4 transcribed", the page listed three, and nothing
     * anywhere explained the missing one. The card counts DONE rows in the transcripts database and
     * cannot see the recordings catalog, so anything this filters out is a number the user can catch
     * the app lying about by counting.
     *
     * An orphan is rare rather than impossible: the two are separate databases with no foreign key
     * between them, so the delete cascade has to be called by hand and a recording can be gone while
     * its transcript is still being cleaned up. Keeping it is also the honest reading of what it is —
     * the text really is still there and still readable. Only the audio has gone, and the page draws
     * such a row with what it does know rather than pretending to a date and a contact it does not.
     *
     * Input order is preserved inside each group, so the caller's ORDER BY is the page's order and
     * this function has no opinion about what "newest" means.
     */
    fun group(entries: List<TranscriptEntry>): Groups = Groups(
        working = entries.filter {
            it.state == TranscriptState.QUEUED || it.state == TranscriptState.RUNNING
        },
        failed = entries.filter { it.state == TranscriptState.FAILED },
        ready = entries.filter { it.state == TranscriptState.DONE },
    )
}
