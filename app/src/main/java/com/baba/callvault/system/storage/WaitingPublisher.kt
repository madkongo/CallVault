/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.net.Uri
import com.baba.callvault.system.diagnostics.CallEndLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves the calls kept in [WaitingRecordings] into the recording folder once it is usable again (review N5): under
 * the name each was recorded with, with its library entry, then handed on like any finished call (transcription when
 * it is on after calls, the waveform, the Drive copy when one is set up).
 *
 * The decisions per file are [WaitingPolicy.publishDecision]. Audio is never deleted unless it was saved or holds
 * nothing; a save that fails leaves the file for the next run. Everything that happens to a file goes to the
 * always-on log.
 */
object WaitingPublisher {

    interface Env {
        val files: List<File>
        val now: Long
        /** Why the recording folder is not usable now, or null when it is. */
        fun folderProblem(): String?
        fun callActive(): Boolean
        /** Creates the file in the folder and fills it; the new document and the name the provider gave it. */
        fun publish(file: File, name: String, mimeType: String): Pair<Uri, String>?
        /**
         * What a finished call gets before its library entry: the details file, and the keep-or-delete hold. Returns
         * whether the call is held for the user's answer.
         */
        suspend fun beforeCatalog(name: String, uri: Uri, mimeType: String, note: WaitingNote): Boolean
        /** The library entry; the failure, or null when it was written. */
        suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable?
        /**
         * What every finished call gets after its library entry: transcription, waveform, the Drive copy; while [held],
         * only the waveform and the question (Keep runs the rest, Delete removes it).
         */
        fun afterPublish(name: String, uri: Uri, mimeType: String, held: Boolean)
        /** One always-on line. */
        fun record(line: String)
    }

    /** What a document already in the folder under a candidate name is, to a waiting call about to be saved. */
    enum class Existing {
        /** Nothing there: save under this name. */
        NONE,

        /** Our own earlier save (killed before the private copy was removed): same name, same non-zero size. */
        ALREADY_SAVED,

        /** Something else, or something we cannot measure: never replaced, never trusted; try the next name. */
        OTHER_NAME,
    }

    /**
     * Second review 2026-11-20 (B1, B2): an existing document is ours only when its size is known, non-zero and equal to
     * ours. A 0-byte or unmeasurable one (a failed earlier write that could not be removed, a provider that will not
     * say) taken as "saved" would delete the only real audio; one of another size may be another call or the user's
     * own file, and is never deleted.
     */
    fun existing(existingSize: Long?, ourSize: Long): Existing = when {
        existingSize == null -> Existing.NONE
        existingSize > 0L && existingSize == ourSize -> Existing.ALREADY_SAVED
        else -> Existing.OTHER_NAME
    }

    /** [name], then "name (1).ext", "name (2).ext", …, as a provider numbers a clash. */
    fun candidates(name: String): Sequence<String> {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { name.substring(0, it) } ?: name
        val ext = dot?.let { name.substring(it) } ?: ""
        return sequenceOf(name) + (1..MAX_NUMBERED).asSequence().map { "$base ($it)$ext" }
    }

    private const val MAX_NUMBERED = 999

    /** The first of [candidates] not [taken]. */
    fun freeName(name: String, taken: (String) -> Boolean): String = candidates(name).firstOrNull { !taken(it) } ?: name

    /**
     * The name to save under: the first candidate that is free, or the one that already holds our earlier save
     * ([alreadySaved]). Null when every candidate is taken by something else.
     */
    fun choose(name: String, ourSize: Long, sizeOf: (String) -> Long?): Pair<String, Boolean>? {
        candidates(name).forEach { candidate ->
            when (existing(sizeOf(candidate), ourSize)) {
                Existing.NONE -> return candidate to false
                Existing.ALREADY_SAVED -> return candidate to true
                Existing.OTHER_NAME -> Unit
            }
        }
        return null
    }

    /** @param retryLater something is still in use or failed: look again later. */
    data class Result(val saved: Int, val retryLater: Boolean)

    /** @param ignoreCallState a call state that seemed never to end has blocked enough retries (S6): save finished calls anyway. */
    suspend fun run(env: Env, notes: List<File> = emptyList(), ignoreCallState: Boolean = false): Result {
        // A note whose recording is gone (a call whose start was abandoned) holds nothing.
        notes.filter { it.name.endsWith(StagingNote.SUFFIX) && !File(it.parentFile, it.name.removeSuffix(StagingNote.SUFFIX)).exists() }
            .forEach { runCatching { it.delete() } }
        if (env.files.isEmpty()) return Result(0, retryLater = false)
        // No provider query while a call is up (review 2026-11-20): look again once it is over.
        val callActive = env.callActive()
        if (callActive && !ignoreCallState) return Result(0, retryLater = true)
        if (callActive) env.record("the call state has looked busy through several retries; saving finished waiting calls anyway")
        val problem = env.folderProblem()
        if (problem != null) return Result(0, retryLater = false)
        var saved = 0
        var retry = false
        env.files.forEach { file ->
            when (one(env, file, callActive)) {
                Outcome.SAVED -> saved++
                Outcome.RETRY -> retry = true
                Outcome.DONE -> Unit
            }
        }
        return Result(saved, retry)
    }

    private enum class Outcome { SAVED, RETRY, DONE }

    private suspend fun one(env: Env, file: File, callActive: Boolean): Outcome {
        val waitingNote = WaitingNote.read(file)
        val bytes = file.length()
        val decision = WaitingPolicy.publishDecision(
            hasNote = waitingNote != null,
            sizeBytes = bytes,
            finished = waitingNote?.finishedAt != null,
            ageMs = env.now - file.lastModified(),
            callActive = callActive,
            live = LiveStagingFiles.isLive(file),
        )
        return when (decision) {
            WaitingPolicy.Publish.WAIT -> Outcome.RETRY
            WaitingPolicy.Publish.LEAVE -> {
                env.record("waiting recording ${file.name} has no note, so no name or type; left in place, not deleted")
                Outcome.DONE
            }
            WaitingPolicy.Publish.DISCARD_EMPTY -> {
                StagingNote.deleteWithNote(file)
                env.record("waiting recording ${file.name} holds no audio; removed")
                Outcome.DONE
            }
            WaitingPolicy.Publish.NOW -> save(env, file, waitingNote!!, bytes)
        }
    }

    private suspend fun save(env: Env, file: File, waiting: WaitingNote, bytes: Long): Outcome = withContext(NonCancellable) {
        val note = waiting.note
        val published = runCatching { env.publish(file, note.fileName, note.mimeType) }.getOrNull()
        if (published == null) {
            env.record("could not save the waiting call ${CallEndLog.safeName(note.fileName)} into the folder; kept for the next try")
            return@withContext Outcome.RETRY
        }
        val (uri, name) = published
        // A hold that cannot be set keeps the call, as on the normal path (AfterCallDecision.tryHold).
        // No call identity (a cut-off: the app was killed mid-call, or the rescue handed it over): saved like the rescue
        // saves one, with no details file and no keep-or-delete question (second review 2026-11-20, S5).
        val held = if (waiting.extras == null) false else runCatching { env.beforeCatalog(name, uri, note.mimeType, waiting) }
            .onFailure { env.record("details file or keep-or-delete hold for ${CallEndLog.safeName(name)} failed: ${CallEndLog.describe(it)}") }
            .getOrDefault(false)
        val failure = runCatching { env.catalog(name, uri, bytes, note.startedAtMillis) }.getOrElse { it }
        val removed = StagingNote.deleteWithNote(file)
        env.record(
            "waiting call saved into the folder: ${CallEndLog.safeName(name)} size=$bytes bytes" +
                (failure?.let { "; library entry FAILED: ${CallEndLog.describe(it)}" } ?: "") +
                (if (removed) "" else "; its private copy could not be removed"),
        )
        runCatching { env.afterPublish(name, uri, note.mimeType, held) }
            .onFailure { env.record("after-save steps for ${CallEndLog.safeName(name)} failed: ${CallEndLog.describe(it)}") }
        Outcome.SAVED
    }
}
