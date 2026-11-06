/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy.UnansweredAction
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * The two delayed jobs of keep-or-delete, one unique work per recording so a later request replaces an
 * earlier one and either can be cancelled by name:
 * - `after_call_delete_<name>`: the real delete, after the Undo window.
 * - `after_call_unanswered_<name>`: the 24-hour check that applies "If I don't answer".
 *
 * WorkManager, not a Handler or an alarm: both must survive the process being killed in between, which on
 * the phones this app targets is the normal case, not the exception.
 */
internal object AfterCallWork {

    private const val TAG = "CV:AfterCall"
    const val KEY_NAME = "display_name"
    private const val DELETE_PREFIX = "after_call_delete_"
    private const val UNANSWERED_PREFIX = "after_call_unanswered_"

    fun scheduleDelete(context: Context, displayName: String) =
        enqueue<AfterCallDeleteWorker>(context, DELETE_PREFIX + displayName, displayName, AfterCallPolicy.DELETE_UNDO_WINDOW_MS)

    fun cancelDelete(context: Context, displayName: String) = cancel(context, DELETE_PREFIX + displayName)

    /**
     * Scheduled for every held recording, whatever "If I don't answer" says now. The worker reads the setting
     * when it runs, so Keep there means the held hand-offs do eventually run: without the check, a question
     * swiped away would leave its recording with no Drive copy and no transcript for good.
     */
    fun scheduleUnansweredCheck(context: Context, displayName: String) =
        enqueue<AfterCallUnansweredWorker>(context, UNANSWERED_PREFIX + displayName, displayName, AfterCallPolicy.UNANSWERED_DELAY_MS)

    fun cancelUnansweredCheck(context: Context, displayName: String) = cancel(context, UNANSWERED_PREFIX + displayName)

    private inline fun <reified W : CoroutineWorker> enqueue(context: Context, uniqueName: String, displayName: String, delayMs: Long) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<W>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_NAME to displayName))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { AppLogger.w(TAG, "Could not schedule after-call work: ${it.message}") }
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

/** Applies "If I don't answer" to a question still unanswered after 24 hours. */
class AfterCallUnansweredWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(AfterCallWork.KEY_NAME) ?: return Result.success()
        val action = AfterCallPolicy.unansweredAction(
            setting = AppPreferences(applicationContext).getAfterCallUnanswered(),
            stillPending = AfterCallDecisions.peek(applicationContext, name) != null,
        )
        when (action) {
            UnansweredAction.NOTHING -> Unit
            UnansweredAction.KEEP -> AfterCallDecision.keep(applicationContext, name, fromUnansweredCheck = true)
            UnansweredAction.DELETE -> AfterCallDecision.deleteNow(applicationContext, name, fromUnansweredCheck = true)
        }
        return Result.success()
    }
}
