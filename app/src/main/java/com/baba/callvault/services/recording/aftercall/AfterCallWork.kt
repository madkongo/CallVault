/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy.UnansweredAction
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * The delayed jobs of keep-or-delete, one unique work per recording so a later request replaces an earlier
 * one and each can be cancelled by name:
 * - `after_call_delete_<name>`: the real delete, after the Undo window.
 * - `after_call_unanswered_<name>`: the 24-hour check that applies "If I don't answer".
 * - `after_call_keep_<name>`: the held hand-offs after Keep, off the broadcast thread.
 *
 * WorkManager, not a Handler or an alarm: these must survive the process being killed in between, which on
 * the phones this app targets is the normal case, not the exception.
 */
internal object AfterCallWork {

    private const val TAG = "CV:AfterCall"
    const val KEY_NAME = "display_name"
    const val KEY_URI = "uri"
    const val KEY_MIME = "mime"
    private const val DELETE_PREFIX = "after_call_delete_"
    private const val UNANSWERED_PREFIX = "after_call_unanswered_"
    private const val KEEP_PREFIX = "after_call_keep_"

    /** @param replace REPLACE for a fresh request; KEEP when restoring, so a scheduled delete is never pushed back. */
    fun scheduleDelete(context: Context, displayName: String, delayMs: Long, replace: Boolean): Boolean =
        enqueue<AfterCallDeleteWorker>(context, DELETE_PREFIX + displayName, workDataOf(KEY_NAME to displayName), delayMs, replace)

    fun cancelDelete(context: Context, displayName: String) = cancel(context, DELETE_PREFIX + displayName)

    /**
     * Scheduled for every held recording, whatever "If I don't answer" says now. The worker reads the setting
     * when it runs, so Keep there means the held hand-offs do eventually run: without the check, a question
     * swiped away would leave its recording with no Drive copy and no transcript for good.
     */
    fun scheduleUnansweredCheck(context: Context, displayName: String, delayMs: Long, replace: Boolean): Boolean =
        enqueue<AfterCallUnansweredWorker>(context, UNANSWERED_PREFIX + displayName, workDataOf(KEY_NAME to displayName), delayMs, replace)

    fun cancelUnansweredCheck(context: Context, displayName: String) = cancel(context, UNANSWERED_PREFIX + displayName)

    /** The held hand-offs of a kept recording, at once. False when it could not be enqueued. */
    fun scheduleKeep(context: Context, held: AfterCallDecisions.Held): Boolean = enqueue<AfterCallKeepWorker>(
        context,
        KEEP_PREFIX + held.displayName,
        workDataOf(KEY_NAME to held.displayName, KEY_URI to held.uri.toString(), KEY_MIME to held.mimeType),
        delayMs = 0L,
        replace = true,
    )

    private inline fun <reified W : CoroutineWorker> enqueue(
        context: Context,
        uniqueName: String,
        input: Data,
        delayMs: Long,
        replace: Boolean,
    ): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<W>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, policy, request)
        true
    }.getOrElse {
        AppLogger.w(TAG, "Could not schedule after-call work: ${it.message}")
        false
    }

    private fun cancel(context: Context, uniqueName: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(uniqueName) }
            .onFailure { AppLogger.w(TAG, "Could not cancel after-call work: ${it.message}") }
    }
}

/** Deletes a recording once its Undo window has passed. */
class AfterCallDeleteWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(AfterCallWork.KEY_NAME) ?: return Result.success()
        AfterCallDecision.deleteNow(applicationContext, name, fromUnansweredCheck = false)
        return Result.success()
    }
}

/** Runs a kept recording's held hand-offs. The entry is already released; this only does the work. */
class AfterCallKeepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(AfterCallWork.KEY_NAME) ?: return Result.success()
        val uri = inputData.getString(AfterCallWork.KEY_URI)?.toUri() ?: return Result.success()
        val mime = inputData.getString(AfterCallWork.KEY_MIME) ?: return Result.success()
        AfterCallDecision.runHeldHandOffs(applicationContext, name, uri, mime)
        return Result.success()
    }
}

/** Applies "If I don't answer" to a question still unanswered after 24 hours. */
class AfterCallUnansweredWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val name = inputData.getString(AfterCallWork.KEY_NAME) ?: return Result.success()
        val held = AfterCallDecisions.peek(context, name)
        val facts = AfterCallPolicy.HeldFacts(
            stillPending = held != null,
            // Unknown (the catalog could not be read) counts as present: the rest of the rules still apply.
            inCatalog = held == null || RecordingCatalog.contains(context, name) != false,
            deleteRequested = held?.deleteRequested == true,
            engaged = held != null && AfterCallEngagement.isEngaged(context, name),
        )
        when (AfterCallPolicy.unansweredAction(AppPreferences(context).getAfterCallUnanswered(), facts)) {
            UnansweredAction.NOTHING -> Unit
            UnansweredAction.RELEASE -> AfterCallDecision.releaseOnly(context, name)
            UnansweredAction.KEEP -> AfterCallDecision.keep(context, name, fromUnansweredCheck = true)
            UnansweredAction.DELETE -> AfterCallDecision.deleteNow(context, name, fromUnansweredCheck = true)
        }
        return Result.success()
    }
}
