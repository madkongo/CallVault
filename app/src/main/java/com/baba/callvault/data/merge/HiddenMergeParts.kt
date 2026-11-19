/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The parts a merge keeps hidden (owner decision N2, option A).
 *
 * Merging with "keep originals" off removes each part's catalog row but keeps its transcript, segments, notes,
 * tags, star, marks, speaker turns and summary, so un-merge gives them back. Those rows live in the transcripts
 * database, which cannot see the catalog, so every list, count and search over it filters these names out.
 * A hidden part is a manifest entry ([com.baba.callvault.data.recordings.db.MergePartEntry]) with no catalog row.
 *
 * Reading this is display-only, so a failure shows the rows rather than hiding real recordings; it is logged.
 */
object HiddenMergeParts {

    private const val TAG = "CV:HiddenParts"

    /** The hidden part names now; empty (logged) when they cannot be read. */
    suspend fun now(context: Context): Set<String> = runCatching {
        RecordingDatabase.get(context).mergePartDao().hiddenPartNames().toSet()
    }.getOrElse {
        AppLogger.w(TAG, "Could not read the hidden merge parts; showing everything: ${it.message}")
        emptySet()
    }

    /** The hidden part names, live. */
    fun observe(context: Context): Flow<Set<String>> =
        RecordingDatabase.get(context).mergePartDao().observeHiddenPartNames()
            .map { it.toSet() }
            .catch {
                AppLogger.w(TAG, "Could not watch the hidden merge parts; showing everything: ${it.message}")
                emit(emptySet())
            }

    /**
     * [names] plus the hidden parts of any merged recording among them, for the delete cascade: deleting a merged
     * call for good must take its hidden parts' data too. Also drops those recordings' manifests. On a failed read
     * the merged call's own data is still deleted and the failure is logged.
     */
    suspend fun withHiddenPartsOf(context: Context, names: Collection<String>): List<String> = runCatching {
        val dao = RecordingDatabase.get(context).mergePartDao()
        val expanded = names.toMutableList()
        names.forEach { name ->
            val hidden = dao.hiddenPartsOf(name)
            if (dao.partsOf(name).isNotEmpty()) {
                if (hidden.isNotEmpty()) AppLogger.i(TAG, "Deleting ${hidden.size} hidden part(s) with a merged recording")
                expanded += hidden
                dao.deleteParts(name)
            }
        }
        expanded.distinct()
    }.getOrElse {
        AppLogger.w(TAG, "Could not read the merge manifest while deleting; hidden parts may be left: ${it.message}")
        names.toList()
    }
}
