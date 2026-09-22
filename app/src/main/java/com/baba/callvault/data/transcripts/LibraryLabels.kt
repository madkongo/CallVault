/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.transcripts.db.RecordingLabelEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The last-known name and date of each recording, for the library pages to title a row before the
 * recordings list has loaded. See [RecordingLabelEntry] for why it exists.
 *
 * Written by [refresh] each time the list loads; read by [observe] as the fallback for a row whose
 * recording is not (yet) in the list. The rule for what a label IS lives in [of], and is the same
 * rule `RecordingLabel.of` draws with, minus the bidi isolation the row adds when it draws.
 */
object LibraryLabels {

    private const val TAG = "CV:LibraryLabels"

    /** [item] as its row titles it: contact name, else number, else file name; and its date line. */
    fun of(item: RecordingItem): RecordingLabelEntry = RecordingLabelEntry(
        displayName = item.displayName,
        label = item.contactName ?: item.number ?: item.displayName,
        subtitle = item.displayDate,
    )

    /**
     * The labels that [loaded] gives differently from [stored] — new recordings and renamed
     * contacts — and nothing else. A recording that is gone keeps its label: an orphaned transcript
     * is the very row that has nothing else to be named by.
     */
    fun changed(stored: List<RecordingLabelEntry>, loaded: List<RecordingItem>): List<RecordingLabelEntry> {
        val known = stored.associateBy { it.displayName }
        return loaded.map(::of).filter { known[it.displayName] != it }
    }

    /**
     * Records what [loaded] calls each recording. Only when the transcripts database already exists:
     * creating it here would make the library pages believe there is one, and there is nothing to
     * label in a library that has never transcribed anything.
     */
    suspend fun refresh(context: Context, loaded: List<RecordingItem>) {
        if (!TranscriptDatabase.exists(context)) return
        runCatching {
            val dao = TranscriptDatabase.get(context).labelDao()
            val toWrite = changed(dao.all(), loaded)
            if (toWrite.isNotEmpty()) dao.upsertAll(toWrite)
        }.onFailure { AppLogger.w(TAG, "Could not record the recordings' labels: ${it.message}") }
    }

    /** Every cached label by display name; empty for a library with no transcripts database. */
    fun observe(context: Context): Flow<Map<String, RecordingLabelEntry>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyMap())
        return TranscriptDatabase.get(context).labelDao().observeAll().map { all -> all.associateBy { it.displayName } }
    }
}
