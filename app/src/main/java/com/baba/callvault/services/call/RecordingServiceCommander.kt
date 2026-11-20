/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.content.Intent
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.services.recording.RecordingForegroundService
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Sends the start / standby / stop command to [RecordingForegroundService] without ever throwing into the call
 * path (review N26). See [ForegroundStartGuard] for why Android may refuse.
 */
class RecordingServiceCommander(private val appContext: Context) {

    fun send(action: String, metadata: RecordingMetadata? = null) {
        val intent = Intent(appContext, RecordingForegroundService::class.java).apply {
            this.action = action
            if (metadata != null) putExtra(RecordingMetadata.EXTRA_METADATA, metadata)
        }
        if (action == RecordingForegroundService.ACTION_STOP_RECORDING) stop(action, intent) else start(action, intent)
    }

    private fun start(action: String, intent: Intent) {
        when (val outcome = ForegroundStartGuard.attempt { appContext.startForegroundService(intent) }) {
            ForegroundStartGuard.Outcome.Started -> ForegroundStartGuard.clearRefused(appContext)
            is ForegroundStartGuard.Outcome.Refused -> {
                CallEndLog.record(appContext, ForegroundStartGuard.describe(action, outcome.cause, batteryExempt()))
                ForegroundStartGuard.markRefused(appContext)
                runCatching { RecordingNotificationHelper(appContext).showBatteryExemptionNotification() }
                    .onFailure { CallEndLog.record(appContext, "could not post the start-refused notice: ${it.message}") }
            }
        }
    }

    /**
     * When Stopping, startService is used because the service is already running: it only carries a new Intent that
     * triggers cleanup (stopService would give the service very little time to finalise). If that is refused,
     * stopService is the last resort, and the result is logged. A refused stop is not a refused start: no flag, no notice.
     */
    private fun stop(action: String, intent: Intent) {
        val outcome = ForegroundStartGuard.attempt { appContext.startService(intent) }
        if (outcome !is ForegroundStartGuard.Outcome.Refused) return
        CallEndLog.record(appContext, ForegroundStartGuard.describe(action, outcome.cause, batteryExempt()))
        val fallback = ForegroundStartGuard.attempt { appContext.stopService(intent) }
        CallEndLog.record(
            appContext,
            "stop fallback stopService: " + when (fallback) {
                ForegroundStartGuard.Outcome.Started -> "done"
                is ForegroundStartGuard.Outcome.Refused -> "refused ${fallback.cause.javaClass.simpleName}"
            },
        )
    }

    private fun batteryExempt(): Boolean = runCatching { PermissionChecks.hasBatteryExemption(appContext) }.getOrDefault(false)
}
