/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.baba.callvault.utils.AppLogger

/**
 * Asks for a summary, and stops one.
 *
 * **There is no schedule here**, unlike [com.baba.callvault.transcription.TranscriptionScheduler].
 * Transcription has modes because it is something the user wants to happen to every call; a summary
 * is asked for one call at a time, when someone has decided to deal with that call. At about ninety
 * seconds of full CPU and gigabytes of memory each, a nightly sweep would be hours of heat for pages
 * nobody requested.
 */
object SummaryScheduler {

    private const val TAG = "CV:SummaryScheduler"

    /** What marks a job as ours. See [tagFor] and [displayNameOfTag]. */
    private const val TAG_PREFIX = "cv_summary:"

    /** Unique work for the summary queue. One name, so two taps queue rather than collide. */
    const val WORK_NAME = "cv_summary_now"

    /**
     * Queues a summary of [displayName].
     *
     * No network constraint — everything happens on the device — and no charging constraint either:
     * this is something the user asked for and is waiting on, so making it wait for a charger would
     * be answering a request with a condition they never agreed to.
     *
     * `APPEND_OR_REPLACE` so two recordings tapped in a row are both summarised, while still never
     * running two at once. Running two would be pointless anyway: [SummaryEngine] serialises on a
     * mutex, and two models will not fit in memory together.
     */
    fun runNow(context: Context, displayName: String, model: SummaryModel = SummaryModel.DEFAULT) {
        val request = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(
                workDataOf(
                    SummaryWorker.KEY_DISPLAY_NAME to displayName,
                    SummaryWorker.KEY_MODEL_ID to model.id
                )
            )
            // Tagged, because a tag is the only thing that says which recording a job belongs to
            // once it has finished. See [tagFor].
            .addTag(tagFor(displayName))
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        AppLogger.i(TAG, "Summary requested with ${model.id}")
    }

    /**
     * Stops whatever is being summarised.
     *
     * The abort comes first, because it is the only thing that actually stops the work: cancelling
     * the worker does not interrupt a generate, which sits inside one long native call. Same lesson
     * as whisper — a cancelled worker left the phone at full CPU until the call finished on its own.
     */
    fun stopNow(context: Context) {
        SummaryEngine.requestAbort()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        AppLogger.i(TAG, "Summary stopped")
    }

    /**
     * The tag identifying the job for [displayName].
     *
     * **Tags outlive the run; progress does not.** The card used to work out which recording a job
     * belonged to by reading the name out of `WorkInfo.progress` — and WorkManager clears progress
     * the moment a worker finishes. So the instant a run ended, success or failure, the job stopped
     * being recognised and the card fell back to offering the summary again as though nothing had
     * happened. It also made the failed state unreachable: the worker reported the failure and
     * nothing could see it.
     */
    fun tagFor(displayName: String): String = TAG_PREFIX + displayName

    /**
     * The recording [tag] belongs to, or null when it is not one of ours.
     *
     * The inverse of [tagFor], and the only way a **list** can find out what the queue is doing:
     * every job carries WorkManager's own tags too (the worker's class name, and the unique-work
     * name), so a caller reading tags has to be able to say which ones mean nothing to it.
     *
     * Split from the queue itself because the Summaries page reads one flow of work infos for the
     * whole list rather than one observer per row, and this is the step that turns them back into
     * recordings.
     */
    fun displayNameOfTag(tag: String): String? =
        tag.removePrefix(TAG_PREFIX).takeIf { it.length != tag.length && it.isNotEmpty() }

    /** The recording a *running* summary is for, or null. Only meaningful mid-run. */
    fun displayNameOf(progress: Data): String? = progress.getString(SummaryWorker.KEY_DISPLAY_NAME)

    /** How far through, 0-100, or null when nothing has been reported yet. */
    fun percentOf(progress: Data): Int? =
        progress.getInt(SummaryWorker.KEY_PERCENT, -1).takeIf { it >= 0 }
}
