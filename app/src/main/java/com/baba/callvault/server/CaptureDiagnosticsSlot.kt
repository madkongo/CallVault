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
 */
internal class CaptureDiagnosticsSlot {

    @Volatile
    var value: String = ""
        private set

    /** A new app-call start was accepted: nothing from an earlier call may be read as this one's. */
    fun startAccepted() {
        value = ""
    }

    fun startFailed(error: Throwable) {
        value = VoipStartFailure.line(error)
    }

    /** A stop tore [session] down. With no session there is nothing new to say, so a start failure stays readable. */
    fun sessionEnded(session: RecordingSession?) {
        if (session == null) return
        value = runCatching { session.captureDiagnostics() }.getOrElse {
            AppLogger.w(TAG, "Could not read the capture's health: ${it.message}")
            ""
        }
    }

    private companion object {
        const val TAG = "CV:RecorderServer"
    }
}
