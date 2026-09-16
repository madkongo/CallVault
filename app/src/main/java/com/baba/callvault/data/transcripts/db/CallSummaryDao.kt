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

/** Summaries a model wrote about a call. */
@Dao
interface CallSummaryDao {

    /**
     * The summary for a recording, or null while it has none.
     *
     * A [Flow] because the playback screen is open while the summary is being written: the card
     * has to arrive when the worker finishes, without the user leaving the screen and coming back.
     */
    @Query("SELECT * FROM call_summaries WHERE displayName = :displayName")
    fun observe(displayName: String): Flow<CallSummaryEntry?>

    @Query("SELECT * FROM call_summaries WHERE displayName = :displayName")
    suspend fun summary(displayName: String): CallSummaryEntry?

    /**
     * Upsert rather than insert, so redoing a summary replaces the old one.
     *
     * Keeping both would mean deciding which to show, and the answer is always the newer — it was
     * asked for precisely because the earlier one was not good enough.
     */
    @Upsert
    suspend fun upsert(entry: CallSummaryEntry)

    @Query("DELETE FROM call_summaries WHERE displayName = :displayName")
    suspend fun deleteFor(displayName: String)

    /** Which of [displayNames] already have a summary — for showing the state of a list at a glance. */
    /**
     * Summaries whose text matches [query], as search hits with no timestamp.
     *
     * `startMs` is 0 because a summary is about the whole call and there is no moment in the audio to
     * seek to — tapping the result opens the recording at the beginning, which is the honest answer.
     * Ranking against segment hits is [com.baba.callvault.data.transcripts.TranscriptRepository]'s
     * job, not SQL's.
     *
     * [query] is an FTS MATCH expression, not a literal; callers quote user input before it gets here.
     */
    @Query(
        """
        SELECT s.displayName AS displayName, 0 AS startMs, s.searchText AS snippet
        FROM call_summaries AS s
        JOIN call_summaries_fts AS f ON f.docid = s.rowid
        WHERE call_summaries_fts MATCH :query
        """
    )
    suspend fun search(query: String): List<TranscriptSearchHit>

    /**
     * Summaries written before `searchText` existed, which are invisible to [search] until backfilled.
     *
     * Empty is the only marker available: a summary always has an intent and a non-blank body, so a
     * real one can never flatten to an empty string.
     */
    @Query("SELECT * FROM call_summaries WHERE searchText = ''")
    suspend fun needingSearchText(): List<CallSummaryEntry>

    /** Writing this fires the FTS sync trigger, which is what actually indexes the row. */
    @Query("UPDATE call_summaries SET searchText = :searchText WHERE displayName = :displayName")
    suspend fun setSearchText(displayName: String, searchText: String)

    @Query("SELECT displayName FROM call_summaries WHERE displayName IN (:displayNames)")
    suspend fun summarised(displayNames: List<String>): List<String>

    /**
     * How many summaries exist, as a live count.
     *
     * COUNT in SQL, not the size of a row list: every summary row carries its whole JSON document,
     * and the hub wants one number beside a card. Reading the documents to count them would make the
     * hub cost more the more the user has summarised.
     */
    @Query("SELECT COUNT(*) FROM call_summaries")
    fun countAll(): Flow<Int>

    /** Every summarised recording, newest first. */
    @Query("SELECT displayName FROM call_summaries ORDER BY createdAt DESC")
    fun observeAllDisplayNames(): Flow<List<String>>
}
