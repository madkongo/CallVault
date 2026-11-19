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
 * Data-access for the merge manifest ([MergePartEntry]).
 *
 * Every read is ordered by position, never by insertion: the order a merged call plays in is the
 * order the user ticked the parts, which is deliberately neither chronological nor the order the
 * rows happened to be written.
 */
/** How many calls one merged recording was made from. */
data class MergedPartCount(val mergedName: String, val partCount: Int)

@Dao
interface MergePartDao {

    /** The parts of [mergedName], in playback order. Empty for a recording that was never merged. */
    @Query("SELECT * FROM merge_parts WHERE mergedName = :mergedName ORDER BY position ASC")
    suspend fun partsOf(mergedName: String): List<MergePartEntry>

    /** The merged recording [partName] lives inside, or null if it is not part of one. */
    @Query("SELECT mergedName FROM merge_parts WHERE partName = :partName LIMIT 1")
    suspend fun mergedNameContaining(partName: String): String?

    /** Every merged recording that currently has a manifest. */
    @Query("SELECT DISTINCT mergedName FROM merge_parts")
    suspend fun allMergedNames(): List<String>

    /** How many calls each merged recording holds — one grouped query for a whole list. */
    @Query("SELECT mergedName, COUNT(*) AS partCount FROM merge_parts GROUP BY mergedName")
    suspend fun partCounts(): List<MergedPartCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(parts: List<MergePartEntry>)

    /**
     * Parts kept inside a merged recording with no catalog row of their own: their transcript, notes, tags and
     * star are kept for un-merge but must show nowhere while merged (owner decision N2).
     */
    @Query("SELECT DISTINCT partName FROM merge_parts WHERE partName NOT IN (SELECT displayName FROM recordings)")
    suspend fun hiddenPartNames(): List<String>

    /** [hiddenPartNames], live: Room re-runs it when either table changes. */
    @Query("SELECT DISTINCT partName FROM merge_parts WHERE partName NOT IN (SELECT displayName FROM recordings)")
    fun observeHiddenPartNames(): kotlinx.coroutines.flow.Flow<List<String>>

    /** The parts of [mergedName] that have no catalog row of their own: what deleting it must take with it. */
    @Query(
        "SELECT DISTINCT partName FROM merge_parts WHERE mergedName = :mergedName " +
            "AND partName NOT IN (SELECT displayName FROM recordings)"
    )
    suspend fun hiddenPartsOf(mergedName: String): List<String>

    /** Drops the manifest — the second half of an un-merge, once the parts are back on disk. */
    @Query("DELETE FROM merge_parts WHERE mergedName = :mergedName")
    suspend fun deleteParts(mergedName: String)
}
