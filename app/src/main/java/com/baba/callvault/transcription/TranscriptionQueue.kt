/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.services.recording.aftercall.AfterCallDecisions
import com.baba.callvault.utils.AppLogger
import android.content.Context
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.TranscribeAgainBackup
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState

/**
 * Which recordings still need transcribing.
 *
 * The answer spans two databases — the recordings catalog and the transcripts store — which cannot be
 * joined in SQL, so it is composed here.
 *
 * The exclusions matter more than the inclusions. Transcribing costs roughly the call's own duration
 * in CPU, so a queue that keeps re-offering work that is finished, in flight, or hopeless would burn
 * a night of battery to achieve nothing.
 */
object TranscriptionQueue {

    private const val TAG = "CV:TranscriptionQueue"

    /** How many recordings one scheduled run will take on. */
    const val DEFAULT_LIMIT = 25

    /** Passed as the limit to mean "take the whole backlog". */
    const val NO_LIMIT = 0

    /**
     * Display names that should be transcribed, **oldest first** so a backlog drains in call order.
     *
     * A recording is offered when it has a local file to decode and either has no transcript row at
     * all or was left [TranscriptState.QUEUED] by an interrupted run.
     *
     * Deliberately excluded:
     * - [TranscriptState.DONE] — already transcribed.
     * - [TranscriptState.RUNNING] — in flight; offering it again would duplicate the work.
     * - [TranscriptState.FAILED] — a file that cannot be decoded fails identically every time, so a
     *   scheduled run must not retry it nightly forever. Only an explicit tap retries.
     * - No local copy — a Drive-only recording has nothing to decode. Queuing it would fail and then
     *   mark it FAILED, which would also hide it from a later run made after it syncs back down.
     * - Waiting for a keep-or-delete answer — transcribing a call the user may be about to delete
     *   spends its battery for nothing; Keep queues it the moment they answer.
     *
     * Length is not a reason: long calls are transcribed like any other (the limit was removed once
     * decoding became chunked).
     */
    suspend fun pending(
        context: Context,
        limit: Int = DEFAULT_LIMIT,
    ): List<String> {
        val all = RecordingCatalog.all(context)
        val held = AfterCallDecisions.pendingNames(context)
        val transcribable = all.filter { it.localUri != null && it.displayName !in held }
        if (transcribable.isEmpty()) {
            // "Nothing was transcribed" has several innocent explanations and one real bug, and they
            // are indistinguishable without this line: no recordings at all, or recordings that exist
            // only in Drive and so have nothing local to decode.
            AppLogger.i(TAG, "pending: nothing to do — ${all.size} recording(s), none with a local copy that is not awaiting keep-or-delete (${held.size} awaiting)")
            return emptyList()
        }

        val states = if (TranscriptDatabase.exists(context)) {
            TranscriptDatabase.get(context).transcriptDao()
                .allTranscripts()
                .associate { it.displayName to it.state }
        } else {
            emptyMap()
        }

        val eligible = transcribable.filter { entry ->
            when (states[entry.displayName]) {
                null, TranscriptState.QUEUED -> true
                TranscriptState.DONE, TranscriptState.RUNNING, TranscriptState.FAILED -> false
            }
        }

        val selected = eligible
            .sortedBy { it.lastModified }
            .let { if (limit <= NO_LIMIT) it else it.take(limit) }
            .map { it.displayName }

        // Counted by reason rather than listed by name: the answer to "why was my call not
        // transcribed?" is almost always a state the user cannot see, and a name-by-name dump of a
        // large library would bury it. FAILED and RUNNING are the two that surprise people — one is
        // never retried automatically, the other is invisible work or a stuck row.
        val skipped = transcribable.size - eligible.size
        if (skipped > 0 || selected.isNotEmpty()) {
            val byState = transcribable
                .filterNot { it.displayName in selected }
                .groupingBy { states[it.displayName]?.name ?: "NEW" }
                .eachCount()
                .entries.sortedBy { it.key }
                .joinToString { "${it.key}=${it.value}" }
            AppLogger.i(
                TAG,
                "pending: ${selected.size} queued of ${transcribable.size} local " +
                    "(limit=$limit); not queued: ${byState.ifEmpty { "none" }}"
            )
        }
        return selected
    }

    /**
     * Clears rows left [TranscriptState.RUNNING] or [TranscriptState.QUEUED] by work that is no longer
     * happening.
     *
     * A worker that is cancelled — or killed outright — cannot be relied on to tidy up after itself:
     * whisper runs inside a blocking native call, so the interruption may never reach the Kotlin that
     * would reset the row. Observed on the OP12: after Stop the row sat on the busy spinner for ever,
     * untappable, while nothing was running; and the queue skips RUNNING, so an automatic sweep would
     * not have rescued it either.
     *
     * QUEUED is included because a tap marks a row queued before its work is enqueued: if that work
     * never runs, the row would sit on the busy spinner for ever, and this mode's queue skips QUEUED's
     * sibling RUNNING anyway. Deleting is safe even if the work does still run — the worker marks the
     * row again when it starts, and a recording with no row is exactly what [pending] offers.
     *
     * Called from the app process by whoever does the stopping, which is still alive to finish the job.
     *
     * @return how many rows were released.
     */
    suspend fun releaseStaleWork(context: Context): Int {
        if (!TranscriptDatabase.exists(context)) return 0

        val dao = TranscriptDatabase.get(context).transcriptDao()
        val stale = dao.displayNamesWithState(TranscriptState.RUNNING) +
            dao.displayNamesWithState(TranscriptState.QUEUED)
        // A stopped "transcribe again" (#72) gets its previous transcript back rather than losing it, and a row
        // the run has already put back (finished again by now) is never deleted: the two cleanups race on Stop.
        stale.forEach {
            if (!TranscribeAgainBackup.restore(context, dao, it) &&
                dao.findTranscript(it)?.state != TranscriptState.DONE
            ) dao.deleteFor(it)
        }
        return stale.size
    }
}
