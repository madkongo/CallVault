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
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.transcription.TranscriptionInFlight
import com.baba.callvault.utils.AppLogger

/**
 * Deletes everything attached to a recording when the recording goes away — its transcript, the note
 * the user wrote about it, and the summary a model wrote from it.
 *
 * Transcripts live in their own database ([TranscriptDatabase]) because the recordings catalog is a
 * destructible cache, which means a cross-database `ON DELETE CASCADE` is not available. This object
 * *is* the cascade, and it is called from [com.baba.callvault.data.recordings.RecordingCatalog] at
 * every point a catalog row is dropped.
 *
 * Getting this wrong is a privacy defect rather than an untidiness: the app would report a recording
 * as deleted while keeping a searchable transcript of the conversation, which is more exposing than
 * the audio it replaced.
 */
object TranscriptCascade {

    private const val TAG = "CV:TranscriptCascade"

    /**
     * Removes the transcripts (and their search rows), the notes, the cached shapes and the
     * summaries for [displayNames].
     *
     * The note matters here as much as the transcript: it is the user's own account of a private
     * call, so leaving it behind would keep a written record of a conversation they deleted. The
     * summary is the same defect wearing a machine's handwriting — it is a readable account of the
     * call, and an orphaned one would outlive the audio and the transcript both.
     *
     * Never throws: a failure here must not turn a working delete into a failed one. It is also a
     * no-op before the first transcription, so deleting a recording on a device that has never used
     * the feature does not create a database.
     */
    suspend fun deleteFor(context: Context, displayNames: Collection<String>) {
        if (displayNames.isEmpty()) return

        // Before anything is removed, and before the early return below: a recording can be deleted
        // while it is being transcribed, and whisper would otherwise grind on for minutes producing
        // a transcript of a call that no longer exists — holding the one transcription thread, so
        // everything queued behind it waits for a result that is thrown away (mirror176, issue #35).
        // The runner treats an abort as "no result": it removes the row and moves on to the next
        // recording, which is exactly the behaviour wanted here.
        if (TranscriptionInFlight.isAmong(displayNames)) {
            AppLogger.i(TAG, "Stopping the transcription of a recording that has just been deleted")
            TranscriptionEngine.requestAbort()
        }

        displayNames.forEach { TranscribeAgainBackup.clear(context, it) }
        if (!TranscriptDatabase.exists(context)) return

        runCatching {
            val dao = TranscriptDatabase.get(context).transcriptDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            AppLogger.w(TAG, "Failed to delete transcripts for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            displayNames.forEach { RecordingExtrasRepository.deleteFor(context, it) }
        }.onFailure {
            // Names are logged; note and transcript text never are.
            AppLogger.w(TAG, "Failed to delete notes for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            val dao = TranscriptDatabase.get(context).speakerTurnsDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            AppLogger.w(TAG, "Failed to delete speaker turns for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            val dao = TranscriptDatabase.get(context).summaryDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            // As above: the summary is an account of a private call and is never logged.
            AppLogger.w(TAG, "Failed to delete summaries for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            val dao = TranscriptDatabase.get(context).tagDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            // A tag is short, but it is the user's own word for what a call was about — an orphaned
            // `lawyer` or `hospital` is a readable record of a conversation they deleted, and it
            // would go on appearing in the filter row afterwards. Never logged, for that reason.
            AppLogger.w(TAG, "Failed to delete tags for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            val dao = TranscriptDatabase.get(context).favouriteDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            // Left behind, a star would silently re-attach itself to the next recording to reuse the
            // name, exempting a call the user never protected from the sweeps.
            AppLogger.w(TAG, "Failed to delete stars for ${displayNames.size} recording(s): ${it.message}")
        }

        runCatching {
            val dao = TranscriptDatabase.get(context).flagDao()
            displayNames.forEach { dao.deleteFor(it) }
        }.onFailure {
            // Orphaned marks would reattach to the next recording to reuse the name, putting the
            // user's bookmarks on a call they were never made in.
            AppLogger.w(TAG, "Failed to delete marks for ${displayNames.size} recording(s): ${it.message}")
        }
    }
}
