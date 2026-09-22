/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * What a recording was last called — the name the row is titled with and the date under it — kept
 * so the Transcripts and Summaries pages can name a row before the recordings list has loaded.
 *
 * Those pages read the transcripts database, which knows a recording only by file name; the name
 * and date come from the recordings list, a scan of the files with a contact lookup each, which
 * takes seconds on a cold start. Until it lands a row was titled by its file name, which reads as a
 * date and time. This table is the last answer the scan gave, written each time it completes, and
 * read only as the fallback while it is running: the list, once loaded, wins.
 *
 * Its own table rather than columns on `transcripts`, because that row is replaced whole on every
 * state change and the label would have to be carried through each of those writes.
 *
 * @param label    the row's title, un-isolated: contact name, else the number, else the file name.
 * @param subtitle the date line under it, or null for a recording with no parsable date.
 */
@Entity(tableName = "recording_labels")
data class RecordingLabelEntry(
    @PrimaryKey val displayName: String,
    val label: String,
    val subtitle: String?,
)

@Dao
interface RecordingLabelDao {

    @Query("SELECT * FROM recording_labels")
    fun observeAll(): Flow<List<RecordingLabelEntry>>

    @Query("SELECT * FROM recording_labels")
    suspend fun all(): List<RecordingLabelEntry>

    @Upsert
    suspend fun upsertAll(entries: List<RecordingLabelEntry>)
}
