/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * What switching "Record app calls" on or off does, outside the Settings page's lifetime (review C13).
 *
 * The page used to run the arm and then the keep-alive restart in one job of its own coroutine scope. Since
 * the page leaves composition when it closes (item 135), closing Settings while the arm was running cancelled
 * the job after the arm and before the restart, and the restart is the only thing that starts app-call
 * detection: app calls then went undetected until something else restarted the keep-alive.
 *
 * Now the choice is saved and detection is started at once, on the tap (main thread, app in the foreground, so
 * the foreground-service start is allowed), and the arm runs in a process-wide scope. A call that starts inside
 * the arming window is detected while unarmed; the recorder refuses that start and the existing missed-app-call
 * notice says so. Nothing re-arms late or retries.
 */
object VoipToggleEffects {

    private const val TAG = "CV:VoipToggle"

    /** Outlives any screen: the arm must finish even if the page that asked for it is gone. */
    private val armScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @return the arm's result (true = armed, or nothing to arm), for the page's status line only.
     */
    fun apply(
        turnOn: Boolean,
        persist: (Boolean) -> Unit,
        startDetection: () -> Unit,
        arm: () -> Boolean,
        syncRingListener: () -> Unit = {},
        scope: CoroutineScope = armScope,
    ): Deferred<Boolean> {
        persist(turnOn)
        // The ring listener's approval follows the switch now, not at the next process start (review C19).
        runCatching(syncRingListener).onFailure { AppLogger.e(TAG, "Could not queue the ring listener sync: ${it.message}", it) }
        runCatching(startDetection).onFailure { AppLogger.e(TAG, "Could not start app-call detection: ${it.message}", it) }
        return scope.async {
            runCatching(arm).onFailure { AppLogger.e(TAG, "Arming app-call capture failed: ${it.message}", it) }
                .getOrDefault(false)
        }
    }

    /** The real effects. Call on the main thread, from the tap. */
    fun apply(context: Context, turnOn: Boolean): Deferred<Boolean> {
        val app = context.applicationContext
        return apply(
            turnOn = turnOn,
            persist = { AppPreferences(app).setVoipRecordingEnabled(it) },
            // Its onStartCommand re-syncs the detector with the new choice; the keep-alive runs in every mode
            // (KeepAlivePolicy); turning app calls off only stops the detector inside it.
            startDetection = { DaemonKeepAliveService.start(app) },
            arm = { VoipCaptureController.sync(app) },
            syncRingListener = { VoipRingListenerAccess.requestSync(app) },
        )
    }
}
