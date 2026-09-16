/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the hub's cards count, and what the Transcripts and Summaries sections list.
 *
 * **Every read here goes through [TranscriptDatabase.exists] first, and that is the whole reason this
 * object exists.** The hub is the first screen of the app, so it asks these questions on every open —
 * including on a phone that has never transcribed anything. Asking Room directly would create
 * `transcripts.db` as a side effect of drawing a badge that reads "0", which is the same mistake
 * [TranscriptRepository.statusesFor] and the delete cascade already guard against: a database
 * materialised by a screen that only wanted to know whether there was one.
 *
 * Zero and empty are the honest answers in that case, not placeholders — there is no database
 * precisely because nothing has ever been transcribed or summarised.
 */
object LibraryCounts {

    /** How many recordings have a finished transcript. QUEUED, RUNNING and FAILED do not count:
     *  the card offers something to read, and only a DONE transcript is readable. */
    fun transcribed(context: Context): Flow<Int> {
        if (!TranscriptDatabase.exists(context)) return flowOf(0)
        return TranscriptDatabase.get(context).transcriptDao().countWithState(TranscriptState.DONE)
    }

    /** How many recordings have a summary. */
    fun summarised(context: Context): Flow<Int> {
        if (!TranscriptDatabase.exists(context)) return flowOf(0)
        return TranscriptDatabase.get(context).summaryDao().countAll()
    }

    /** The recordings with a finished transcript, newest transcript first. */
    fun transcribedNames(context: Context): Flow<List<String>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        return TranscriptDatabase.get(context).transcriptDao()
            .observeDisplayNamesWithState(TranscriptState.DONE)
    }

    /** The recordings with a summary, newest summary first. */
    fun summarisedNames(context: Context): Flow<List<String>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        return TranscriptDatabase.get(context).summaryDao().observeAllDisplayNames()
    }
}
