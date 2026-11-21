/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * The recorder's answer to `captureDiagnostics()`: what the last capture noticed about its own health, or why the
 * last app-call capture never started ([VoipStartFailure]).
 *
 * Review N21: the reason must reach the app for its own call and only that call. A stop that finds no session — the
 * app's stop arriving while the slow VoIP start is still being built, then failing — leaves the reason in place, and
 * an accepted app-call start clears what the previous call left.
 *
 * Review N21b: either answer carries, after [CaptureRecoveryLog.MARKER], the summary of how the capture's records had
 * to be built or started when the normal way failed — nothing at all when every one was built normally.
 */
internal class CaptureDiagnosticsSlot(
    private val recovery: CaptureRecoveryLog = CaptureRecoveryLog.current,
) {

    @Volatile
    var value: String = ""
        private set

    /** A new app-call start was accepted: nothing from an earlier call may be read as this one's. */
    fun startAccepted() {
        value = ""
        recovery.reset()
    }

    fun startFailed(error: Throwable) {
        value = CaptureRecoveryLog.append(VoipStartFailure.line(error), recovery.drain())
    }

    /** A stop tore [session] down. With no session there is nothing new to say, so a start failure stays readable. */
    fun sessionEnded(session: RecordingSession?, routeLine: String = "") {
        if (session == null) return
        val health = runCatching { session.captureDiagnostics() }.getOrElse {
            AppLogger.w(TAG, "Could not read the capture's health: ${it.message}")
            ""
        }
        // The route leads (review C131); the capture's own health follows it unchanged.
        val withRoute = listOf(routeLine, health).filter { it.isNotEmpty() }.joinToString("\n")
        value = CaptureRecoveryLog.append(withRoute, recovery.drain())
    }

    private companion object {
        const val TAG = "CV:RecorderServer"
    }
}
