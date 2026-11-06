/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.system.storage.StorageRouter
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.utils.AppLogger

/**
 * What each keep-or-delete answer does. Plain functions on an object, so a receiver, a worker and (later) the
 * pop-up can all call them without a ViewModel.
 *
 * While a recording is held it is catalogued and playable but has no Drive copy and no automatic transcript:
 * [keep] is what finally runs those hand-offs, exactly as the call-end path would have. Answers are made
 * idempotent by [AfterCallDecisions.release], which hands the held entry to one caller only — a second answer
 * (from the pop-up, or the 24-hour check) finds nothing and only tidies the notification.
 */
object AfterCallDecision {

    private const val TAG = "CV:AfterCall"

    /**
     * Asks the question for a recording already [AfterCallDecisions.hold]-ed: posts the notification and
     * schedules the 24-hour check. Called once the catalog row exists, so Keep can never race the catalog.
     */
    fun ask(context: Context, displayName: String) {
        val held = AfterCallDecisions.peek(context, displayName) ?: return
        AfterCallNotifier.postQuestion(context, held)
        AfterCallWork.scheduleUnansweredCheck(context, displayName)
        AppLogger.i(TAG, "Asked keep-or-delete for a ${if (held.isVoip) "VoIP" else "carrier"} recording")
    }

    /** Keep: end the wait and run the held Drive copy, details-file copy and transcription. */
    fun keep(context: Context, displayName: String) = keep(context, displayName, fromUnansweredCheck = false)

    internal fun keep(context: Context, displayName: String, fromUnansweredCheck: Boolean): Boolean {
        val held = AfterCallDecisions.release(context, displayName)
        AfterCallWork.cancelDelete(context, displayName)
        // Never from inside the check itself: cancelling the running worker would stop it mid-hand-off.
        if (!fromUnansweredCheck) AfterCallWork.cancelUnansweredCheck(context, displayName)
        AfterCallNotifier.cancel(context, displayName)
        if (held == null) return false
        runHeldHandOffs(context, held)
        AppLogger.i(TAG, "Kept a held recording${if (fromUnansweredCheck) " (no answer in 24 h)" else ""}")
        return true
    }

    /**
     * Delete: show "Recording deleted · Undo" and delete for real after the Undo window. The entry stays held
     * meanwhile, so no sweep copies or transcribes a recording that is on its way out.
     */
    fun delete(context: Context, displayName: String) {
        if (AfterCallDecisions.peek(context, displayName) == null) {
            AfterCallNotifier.cancel(context, displayName)
            return
        }
        AfterCallNotifier.postDeleted(context, displayName)
        AfterCallWork.scheduleDelete(context, displayName)
        AppLogger.i(TAG, "Delete requested; the recording goes after the Undo window")
    }

    /** Undo: cancel the pending delete and ask again. */
    fun undo(context: Context, displayName: String) {
        AfterCallWork.cancelDelete(context, displayName)
        val held = AfterCallDecisions.peek(context, displayName)
        if (held == null) {
            // The delete already ran; there is nothing left to bring back.
            AfterCallNotifier.cancel(context, displayName)
            return
        }
        AfterCallNotifier.postQuestion(context, held)
        AppLogger.i(TAG, "Delete undone; asking again")
    }

    /**
     * Deletes a held recording everywhere — file, sidecars in both folders, catalog row and everything that
     * cascades from it — and ends the wait. Called from the delete worker and the 24-hour check.
     */
    internal suspend fun deleteNow(context: Context, displayName: String, fromUnansweredCheck: Boolean): Boolean {
        val held = AfterCallDecisions.release(context, displayName)
        if (!fromUnansweredCheck) AfterCallWork.cancelUnansweredCheck(context, displayName)
        AfterCallNotifier.cancel(context, displayName)
        if (held == null) return false
        val item = RecordingsRepository.RecordingItem(
            uri = held.uri,
            displayName = held.displayName,
            sizeBytes = 0L,
            lastModified = 0L,
            direction = null,
            displayDate = null,
            startedAtMillis = null,
            number = null,
        )
        val deleted = runCatching { RecordingsRepository.deleteRecording(context, item) }
            .onFailure { AppLogger.e(TAG, "Deleting a held recording failed", it) }
            .getOrDefault(false)
        AppLogger.i(TAG, "Deleted a held recording: removed=$deleted${if (fromUnansweredCheck) " (no answer in 24 h)" else ""}")
        return deleted
    }

    /**
     * The three hand-offs the finalize paths skip while a recording is held, in the order the VoIP path runs
     * them. Each on its own so one failing (Drive folder gone, say) does not cost the others.
     */
    private fun runHeldHandOffs(context: Context, held: AfterCallDecisions.Held) {
        val name = held.displayName
        runCatching { TranscriptionScheduler.transcribeAfterCallIfEnabled(context, name) }
            .onFailure { AppLogger.w(TAG, "Could not queue transcription for a kept recording: ${it.message}") }
        runCatching { StorageRouter.route(context, held.uri, name, held.mimeType) }
            .onFailure { AppLogger.w(TAG, "Could not route a kept recording: ${it.message}") }
        runCatching {
            StorageRouter.routeSidecar(context, name, MetadataSidecar.sidecarNameFor(name), "application/json")
        }.onFailure { AppLogger.w(TAG, "Could not route a kept recording's details file: ${it.message}") }
    }
}
