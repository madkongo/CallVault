/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** How often a screen waiting for the first transcript looks for the database. One file check. */
private const val WAIT_FOR_DATABASE_MS = 2_000L

/**
 * A live query on the transcripts database that does not create it, and does not go stale before it exists.
 *
 * Screens ask about transcripts before there are any, and must not create `transcripts.db` by asking (see
 * [LibraryCounts]). They used to get a one-off "empty" instead, so a page opened before the first transcription
 * stayed empty after it, until the app was reopened. This answers [empty] until the database appears, then
 * follows the real query.
 */
internal fun <T> onceTranscriptsExist(
    context: Context,
    empty: T,
    query: (TranscriptDatabase) -> Flow<T>,
): Flow<T> = onceExists(
    exists = { TranscriptDatabase.exists(context) },
    empty = empty,
    open = { query(TranscriptDatabase.get(context)) },
)

/** [onceTranscriptsExist] without Android, for tests. */
internal fun <T> onceExists(
    exists: () -> Boolean,
    empty: T,
    open: () -> Flow<T>,
    pollMs: Long = WAIT_FOR_DATABASE_MS,
): Flow<T> {
    if (exists()) return open()
    return flow {
        emit(empty)
        while (!exists()) delay(pollMs)
        emitAll(open())
    }
}
