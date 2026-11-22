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
import kotlinx.coroutines.flow.Flow

/**
 * Data-access for the recordings catalog ([RecordingEntry]). All mutations are upserts/targeted updates
 * keyed by [RecordingEntry.displayName], so the post-call copy and the scheduled sweep can independently
 * stamp the Drive copy onto an existing local row without races. Suspend functions — call off the main
 * thread (Room enforces this).
 */
/** Just the copy state of one recording, for [RecordingDao.observeCopies]. */
data class RecordingCopies(
    val displayName: String,
    val localUri: String?,
    val driveUri: String?,
)

@Dao
interface RecordingDao {

    /**
     * Which copies each recording has, as a stream.
     *
     * Home reads the catalog once per pass, so a Drive copy stamped afterwards by the copy worker or
     * the sweep was invisible until the app was relaunched. This is the change signal that fixes
     * that: deliberately three columns rather than the whole row, because it exists to be compared
     * cheaply on every table write, not to render anything.
     */
    @Query("SELECT displayName, localUri, driveUri FROM recordings")
    fun observeCopies(): Flow<List<RecordingCopies>>

    /** All catalog rows, newest-first (the order Home renders). */
    @Query("SELECT * FROM recordings ORDER BY lastModified DESC, displayName DESC")
    suspend fun getAll(): List<RecordingEntry>

    /** Names ending in a sidecar extension (BabApps item 6). LIKE is case-insensitive for ASCII. */
    @Query("SELECT displayName FROM recordings WHERE displayName LIKE '%.md' OR displayName LIKE '%.json'")
    suspend fun sidecarNames(): List<String>

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    @Query("SELECT * FROM recordings WHERE displayName = :displayName LIMIT 1")
    suspend fun findByName(displayName: String): RecordingEntry?

    /**
     * The row that holds [uri] as its device or Drive copy, if any. One query that reads at most one row, so deleting a
     * copy no longer reads the whole catalog (R47). No new index: that would be a schema change.
     */
    @Query("SELECT * FROM recordings WHERE localUri = :uri OR driveUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): RecordingEntry?

    /** Insert or replace a full row (used by record-finish and the one-time import). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecordingEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<RecordingEntry>)

    /**
     * Stamp the Drive copy onto an existing row (post-call copy / scheduled sweep). No-op if absent.
     *
     * Writes only when something differs (R50): every write to the table wakes Home's catalog watcher, which
     * re-lists the whole library, so a sweep re-stamping thousands of unchanged rows cost minutes of CPU. `IS NOT`
     * compares NULLs as values. Returns how many rows changed.
     */
    @Query(
        "UPDATE recordings SET driveUri = :driveUri, driveSizeBytes = :driveSizeBytes WHERE displayName = :displayName " +
            "AND (driveUri IS NOT :driveUri OR driveSizeBytes IS NOT :driveSizeBytes)"
    )
    suspend fun setDrive(displayName: String, driveUri: String, driveSizeBytes: Long?): Int

    /** Clear the local copy after a DRIVE-only sync deletes it on device. Writes only if a local copy is recorded (R50). */
    @Query(
        "UPDATE recordings SET localUri = NULL, localSizeBytes = NULL WHERE displayName = :displayName " +
            "AND (localUri IS NOT NULL OR localSizeBytes IS NOT NULL)"
    )
    suspend fun clearLocal(displayName: String): Int

    /** Clear the Drive copy (e.g. the Drive copy alone was deleted). */
    @Query("UPDATE recordings SET driveUri = NULL, driveSizeBytes = NULL WHERE displayName = :displayName")
    suspend fun clearDrive(displayName: String)

    /**
     * Forgets the stored Drive size of every row that has a device copy; returns how many it changed. Drive-only
     * rows keep theirs (retention never trusts them). See RecordingCatalog.forgetUnconfirmedDriveSizesOnce.
     */
    @Query("UPDATE recordings SET driveSizeBytes = NULL WHERE driveSizeBytes IS NOT NULL AND localUri IS NOT NULL")
    suspend fun clearDeviceRowDriveSizes(): Int

    /** Remembers the length read off the file, so it is never read twice. */
    @Query("UPDATE recordings SET durationSeconds = :durationSeconds WHERE displayName = :displayName")
    suspend fun setDuration(displayName: String, durationSeconds: Long): Int

    @Query("DELETE FROM recordings WHERE displayName = :displayName")
    suspend fun deleteByName(displayName: String)

    /** Drop rows whose every copy is gone (housekeeping after partial deletes). */
    @Query("DELETE FROM recordings WHERE localUri IS NULL AND driveUri IS NULL")
    suspend fun deleteEmpty()
}
