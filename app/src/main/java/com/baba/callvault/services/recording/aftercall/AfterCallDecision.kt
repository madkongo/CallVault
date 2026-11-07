/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.system.interop.MetadataSidecar
import com.baba.callvault.system.storage.StorageRouter
import com.baba.callvault.transcription.TranscriptionScheduler
import com.baba.callvault.ui.screens.AfterCallOverlay
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.runBlocking

/**
 * What each keep-or-delete answer does. Plain functions on an object, so a receiver, a worker and (later) the
 * pop-up can all call them without a ViewModel.
 *
 * While a recording is held it is catalogued and playable but has no Drive copy and no automatic transcript:
 * [keep] is what finally runs those hand-offs, exactly as the call-end path would have. Answers are made
 * idempotent by [AfterCallDecisions.release], which hands the held entry to one caller only — a second answer
 * (from the pop-up, or the 24-hour check) finds nothing and only tidies up.
 */
object AfterCallDecision {

    private const val TAG = "CV:AfterCall"

    /**
     * Called by both finalize paths: holds the recording when the user asked to be asked AND the question can
     * be shown, and schedules its 24-hour check at once, so a hold is never left without a way out even if
     * everything after this throws. Returns false — keep it as in Keep mode — otherwise.
     */
    fun tryHold(context: Context, displayName: String, uri: Uri, mimeType: String, isVoip: Boolean): Boolean {
        if (!AfterCallPolicy.shouldAsk(AppPreferences(context).getAfterCallMode())) return false
        if (!AfterCallNotifier.canAsk(context)) {
            AppLogger.w(TAG, "Asked to ask after calls, but notifications are off or blocked; keeping the recording")
            return false
        }
        val held = AfterCallDecisions.hold(context, displayName, uri, mimeType, isVoip) ?: return false
        AfterCallWork.scheduleUnansweredCheck(context, displayName, AfterCallPolicy.UNANSWERED_DELAY_MS, replace = true)
        AppLogger.i(TAG, "Held a ${if (held.isVoip) "VoIP" else "carrier"} recording for keep-or-delete")
        return true
    }

    /**
     * Asks about a held recording at call end. Called once its catalog row exists, so Keep never races it.
     *
     * Asks the way the user chose. Pop-up (when chosen and Android allows it) shows the pop-up alone; the
     * notification asks instead when the pop-up cannot be drawn (phone locked, no permission, window refused) or
     * is closed unanswered (see [askByNotification]), so a call is never left without its question. Only this
     * call-end path opens the pop-up: startup and Undo re-post the notification alone, since a window appearing
     * out of nowhere long after the call would be a surprise.
     *
     * Changed 2026-11-07 at the maintainer's request: it used to post the notification as well, every time.
     */
    fun ask(context: Context, displayName: String) {
        val held = runCatching { AfterCallDecisions.peek(context, displayName) }
            .onFailure { AppLogger.w(TAG, "Could not read the held recording to ask about: ${it.message}") }
            .getOrNull() ?: return
        if (shouldPopUp(context)) {
            val opened = runCatching { popUpOpener(context) }
                .onFailure { AppLogger.w(TAG, "Could not open the keep-or-delete pop-up; asking by notification: ${it.message}") }
                .isSuccess
            if (opened) return
        }
        runCatching { AfterCallNotifier.postQuestion(context, held) }
            .onFailure { AppLogger.w(TAG, "Could not ask keep-or-delete: ${it.message}") }
    }

    /**
     * The pop-up could not be drawn or was closed without an answer: every question still waiting (not swiped
     * away, no Delete tapped) is asked in the notification shade instead.
     */
    fun askByNotification(context: Context) {
        AfterCallDecisions.all(context)
            .filter { AfterCallPolicy.shouldRepostQuestion(it.deleteRequested, it.dismissed) }
            .forEach { held ->
                runCatching { AfterCallNotifier.postQuestion(context, held) }
                    .onFailure { AppLogger.w(TAG, "Could not ask keep-or-delete by notification: ${it.message}") }
            }
    }

    /** Opens the pop-up window. A seam so tests can see the call without drawing an overlay. */
    @androidx.annotation.VisibleForTesting
    internal var popUpOpener: (Context) -> Unit = { AfterCallOverlay.show(it) }

    private fun shouldPopUp(context: Context): Boolean = AfterCallPolicy.shouldPopUp(
        style = AppPreferences(context).getAfterCallAskStyle(),
        canDrawOverlays = Settings.canDrawOverlays(context),
        keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true,
    )

    /** Keep: end the wait and run the held Drive copy, details-file copy and transcription. */
    fun keep(context: Context, displayName: String): Boolean = keep(context, displayName, fromUnansweredCheck = false)

    internal fun keep(context: Context, displayName: String, fromUnansweredCheck: Boolean): Boolean {
        val held = AfterCallDecisions.release(context, displayName)
        AfterCallWork.cancelDelete(context, displayName)
        // Never from inside the check itself: cancelling the running worker would stop it mid-hand-off.
        if (!fromUnansweredCheck) AfterCallWork.cancelUnansweredCheck(context, displayName)
        if (held == null) return false
        AfterCallNotifier.cancel(context, held.notificationId)
        // In a worker, not here: the details-file copy searches the recordings folder through SAF, which on a
        // large folder can outlast a broadcast's time budget. Inline only when already in a worker, or when
        // the work cannot be enqueued — never dropped.
        if (fromUnansweredCheck || !AfterCallWork.scheduleKeep(context, held)) runHeldHandOffs(context, held)
        AppLogger.i(TAG, "Kept a held recording${if (fromUnansweredCheck) " (no answer in 24 h)" else ""}")
        return true
    }

    /**
     * Keeps every held recording. For when "When a call ends" goes back to Keep: questions the user is no
     * longer being asked must not leave their recordings without a Drive copy or a transcript. Not one whose
     * Delete was tapped: that was an answer, and its delete is left to finish.
     */
    fun keepAll(context: Context): Int =
        AfterCallDecisions.all(context).filterNot { it.deleteRequested }.count { keep(context, it.displayName) }

    /**
     * Delete: show "Recording deleted · Undo" and delete for real after the Undo window. The entry stays held
     * meanwhile, so no sweep copies or transcribes a recording that is on its way out; it is marked, so the
     * 24-hour check deletes rather than keeps it if the delete work is ever lost. If the delete cannot be
     * scheduled at all it happens now, with no Undo — never left held.
     */
    fun delete(context: Context, displayName: String) {
        val held = AfterCallDecisions.setDeleteRequested(context, displayName, requested = true) ?: return
        AfterCallNotifier.postDeleted(context, held)
        if (!AfterCallWork.scheduleDelete(context, displayName, AfterCallPolicy.DELETE_UNDO_WINDOW_MS, replace = true)) {
            AppLogger.w(TAG, "Could not schedule the delete; deleting now")
            runBlocking { deleteNow(context, displayName, fromUnansweredCheck = false) }
            return
        }
        AppLogger.i(TAG, "Delete requested; the recording goes after the Undo window")
    }

    /** Undo: cancel the pending delete and ask again. */
    fun undo(context: Context, displayName: String) {
        AfterCallWork.cancelDelete(context, displayName)
        // Null: the delete already ran, and it cleared the notification on its way.
        val held = AfterCallDecisions.setDeleteRequested(context, displayName, requested = false) ?: return
        AfterCallNotifier.postQuestion(context, held)
        AppLogger.i(TAG, "Delete undone; asking again")
    }

    /** The question was swiped away: still held, the 24-hour check still decides, but startup will not re-ask. */
    fun dismissed(context: Context, displayName: String) {
        AfterCallDecisions.setDismissed(context, displayName)
    }

    /**
     * Deletes a held recording everywhere — file, sidecars in both folders, catalog row and everything that
     * cascades from it — and ends the wait. Called from the delete worker and the 24-hour check.
     */
    internal suspend fun deleteNow(context: Context, displayName: String, fromUnansweredCheck: Boolean): Boolean {
        val held = AfterCallDecisions.release(context, displayName)
        if (!fromUnansweredCheck) AfterCallWork.cancelUnansweredCheck(context, displayName)
        if (held == null) return false
        AfterCallNotifier.cancel(context, held.notificationId)
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

    /** Ends the wait and does nothing else: the recording is no longer in the catalog (a merge took it). */
    internal fun releaseOnly(context: Context, displayName: String) {
        val held = AfterCallDecisions.release(context, displayName) ?: return
        AfterCallWork.cancelDelete(context, displayName)
        AfterCallNotifier.cancel(context, held.notificationId)
        AppLogger.i(TAG, "A held recording left the catalog; its question was withdrawn")
    }

    /**
     * Puts back what a killed process or a reboot can take away from a held recording: its 24-hour check
     * (KEEP, so a scheduled one is never pushed back; with what is left of the 24 hours, so an overdue one
     * runs now), its pending delete, and its question — unless the user swiped that away. Idempotent: run at
     * every app start, off the main thread.
     */
    fun reconcile(context: Context) {
        // Asking was switched off with recordings still waiting (or the switch's own keep-all was killed):
        // nobody will be asked about them, so keep them now. Those with Delete tapped are restored below.
        if (AppPreferences(context).getAfterCallMode() == AppPreferences.AfterCallMode.KEEP) {
            val kept = keepAll(context)
            if (kept > 0) AppLogger.i(TAG, "Asking is off; kept $kept waiting recording(s) at start")
        }
        val now = System.currentTimeMillis()
        val held = AfterCallDecisions.all(context)
        held.forEach { entry ->
            runCatching {
                val name = entry.displayName
                AfterCallWork.scheduleUnansweredCheck(
                    context, name, AfterCallPolicy.remainingUnansweredDelayMs(entry.askedAt, now), replace = false,
                )
                if (entry.deleteRequested) {
                    AfterCallWork.scheduleDelete(context, name, AfterCallPolicy.DELETE_UNDO_WINDOW_MS, replace = false)
                }
                if (AfterCallPolicy.shouldRepostQuestion(entry.deleteRequested, entry.dismissed)) {
                    AfterCallNotifier.postQuestion(context, entry)
                }
            }.onFailure { AppLogger.w(TAG, "Could not restore a held recording's question: ${it.message}") }
        }
        if (held.isNotEmpty()) AppLogger.i(TAG, "Restored ${held.size} held recording(s) at start")
    }

    /**
     * The three hand-offs the finalize paths skip while a recording is held, in the order the VoIP path runs
     * them. Each on its own so one failing (Drive folder gone, say) does not cost the others.
     */
    internal fun runHeldHandOffs(context: Context, displayName: String, uri: Uri, mimeType: String) {
        runCatching { TranscriptionScheduler.transcribeAfterCallIfEnabled(context, displayName) }
            .onFailure { AppLogger.w(TAG, "Could not queue transcription for a kept recording: ${it.message}") }
        runCatching { StorageRouter.route(context, uri, displayName, mimeType) }
            .onFailure { AppLogger.w(TAG, "Could not route a kept recording: ${it.message}") }
        runCatching {
            StorageRouter.routeSidecar(context, displayName, MetadataSidecar.sidecarNameFor(displayName), "application/json")
        }.onFailure { AppLogger.w(TAG, "Could not route a kept recording's details file: ${it.message}") }
    }

    private fun runHeldHandOffs(context: Context, held: AfterCallDecisions.Held) =
        runHeldHandOffs(context, held.displayName, held.uri, held.mimeType)
}
