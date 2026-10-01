/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.interop

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.utils.AppLogger

/**
 * Writes a transcript/notes sidecar for every existing recording, once, after the user turns the
 * sidecar setting on — so text they already have is backed up too, not only text created from now on.
 *
 * Runs in the background (it can touch many recordings and SAF files) and survives leaving Settings,
 * unlike a view-model coroutine. Best-effort; [TranscriptSidecar.backfillAll] never throws.
 */
class TranscriptSidecarBackfillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        TranscriptSidecar.backfillAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "CV:SidecarBackfill"
        private const val WORK_NAME = "transcript_sidecar_backfill"

        /** Enqueues the one-time backfill. Safe to call again: a running/queued pass is kept (KEEP). */
        fun enqueue(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<TranscriptSidecarBackfillWorker>().build(),
                )
            }.onFailure { AppLogger.w(TAG, "Could not enqueue the transcript sidecar backfill: ${it.message}") }
        }
    }
}
