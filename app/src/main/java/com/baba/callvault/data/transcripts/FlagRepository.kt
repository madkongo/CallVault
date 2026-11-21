/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.transcripts.db.RecordingFlagEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow

/** The moments a user marked during a call. Shaped like [FavouriteRepository]. */
object FlagRepository {

    private const val TAG = "CV:FlagRepository"

    private fun dao(context: Context) = TranscriptDatabase.get(context).flagDao()

    /** The marks on one recording, earliest first. */
    fun flagsFor(context: Context, displayName: String): Flow<List<Long>> =
        // Empty until the database exists, then the live query, so a screen opened first is not stuck empty (C164).
        onceTranscriptsExist(context, emptyList()) { it.flagDao().observeFor(displayName) }

    /**
     * Stores [offsetsMs] against [displayName], at the end of a call.
     *
     * Called with the recording's **final** name, after any call-log rename. Marks saved against a
     * name that then changed would sit against a recording that does not exist, and would silently
     * never appear.
     */
    suspend fun save(context: Context, displayName: String, offsetsMs: List<Long>) {
        if (offsetsMs.isEmpty()) return
        runCatching {
            dao(context).upsertAll(offsetsMs.distinct().sorted().map { RecordingFlagEntry(displayName, it) })
            AppLogger.i(TAG, "Saved ${offsetsMs.size} mark(s) for '$displayName'.")
        }.onFailure { AppLogger.w(TAG, "Could not save marks for '$displayName': ${it.message}") }
    }

    /** Removes one mark, for the playback screen's long-press. */
    suspend fun remove(context: Context, displayName: String, atMs: Long) {
        if (!TranscriptDatabase.exists(context)) return
        runCatching { dao(context).deleteOne(displayName, atMs) }
            .onFailure { AppLogger.w(TAG, "Could not remove a mark: ${it.message}") }
    }
}
