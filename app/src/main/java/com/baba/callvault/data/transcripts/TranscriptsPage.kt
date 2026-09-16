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
     *                 so the card and the page agree by construction rather than by coincidence.
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
     * Groups [entries] for the page, dropping any whose recording is gone.
     *
     * [catalogued] is every display name the recordings list currently holds. A transcript with no
     * recording behind it is normal rather than exceptional — the two are separate databases with no
     * foreign key between them, so a recording can be deleted while its transcript is still being
     * cleaned up, and a Drive-only library does not enumerate the device folder at all. Such a row is
     * dropped rather than drawn: it would be an unlabelled row that opens onto a transcript with no
     * audio to play, and it would make the page's count disagree with the hub's.
     *
     * Input order is preserved inside each group, so the caller's ORDER BY is the page's order and
     * this function has no opinion about what "newest" means.
     */
    fun group(entries: List<TranscriptEntry>, catalogued: Set<String>): Groups {
        val known = entries.filter { it.displayName in catalogued }
        return Groups(
            working = known.filter {
                it.state == TranscriptState.QUEUED || it.state == TranscriptState.RUNNING
            },
            failed = known.filter { it.state == TranscriptState.FAILED },
            ready = known.filter { it.state == TranscriptState.DONE },
        )
    }
}
