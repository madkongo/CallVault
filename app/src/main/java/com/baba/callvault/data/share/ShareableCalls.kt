/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.share

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Looks up what each call has, for the Share sheet. */
object ShareableCalls {

    /**
     * @param audioOf the copy of the recording to send, or null when there is none.
     *
     * Reads the transcripts database only when it exists: a Share tap must not create it (see LibraryCounts).
     */
    suspend fun load(
        context: Context,
        displayNames: List<String>,
        titleOf: (String) -> String,
        audioOf: (String) -> Uri?,
    ): List<ShareableCall> = withContext(Dispatchers.IO) {
        val db = if (TranscriptDatabase.exists(context)) TranscriptDatabase.get(context) else null
        displayNames.distinct().map { name ->
            ShareableCall(
                displayName = name,
                title = titleOf(name),
                audioUri = audioOf(name)?.toString(),
                hasTranscript = db != null &&
                    runCatching { db.transcriptDao().findTranscript(name)?.state == TranscriptState.DONE }.getOrDefault(false),
                hasSummary = db != null &&
                    runCatching { db.summaryDao().summary(name) != null }.getOrDefault(false),
            )
        }
    }
}
