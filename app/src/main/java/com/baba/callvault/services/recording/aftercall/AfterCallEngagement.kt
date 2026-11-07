/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.utils.AppLogger

/**
 * Whether the user did something with a held recording — a star, a mark, a tag, a note, or a transcript row
 * (they tapped Transcribe). Any of these is read as "keep", so "Delete after 24 hours" never destroys what
 * they had just added. See [AfterCallPolicy.unansweredAction].
 */
internal object AfterCallEngagement {

    private const val TAG = "CV:AfterCall"

    /**
     * True when engaged. Never creates the database: before the first transcript, star or tag none of these
     * can exist. A lookup that fails reads as engaged — the failure must fall towards keeping.
     */
    suspend fun isEngaged(context: Context, displayName: String): Boolean {
        if (!TranscriptDatabase.exists(context)) return false
        return runCatching {
            val db = TranscriptDatabase.get(context)
            db.favouriteDao().isFavourite(displayName) ||
                db.flagDao().flagsFor(displayName).isNotEmpty() ||
                db.tagDao().tagsFor(displayName).isNotEmpty() ||
                db.noteDao().note(displayName) != null ||
                db.transcriptDao().findTranscript(displayName) != null
        }.getOrElse {
            AppLogger.w(TAG, "Could not read how a held recording was used; keeping it: ${it.message}")
            true
        }
    }
}
