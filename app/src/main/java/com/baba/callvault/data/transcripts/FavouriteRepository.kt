/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import kotlinx.coroutines.flow.combine
import com.baba.callvault.data.merge.HiddenMergeParts
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Starred recordings — the star on the playback screen, the Home filter, and the exemption the two
 * automatic deletes honour.
 *
 * Shaped like [TagRepository], including its rule that a *read* never creates the database: a device
 * that has never transcribed anything should not grow a `transcripts.db` merely because Home asked
 * whether anything is starred.
 */
object FavouriteRepository {

    private const val TAG = "CV:FavouriteRepository"

    private fun dao(context: Context) = TranscriptDatabase.get(context).favouriteDao()

    /** Whether one recording is starred, as a stream, for the star button. */
    fun isFavourite(context: Context, displayName: String): Flow<Boolean> {
        if (!TranscriptDatabase.exists(context)) return flowOf(false)
        return dao(context).observeIsFavourite(displayName)
    }

    /** Every starred name, for Home's filter facet. */
    fun all(context: Context): Flow<List<String>> {
        if (!TranscriptDatabase.exists(context)) return flowOf(emptyList())
        // A merged-away part keeps its star, hidden, for un-merge (N2); it is not a starred recording now.
        return dao(context).observeAll()
            .combine(HiddenMergeParts.observe(context)) { names, hidden -> names.filterNot { it in hidden } }
    }

    /**
     * A one-shot snapshot of the starred names, for the retention sweep and the storage cap.
     *
     * Returns empty when the database has never been created, which is the safe reading: no database
     * means nothing was ever starred, so nothing is exempt. It is deliberately NOT a failure that
     * blocks a sweep — but a *thrown* read is, which is why the caller is expected to treat an
     * exception as "protect everything" rather than "protect nothing".
     */
    suspend fun snapshot(context: Context): List<String> {
        if (!TranscriptDatabase.exists(context)) return emptyList()
        return dao(context).allFavourites()
    }

    /** Stars [displayName]. Creating the database for this is correct — the user asked to keep it. */
    suspend fun add(context: Context, displayName: String) {
        runCatching { dao(context).add(displayName) }
            .onFailure { AppLogger.w(TAG, "Failed to star '$displayName': ${it.message}") }
    }

    /** Unstars [displayName]. */
    suspend fun remove(context: Context, displayName: String) {
        if (!TranscriptDatabase.exists(context)) return
        runCatching { dao(context).deleteFor(displayName) }
            .onFailure { AppLogger.w(TAG, "Failed to unstar '$displayName': ${it.message}") }
    }

    /** Flips the star and returns the new state, for the button's single tap. */
    suspend fun toggle(context: Context, displayName: String, currentlyFavourite: Boolean): Boolean {
        if (currentlyFavourite) remove(context, displayName) else add(context, displayName)
        return !currentlyFavourite
    }
}
