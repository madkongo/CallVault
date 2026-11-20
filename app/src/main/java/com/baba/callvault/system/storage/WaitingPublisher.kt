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
        /** The library entry; the failure, or null when it was written. */
        suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable?
        /** What every finished call gets after its library entry: transcription, waveform, the Drive copy. */
        fun afterPublish(name: String, uri: Uri, mimeType: String)
        /** One always-on line. */
        fun record(line: String)
    }

    /**
     * The saved document and its name from a [SafHelper.copyFileToFolder] result, or null when nothing usable landed.
     * An existing same-name same-size file counts as saved: that is a save killed before the private copy was removed.
     */
    fun publishedFrom(result: SafHelper.CopyResult, name: String): Pair<Uri, String>? = when (result) {
        is SafHelper.CopyResult.Copied -> result.uri to name
        is SafHelper.CopyResult.AlreadyPresent -> result.uri to name
        is SafHelper.CopyResult.Failed -> null
    }

    /** @param retryLater something is still in use or failed: look again later. */
    data class Result(val saved: Int, val retryLater: Boolean)

    suspend fun run(env: Env, notes: List<File> = emptyList()): Result {
        // A note whose recording is gone (a call whose start was abandoned) holds nothing.
        notes.filter { it.name.endsWith(StagingNote.SUFFIX) && !File(it.parentFile, it.name.removeSuffix(StagingNote.SUFFIX)).exists() }
            .forEach { runCatching { it.delete() } }
        if (env.files.isEmpty()) return Result(0, retryLater = false)
        // No provider query while a call is up (review 2026-11-20): look again once it is over.
        if (env.callActive()) return Result(0, retryLater = true)
        val problem = env.folderProblem()
        if (problem != null) return Result(0, retryLater = false)
        val callActive = false
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
        val failure = runCatching { env.catalog(name, uri, bytes, note.startedAtMillis) }.getOrElse { it }
        val removed = StagingNote.deleteWithNote(file)
        env.record(
            "waiting call saved into the folder: ${CallEndLog.safeName(name)} size=$bytes bytes" +
                (failure?.let { "; library entry FAILED: ${CallEndLog.describe(it)}" } ?: "") +
                (if (removed) "" else "; its private copy could not be removed"),
        )
        runCatching { env.afterPublish(name, uri, note.mimeType) }
            .onFailure { env.record("after-save steps for ${CallEndLog.safeName(name)} failed: ${CallEndLog.describe(it)}") }
        Outcome.SAVED
    }
}
