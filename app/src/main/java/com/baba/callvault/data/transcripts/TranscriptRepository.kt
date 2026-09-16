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
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSearchHit
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.data.transcripts.db.TranscriptWithSegments
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What a recording row shows about its transcript.
 *
 * Distinct from [TranscriptState], which is what gets *persisted*. This adds [NONE] — the common case
 * of a recording nobody has transcribed — so the UI renders a state rather than special-casing a null
 * at every use. [NONE] is never stored; a row that does not exist is what produces it.
 */
enum class TranscriptStatus {
    NONE,
    QUEUED,
    RUNNING,
    DONE,
    FAILED;

    companion object {
        fun of(state: TranscriptState?): TranscriptStatus = when (state) {
            null -> NONE
            TranscriptState.QUEUED -> QUEUED
            TranscriptState.RUNNING -> RUNNING
            TranscriptState.DONE -> DONE
            TranscriptState.FAILED -> FAILED
        }
    }
}

/**
 * The transcript side of the recordings list: what each row shows, and the actions it offers.
 *
 * Reads are scoped to the names asked for, because Home lists years of calls and observing the whole
 * table to draw a handful of icons would re-read everything on every change.
 */
object TranscriptRepository {

    private const val TAG = "CV:TranscriptRepository"

    private fun dao(context: Context) = TranscriptDatabase.get(context).transcriptDao()

    /**
     * Status for each of [displayNames], including those with no transcript at all.
     *
     * Every requested name appears in the map. A missing key would leave its row rendering nothing,
     * which is a worse failure than rendering "not transcribed".
     */
    fun statusesFor(context: Context, displayNames: List<String>): Flow<Map<String, TranscriptStatus>> {
        if (displayNames.isEmpty()) return flowOf(emptyMap())

        // Skip opening the database at all before the first transcription — most installs will never
        // have one, and a Home screen should not create a database just to draw a list.
        if (!TranscriptDatabase.exists(context)) {
            return flowOf(displayNames.associateWith { TranscriptStatus.NONE })
        }

        return dao(context).observeAll(displayNames).map { rows ->
            val stored = rows.associate { it.displayName to TranscriptStatus.of(it.state) }
            displayNames.associateWith { stored[it] ?: TranscriptStatus.NONE }
        }
    }

    /**
     * What the database has said about one recording's transcript so far.
     *
     * **Two answers, not one.** Room's flow does not emit until it has queried, so a plain null meant
     * both "not read yet" and "there is none" — and the reader could only be told the first, which
     * is why a page with no transcript said "Loading the transcript…" for ever. That was unreachable
     * while every way in was gated on a finished transcript; the Summaries page is not, because
     * deleting the text leaves the summary, and then the summary is the only way back to the call.
     */
    sealed interface TranscriptRead {

        /** The query has not come back yet. Nothing can be said about the transcript. */
        data object Unread : TranscriptRead

        /** The database has answered. A null [transcript] now means there is none. */
        data class Read(val transcript: TranscriptWithSegments?) : TranscriptRead
    }

    /** The full transcript for one recording. See [TranscriptRead] for why it is wrapped. */
    fun transcript(context: Context, displayName: String): Flow<TranscriptRead> =
        dao(context).observe(displayName).map { TranscriptRead.Read(it) }

    /**
     * Transcribes [displayName] now, at the user's request — no charging or schedule constraints.
     *
     * [language] is a per-recording pick (see
     * [com.baba.callvault.transcription.TranscriptionLanguageChoice]); null uses the setting.
     */
    fun transcribeNow(context: Context, displayName: String, language: String? = null) {
        TranscriptionScheduler.runNow(context, displayName, language = language, userRequested = true)
    }

    /**
     * Asks for [displayName] to be transcribed, from a tap.
     *
     * Serves a first transcription, a retry after failure and "transcribe again" alike.
     *
     * Clearing the row first is the point, not a side effect:
     * [com.baba.callvault.transcription.TranscriptionQueue] deliberately skips FAILED so an undecodable
     * file is not retried nightly forever, and skips DONE so finished work is not paid for twice —
     * either would make a tap do nothing at all.
     *
     * It is then marked QUEUED **before** the work is enqueued, so the row shows that it is waiting the
     * moment it is tapped. Taps chain — one run at a time — so without this a second tapped call sits
     * looking exactly like an untapped one, inviting the user to tap it again.
     *
     * [language] is the language picked for this recording alone, null meaning "use the setting".
     */
    suspend fun retry(context: Context, displayName: String, language: String? = null) {
        val dao = dao(context)
        dao.deleteFor(displayName)
        dao.upsertTranscript(
            TranscriptEntry(
                displayName = displayName,
                state = TranscriptState.QUEUED,
                updatedAt = System.currentTimeMillis()
            )
        )
        transcribeNow(context, displayName, language)
    }

    /** Removes a transcript, leaving the recording. Someone may want the audio but not the text. */
    suspend fun delete(context: Context, displayName: String) {
        dao(context).deleteFor(displayName)
    }

    /**
     * Removes a summary, leaving the transcript it was written from and the recording.
     *
     * The narrowest of the three deletes, and deliberately not [TranscriptCascade]: that one clears
     * the transcript, the note, the tags and the stars as well, which is right when the recording
     * itself goes and catastrophic when the user only meant to throw away a summary they disagreed
     * with. The summary can be written again from the reading view afterwards.
     *
     * Guarded on the database existing, like every other read in this area: there is no summary to
     * remove on a phone that has never transcribed, and materialising `transcripts.db` to find that
     * out is the mistake [LibraryCounts] exists to prevent.
     */
    suspend fun deleteSummary(context: Context, displayName: String) {
        if (!TranscriptDatabase.exists(context)) return
        TranscriptDatabase.get(context).summaryDao().deleteFor(displayName)
    }

    /**
     * Full-text search across every transcript, **summary and note**.
     *
     * [query] is whatever the user typed, and is quoted before it reaches SQLite: `MATCH` takes an
     * expression, so an apostrophe or asterisk typed naturally would otherwise be a syntax error —
     * surfacing as a crash rather than as "no results".
     *
     * **One hit per recording, and a segment hit always wins.** The three indexes routinely match the
     * same call — a word said aloud usually also reaches the summary — and only the segment hit knows
     * *where* in the audio it was, so it is the one worth keeping. [mergeHits] does that ranking here
     * rather than in the UI, which collapses by display name and would otherwise keep whichever hit
     * happened to arrive last.
     *
     * Each index is queried in its own `runCatching`: a summary index that fails must not take the
     * transcript results down with it, since transcripts are what search has always returned.
     */
    suspend fun search(context: Context, query: String): List<TranscriptSearchHit> {
        val prepared = quoteForFts(query)
        if (prepared.isEmpty()) return emptyList()
        if (!TranscriptDatabase.exists(context)) return emptyList()

        backfillSummarySearchText(context)

        val db = TranscriptDatabase.get(context)
        val segments = attempt("transcripts") { db.transcriptDao().search(prepared) }
        val summaries = attempt("summaries") { db.summaryDao().search(prepared) }
        val notes = attempt("notes") { db.noteDao().searchNotes(prepared) }
        return mergeHits(segments, summaries, notes)
    }

    /**
     * The three result sets as one, preferring the hit that can be acted on.
     *
     * Pure and internal so the preference is testable without a database — the interesting failure is
     * a result that renders perfectly and seeks to the wrong place, which no crash would reveal.
     */
    internal fun mergeHits(
        segments: List<TranscriptSearchHit>,
        summaries: List<TranscriptSearchHit>,
        notes: List<TranscriptSearchHit>
    ): List<TranscriptSearchHit> {
        val byName = LinkedHashMap<String, TranscriptSearchHit>()
        (segments + summaries + notes).forEach { byName.putIfAbsent(it.displayName, it) }
        return byName.values.toList()
    }

    /**
     * Gives summaries written before v5 their `searchText`, once.
     *
     * The column cannot be filled by the migration itself: the text lives inside a JSON document and
     * the only parser for it is `CallSummary.parse`, in Kotlin. So it is done on the first search
     * instead — the moment it first matters, already off the main thread, and bounded by however many
     * summaries the user has.
     *
     * A summary that no longer parses is left with an empty `searchText` and skipped rather than
     * retried for ever. It stays unsearchable, which is the same position it was in before, and it is
     * still readable on its own screen: the parse failure belongs to the stored document, and a search
     * is not the place to discover it.
     */
    private suspend fun backfillSummarySearchText(context: Context) {
        runCatching {
            val dao = TranscriptDatabase.get(context).summaryDao()
            val pending = dao.needingSearchText()
            if (pending.isEmpty()) return

            var filled = 0
            pending.forEach { entry ->
                val text = CallSummary.parse(entry.document)?.searchableText().orEmpty()
                if (text.isNotBlank()) {
                    dao.setSearchText(entry.displayName, text)
                    filled++
                }
            }
            AppLogger.i(TAG, "Indexed $filled of ${pending.size} summary/summaries for search")
        }.onFailure { AppLogger.w(TAG, "Summary search backfill failed: ${it.message}") }
    }

    private inline fun attempt(
        what: String,
        block: () -> List<TranscriptSearchHit>
    ): List<TranscriptSearchHit> = runCatching(block).getOrElse {
        // Never let a search term take the screen down; report nothing found for this index.
        AppLogger.w(TAG, "Search over $what failed: ${it.message}")
        emptyList()
    }

    /**
     * Turns free text into a safe FTS MATCH expression.
     *
     * Each whitespace-separated word becomes a quoted phrase, with embedded quotes doubled per SQLite's
     * escaping rule. That treats the input as words to find rather than as operators, which is what
     * someone typing into a search box means.
     */
    private fun quoteForFts(query: String): String =
        query.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
}
