/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * C3: the user tapped Update during a call and [CallInProgressGate] refused. The downloaded update is remembered as
 * "ready", and once the call is over ONE "Update ready, tap to install" notice is posted. Nothing installs by itself:
 * the notice opens the app like the "update available" one, and the user's tap on the Home banner installs (the gate
 * is asked again then; if another call is on, it defers again and the notice returns after that call).
 *
 * The three seams are lambdas so the rules are testable without a device.
 */
class UpdateReadyNotice(
    private val isPending: () -> Boolean,
    private val setPending: (Boolean) -> Unit,
    private val isCallFree: () -> Boolean,
    private val post: () -> Unit,
) {
    /** An install was just held back by a call: remember that an update is ready. */
    fun noteDeferred() = setPending(true)

    /** A call ended (any kind). Posts the notice once, only if one is owed and no call is up. */
    fun onCallEnded() {
        if (!isPending() || !isCallFree()) return
        setPending(false)
        post()
    }

    companion object {
        private const val TAG = "CV:UpdateReady"
        private const val WORK_NAME = "update_ready_after_call"

        /** Gives the audio mode a moment to leave the call before the gate is asked. */
        private const val SETTLE_SECONDS = 5L

        fun forContext(context: Context): UpdateReadyNotice {
            val app = context.applicationContext
            val prefs = AppPreferences(app)
            return UpdateReadyNotice(
                isPending = { prefs.isUpdateReadyPending() },
                setPending = { prefs.setUpdateReadyPending(it) },
                isCallFree = { CallInProgressGate.mayInstall(app) },
                post = {
                    val tag = prefs.getAvailableUpdateTag()
                    if (tag != null) UpdateNotifications.showUpdateReady(app, tag)
                },
            )
        }

        /** The existing call-ended signals (carrier IDLE, app-call end) call this; cheap when nothing is owed. */
        fun onAnyCallEnded(context: Context) {
            val app = context.applicationContext
            if (!AppPreferences(app).isUpdateReadyPending()) return
            runCatching {
                val request = OneTimeWorkRequestBuilder<UpdateReadyWorker>()
                    .setInitialDelay(SETTLE_SECONDS, TimeUnit.SECONDS).build()
                WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule the update-ready notice: ${it.message}") }
        }
    }
}

/** Runs [UpdateReadyNotice.onCallEnded] a few seconds after a call ended. */
class UpdateReadyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        UpdateReadyNotice.forContext(applicationContext).onCallEnded()
        return Result.success()
    }
}
