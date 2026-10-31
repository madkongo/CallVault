/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Notes the user wrote, and the cached shapes used to draw a recording. */
@Dao
interface RecordingNoteDao {

    @Query("SELECT * FROM recording_notes WHERE displayName = :displayName")
    fun observeNote(displayName: String): Flow<RecordingNoteEntry?>

    @Upsert
    suspend fun upsertNote(entry: RecordingNoteEntry)

    /**
     * Removes a note.
     *
     * Called when the text is emptied rather than storing a blank row: an empty note is the absence of
     * a note, and keeping the row would make "this call has a note" true for every recording the user
     * has ever opened and typed into by accident.
     */
    /** One recording's note. Used when a merge joins the parts' notes together. */
    @Query("SELECT * FROM recording_notes WHERE displayName = :displayName")
    suspend fun note(displayName: String): RecordingNoteEntry?

    @Query("DELETE FROM recording_notes WHERE displayName = :displayName")
    suspend fun deleteNote(displayName: String)

    /**
     * Notes whose text matches [query], as search hits with no timestamp.
     *
     * `startMs` is 0 for the same reason as a summary hit: a note is about the call, not about a
     * moment in it.
     */
    @Query(
        """
        SELECT n.displayName AS displayName, 0 AS startMs, n.text AS snippet
        FROM recording_notes AS n
        JOIN recording_notes_fts AS f ON f.docid = n.rowid
        WHERE recording_notes_fts MATCH :query
        """
    )
    suspend fun searchNotes(query: String): List<TranscriptSearchHit>

    @Query("SELECT peaks FROM recording_waveforms WHERE displayName = :displayName")
    suspend fun waveform(displayName: String): String?

    /** [waveform], as it changes: lets an open recording show its bars the moment a background draw saves them. */
    @Query("SELECT peaks FROM recording_waveforms WHERE displayName = :displayName")
    fun observeWaveform(displayName: String): Flow<String?>

    @Upsert
    suspend fun upsertWaveform(entry: RecordingWaveformEntry)

    @Query("DELETE FROM recording_waveforms WHERE displayName = :displayName")
    suspend fun deleteWaveform(displayName: String)
}
