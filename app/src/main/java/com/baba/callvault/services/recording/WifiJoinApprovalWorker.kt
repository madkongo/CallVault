/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Shizuku mode's backup check of the current access point (review N15), queued by [WifiJoinWatch] when the recorder
 * cannot watch Wi-Fi joins itself (its watch normally does this, in the recorder).
 *
 * Starts [JOIN_SETTLE_MS] after it is queued, so Android has finished its own "Wi-Fi changed" handling; waits up to
 * [RECORDER_WAIT_MS] for a cold-started app to reconnect to the recorder Shizuku keeps running (the app start binds
 * it), then runs [ShizukuWifiApproval]. Not expedited: on Android 11 an expedited request needs a foreground
 * notification, which this feature must not add.
 */
class WifiJoinApprovalWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val back = awaitRecorder(
            alive = { RecorderConnection.service?.asBinder()?.isBinderAlive == true },
            timeoutMs = RECORDER_WAIT_MS,
            pollMs = RECORDER_POLL_MS,
            sleep = Thread::sleep,
        )
        if (!back) {
            val line = "Wi-Fi approval (Shizuku): the recorder was not back within ${RECORDER_WAIT_MS / 1000} s of the join"
            AppLogger.w(TAG, line)
            runCatching { TransportTimeline.event(app, line) }
                .onFailure { AppLogger.w(TAG, "Could not write the transport timeline: ${it.message}") }
        }
        // Run either way: it re-checks the recorder and logs "recorder not running" with the rest of its decisions.
        runCatching { ShizukuWifiApproval.forApp(app).approveIfWanted() }
            .onFailure { AppLogger.w(TAG, "Wi-Fi join approval failed: ${it.javaClass.simpleName}: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "CV:WifiApproveShizuku"
        private const val WORK_NAME = "shizuku-wifi-join-approval"

        /** After a join, before approving: Android writes its own "Wi-Fi changed" switch-off first (built-in mode's 4 s). */
        const val JOIN_SETTLE_MS = 4_000L

        /** How long a join waits for the recorder connection. */
        const val RECORDER_WAIT_MS = 15_000L
        const val RECORDER_POLL_MS = 500L

        /** Queues one approval. A join that arrives while one is queued or running runs after it, not instead of it. */
        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<WifiJoinApprovalWorker>()
                    .setInitialDelay(JOIN_SETTLE_MS, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not queue the Wi-Fi join approval: ${it.message}") }
        }

        /** Polls [alive] until true or [timeoutMs] has been waited. True when the recorder is there. */
        fun awaitRecorder(alive: () -> Boolean, timeoutMs: Long, pollMs: Long, sleep: (Long) -> Unit): Boolean {
            var waited = 0L
            while (!alive()) {
                if (waited >= timeoutMs) return false
                sleep(pollMs)
                waited += pollMs
            }
            return true
        }
    }
}
