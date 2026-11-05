/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.transcripts.db.TranscriptDao
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.transcription.cloud.TranscriptionService
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Transcribe again" on a finished transcript (#72), which keeps the current text until the new run succeeds.
 *
 * A first transcription and a retry after failure clear the row first ([TranscriptRepository.retry]); doing that
 * here would throw away a transcript the user can still read, before knowing whether the new run works. Instead
 * the words stay in the database (the runner only swaps them on success, in one transaction), the row goes to
 * QUEUED so the queue and the runner take it, and the old row is noted in [TranscribeAgainBackup]. A run that
 * fails or is stopped puts that row back, and the old transcript is readable again exactly as it was.
 */
object TranscribeAgain {
    private const val TAG = "CV:TranscribeAgain"

    suspend fun start(
        context: Context,
        displayName: String,
        language: String?,
        service: TranscriptionService?,
        deleteSummary: Boolean,
    ) {
        val db = TranscriptDatabase.get(context)
        val dao = db.transcriptDao()
        val current = dao.findTranscript(displayName)
        if (current?.state == TranscriptState.DONE) {
            // Written to disk before the row changes: if the app dies in between, the cold-start cleanup
            // must find the backup, or it would delete the text as an ordinary stale run.
            TranscribeAgainBackup.save(context, current)
        } else {
            // Nothing finished to keep. Clear like a plain retry, and drop any leftover backup so a later
            // failure cannot "restore" a row whose words are gone.
            TranscribeAgainBackup.clear(context, displayName)
            dao.deleteFor(displayName)
        }
        dao.upsertTranscript(
            TranscriptEntry(displayName = displayName, state = TranscriptState.QUEUED, updatedAt = System.currentTimeMillis())
        )
        if (deleteSummary) {
            runCatching { db.summaryDao().deleteFor(displayName) }
                .onFailure { AppLogger.w(TAG, "Could not delete the old summary: ${it.message}") }
        }
        AppLogger.i(TAG, "Transcribing again (service ${service?.key ?: "setting"}, language ${language ?: "setting"})")
        TranscriptionScheduler.runNow(context, displayName, language = language, userRequested = true, service = service)
    }

    /** Whether any of [displayNames] has a summary, which the dialog then offers to delete. */
    suspend fun anyHasSummary(context: Context, displayNames: Collection<String>): Boolean {
        if (!TranscriptDatabase.exists(context)) return false
        val summaries = TranscriptDatabase.get(context).summaryDao()
        return displayNames.any { runCatching { summaries.summary(it) != null }.getOrDefault(false) }
    }
}

/**
 * The finished row a "transcribe again" replaced, kept until that run ends. Only the row: the words never left
 * the database. Holds model id, language and a time, never any of the text.
 */
object TranscribeAgainBackup {
    private const val PREFS = "cv_transcribe_again"

    suspend fun save(context: Context, entry: TranscriptEntry) {
        val written = withContext(Dispatchers.IO) {
            prefs(context).edit().putString(entry.displayName, encode(entry)).commit()
        }
        check(written) { "could not keep the current transcript aside" }
    }

    fun peek(context: Context, displayName: String): TranscriptEntry? =
        prefs(context).getString(displayName, null)?.let { decode(displayName, it) }

    fun clear(context: Context, displayName: String) {
        prefs(context).edit().remove(displayName).apply()
    }

    /** Puts the kept row back. True when there was one; false means this run was not a "transcribe again". */
    suspend fun restore(context: Context, dao: TranscriptDao, displayName: String): Boolean {
        val kept = peek(context, displayName) ?: return false
        dao.upsertTranscript(kept)
        clear(context, displayName)
        AppLogger.i("CV:TranscribeAgain", "The new run did not finish; the previous transcript is kept")
        return true
    }

    private const val SEP = '\u001F'
    private const val NULL = "\u0000"

    internal fun encode(entry: TranscriptEntry): String =
        listOf(entry.modelId ?: NULL, entry.language ?: NULL, entry.updatedAt.toString()).joinToString(SEP.toString())

    internal fun decode(displayName: String, stored: String): TranscriptEntry? {
        val parts = stored.split(SEP)
        if (parts.size != 3) return null
        return TranscriptEntry(
            displayName = displayName,
            state = TranscriptState.DONE,
            modelId = parts[0].takeUnless { it == NULL },
            language = parts[1].takeUnless { it == NULL },
            updatedAt = parts[2].toLongOrNull() ?: 0L,
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
