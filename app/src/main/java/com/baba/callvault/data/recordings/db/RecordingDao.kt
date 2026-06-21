/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data-access for the recordings catalog ([RecordingEntry]). All mutations are upserts/targeted updates
 * keyed by [RecordingEntry.displayName], so the post-call copy and the scheduled sweep can independently
 * stamp the Drive copy onto an existing local row without races. Suspend functions — call off the main
 * thread (Room enforces this).
 */
@Dao
interface RecordingDao {

    /** All catalog rows, newest-first (the order Home renders). */
    @Query("SELECT * FROM recordings ORDER BY lastModified DESC, displayName DESC")
    suspend fun getAll(): List<RecordingEntry>

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    @Query("SELECT * FROM recordings WHERE displayName = :displayName LIMIT 1")
    suspend fun findByName(displayName: String): RecordingEntry?

    /** Insert or replace a full row (used by record-finish and the one-time import). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecordingEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<RecordingEntry>)

    /** Stamp the Drive copy onto an existing row (post-call copy / scheduled sweep). No-op if absent. */
    @Query("UPDATE recordings SET driveUri = :driveUri, driveSizeBytes = :driveSizeBytes WHERE displayName = :displayName")
    suspend fun setDrive(displayName: String, driveUri: String, driveSizeBytes: Long?)

    /** Clear the local copy after a DRIVE-only sync deletes it on device. */
    @Query("UPDATE recordings SET localUri = NULL, localSizeBytes = NULL WHERE displayName = :displayName")
    suspend fun clearLocal(displayName: String)

    /** Clear the Drive copy (e.g. the Drive copy alone was deleted). */
    @Query("UPDATE recordings SET driveUri = NULL, driveSizeBytes = NULL WHERE displayName = :displayName")
    suspend fun clearDrive(displayName: String)

    @Query("DELETE FROM recordings WHERE displayName = :displayName")
    suspend fun deleteByName(displayName: String)

    /** Drop rows whose every copy is gone (housekeeping after partial deletes). */
    @Query("DELETE FROM recordings WHERE localUri IS NULL AND driveUri IS NULL")
    suspend fun deleteEmpty()
}
