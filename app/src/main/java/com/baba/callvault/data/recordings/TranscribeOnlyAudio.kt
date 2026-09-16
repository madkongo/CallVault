/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes the audio of a file the user imported *only* to read, once the reading is done.
 *
 * The user was asked what a shared file was for and said "transcribe only": the words are what they
 * wanted, and the audio — a voice note out of somebody's chat, a recording of a meeting — is not
 * something they asked a call recorder to keep. So it goes, and the transcript stays.
 *
 * ## The one outcome that must be impossible
 *
 * Losing both. The audio is the only copy CallVault has (an import is never sent to Drive, by
 * design), so a delete that runs before the words are safely stored destroys the file and produces
 * nothing in exchange. Everything here is arranged around that:
 *
 *  - **[verdictFor] is pure, and says no by default.** Four separate conditions must all hold, and
 *    any state it has not been taught about is a KEEP. It is called with what the database says
 *    *now*, not with what the caller believes.
 *  - **The database is re-read, not trusted.** The runner has just written the transcript, but the
 *    write is asked about again rather than assumed: a failed or partial write would otherwise be
 *    invisible and the delete would go ahead on the strength of a belief.
 *  - **Segments are counted, not just the state.** A DONE row with no words is a transcript of
 *    nothing, and deleting the audio for it would be the worst possible trade.
 *  - **It runs after the DONE mark, on the success path only.** A stop, an abort, a failure and a
 *    refusal for length all return from the runner before reaching it, so the audio survives every
 *    one of them and the file is offered again — see [RecordingsRepository] for where such a file
 *    then shows up.
 *
 * ## Why it does not use the ordinary delete
 *
 * [RecordingCatalog.removeName] runs [com.baba.callvault.data.transcripts.TranscriptCascade], which
 * deletes the transcript, the summary, the note and the tags. Calling it here would wipe the exact
 * text this function exists to keep. The catalog row still has to go — a row with no copies is a
 * dangling entry the list, the sweeps and the merge candidates would all have to reason about — so
 * it is dropped by [RecordingCatalog.forgetName], which drops the row and nothing else.
 */
object TranscribeOnlyAudio {

    private const val TAG = "CV:TranscribeOnly"

    /** Whether the audio may go, and — when it may not — which condition said no. */
    enum class Verdict {
        /** Every condition holds: transcribe-only, DONE, and words actually stored. */
        DELETE,

        /** A call, or an import the user asked to keep. Its audio is theirs. */
        KEEP_NOT_TRANSCRIBE_ONLY,

        /** No transcript row at all — stopped, released as stale, or never enqueued. */
        KEEP_NO_TRANSCRIPT,

        /** Queued, running or failed. The run may still produce the words, or may be retried. */
        KEEP_NOT_DONE,

        /** DONE, but nothing was stored. A transcript of nothing is not worth a file for. */
        KEEP_NO_WORDS,
    }

    /**
     * Whether [displayName]'s audio may be deleted, given what the transcripts database says.
     *
     * Pure, and tested as one, because every wrong answer here is silent: too eager destroys audio
     * that cannot be recovered, too shy leaves a file the user asked not to keep. The order is the
     * cheapest and most decisive question first.
     *
     * @param state the stored transcript's state, or null when there is no row.
     * @param segmentCount how many segments were stored for it.
     */
    fun verdictFor(displayName: String, state: TranscriptState?, segmentCount: Int): Verdict = when {
        !ImportedRecording.isTranscribeOnly(displayName) -> Verdict.KEEP_NOT_TRANSCRIBE_ONLY
        state == null -> Verdict.KEEP_NO_TRANSCRIPT
        state != TranscriptState.DONE -> Verdict.KEEP_NOT_DONE
        segmentCount <= 0 -> Verdict.KEEP_NO_WORDS
        else -> Verdict.DELETE
    }

    /**
     * Deletes [displayName]'s audio if — and only if — [verdictFor] says it may.
     *
     * Called from the transcription runner at the one moment it can be true: straight after the
     * segments and the DONE row have been written. Never throws; a failure here leaves the file in
     * place, which is the safe direction and is what the next finished run would try again from.
     *
     * @return true when the audio was deleted.
     */
    suspend fun deleteAfterTranscript(context: Context, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            // Cheapest gate first, and the only one that needs no database: a call or a kept import
            // never gets as far as opening the transcripts database on this path.
            if (!ImportedRecording.isTranscribeOnly(displayName)) return@withContext false
            // Should be impossible — the transcript has just been written — but asked anyway rather
            // than materialising a database as a side effect of a delete decision.
            if (!TranscriptDatabase.exists(context)) {
                AppLogger.w(TAG, "No transcripts database; keeping the audio of '$displayName'")
                return@withContext false
            }

            val verdict = runCatching {
                val dao = TranscriptDatabase.get(context).transcriptDao()
                verdictFor(
                    displayName = displayName,
                    state = dao.findTranscript(displayName)?.state,
                    segmentCount = dao.segmentsFor(displayName).size,
                )
            }.getOrElse { e ->
                // A read that failed says nothing about whether the words are safe, so it says keep.
                AppLogger.w(TAG, "Could not check the transcript of '$displayName': ${e.message}")
                return@withContext false
            }

            if (verdict != Verdict.DELETE) {
                AppLogger.i(TAG, "Keeping the audio of '$displayName' ($verdict)")
                return@withContext false
            }

            val deleted = deleteAudio(context, displayName)
            // The row goes whether or not the file did. A catalog row whose copies are gone is a row
            // the list would draw and the player would fail to open; and if the file somehow
            // survived, the next catalog re-seed finds it and offers it again, which is recoverable.
            // Never removeName(): that runs the transcript cascade over the text just written.
            RecordingCatalog.forgetName(context, displayName)
            AppLogger.i(TAG, "Transcript stored for '$displayName'; audio ${if (deleted) "deleted" else "not found"}")
            deleted
        }

    /**
     * Removes every copy of [displayName] from the folders CallVault knows about.
     *
     * Both folders are walked even though an import is never uploaded to Drive
     * ([com.baba.callvault.system.storage.CloudCopyPolicy] refuses it): a folder the user has since
     * pointed at something else, or a copy made by hand, is cheap to check for and expensive to
     * leave behind. Mirrors [RecordingsRepository.deleteRecording]'s second step deliberately —
     * what it must not mirror is the catalog call at the end of it.
     */
    private fun deleteAudio(context: Context, displayName: String): Boolean {
        val prefs = AppPreferences(context)
        var deletedAny = false
        for (folderUri in listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri())) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@runCatching
                for (doc in tree.listFiles()) {
                    if (doc.isFile && doc.name == displayName) {
                        if (SafHelper.deleteDocument(doc, "the transcribed import '$displayName'")) {
                            deletedAny = true
                        }
                    }
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "Failed to delete '$displayName' in $folderUri: ${e.message}")
            }
        }
        return deletedAny
    }
}
