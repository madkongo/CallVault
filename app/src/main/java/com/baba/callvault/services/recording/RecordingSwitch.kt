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
import com.baba.callvault.data.RecordingState
import com.baba.callvault.server.BackendChoice
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Applies the user's master recording switch ([RecordingState]) and carries out the side effects of a
 * transition. The pref itself is what the arming gates read; this is only the lifecycle work around it:
 *
 * - **→ STOPPED (off):** stop the keep-alive (so its watchdog cannot relaunch what we tear down and its
 *   persistent notification goes away), then tear the daemon down — but never cut a call in progress
 *   (finish-current, apply-to-next). A reboot will not resurrect it, because [DaemonKeepAliveService.start]
 *   itself honours STOPPED.
 * - **→ ACTIVE (on):** bring the recorder back up (standalone via the keep-alive, Shizuku by binding).
 *
 * Teardown and bring-up make blocking calls, so **call this off the main thread.**
 */
object RecordingSwitch {
    private const val TAG = "CV:RecordingSwitch"

    fun apply(context: Context, to: RecordingState) {
        val prefs = AppPreferences(context)
        val from = prefs.getRecordingState()
        if (from == to) return
        prefs.setRecordingState(to)
        AppLogger.i(TAG, "Master recording switch: $from -> $to")
        when (to) {
            RecordingState.STOPPED -> tearDown(context)
            RecordingState.ACTIVE -> bringUp(context)
        }
    }

    private fun tearDown(context: Context) {
        // A "cannot record" warning from before the stop would outlive the reason for it: recording is off by choice (C61).
        runCatching { com.baba.callvault.system.health.SilentFailureNotifier.clearRecorderUnavailable(context) }
        // Stop the watchdog FIRST, synchronously, so a binder death from the teardown below cannot be
        // caught and answered with an immediate relaunch (the same ordering the mode switch relies on).
        runCatching { DaemonKeepAliveService.stop(context) }
            .onFailure { AppLogger.w(TAG, "Could not stop the keep-alive: ${it.message}") }
        // Finish-current, apply-to-next: never cut a call being recorded right now. A daemon left warm this
        // way is harmless — the arming gate blocks the next call, the keep-alive notification is already
        // gone, and a reboot will not bring it back (STOPPED is persisted and start() honours it).
        if (runCatching { RecorderConnection.service?.isRecording == true }.getOrDefault(false)) {
            AppLogger.i(TAG, "Stop requested during a recording — leaving the daemon to finish this call")
            return
        }
        when (BackendChoice.of(AppPreferences(context).getPrivilegedMode())) {
            BackendChoice.SHIZUKU -> RecorderBackend.retireShizukuService("the user stopped recording")
            BackendChoice.ADB ->
                runCatching { RecorderConnection.service?.destroy() }
                    .onFailure { AppLogger.w(TAG, "Could not stop the daemon: ${it.message}") }
        }
    }

    private fun bringUp(context: Context) {
        // Start the keep-alive anchor (its watchdog keeps the daemon up from here on)...
        DaemonKeepAliveService.start(context)
        // ...but warm the daemon RIGHT NOW rather than leaving it to the watchdog's first relaunch. The
        // watchdog polls once a minute and debounces (two down reads before it acts), so left to it a
        // fresh Start takes ~a minute to bring the recorder up — measured at ~60s on the OP9. That is long
        // enough to miss the first call, and a VoIP call that lands in the window cannot be armed late at
        // all. An explicit Start is the user asking for it now, so launch synchronously here (off the main
        // thread — ensureRunning blocks). ensureRunning covers both backends; the pref is already ACTIVE
        // by the time we get here, so its STOPPED gate lets this through.
        Thread({
            val up = runCatching { RecorderBackend.ensureRunning(context) }
                .onFailure { AppLogger.w(TAG, "Could not bring up the recorder on start: ${it.message}") }
                .getOrDefault(false)
            // Push the keep-alive notification to catch up the instant the daemon is actually up. Its
            // watchdog only re-reads readiness once a minute, so without this it sits on "starting up…"
            // long after recording is ready — which reads to the user as "it never came up" (the very
            // doubt that prompted this). A second start() re-posts the notification from the now-ready
            // state; it is idempotent.
            if (up) runCatching { DaemonKeepAliveService.start(context) }
        }, "cv-recorder-start").apply { isDaemon = true }.start()
    }
}
