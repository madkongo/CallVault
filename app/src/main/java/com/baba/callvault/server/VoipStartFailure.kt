/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Why an app-call capture never started in the recorder, handed to the app (review N21).
 *
 * `startVoipRecording` answers before the capture is built, so a capture that then fails to open was known only to
 * the recorder's own log: a vivo V2546A (Android 17) lost 23 app calls that way with no word to the user. The
 * recorder puts this line in its capture-diagnostics slot (`captureDiagnostics()`, which the app already reads when
 * the call ends), and the app recognises it with [reasonOf]. No AIDL change: an older recorder simply never sends it.
 */
internal object VoipStartFailure {

    private const val PREFIX = "VoIP capture did not start: "

    /** The longest line handed over; the reason is only for the log, and a stack-sized message is not. */
    const val MAX_LINE = 300

    /** One line naming the root cause of [error]. */
    fun line(error: Throwable): String {
        val root = VendorConstructorCrash.rootCause(error)
        val reason = listOfNotNull(root.javaClass.simpleName, root.message?.takeIf { it.isNotBlank() }).joinToString(": ")
        return (PREFIX + reason).replace(Regex("[\\r\\n]+"), " ").take(MAX_LINE)
    }

    /**
     * The reason in [diagnostics] when it is a start failure from [line]; null for ordinary capture health. [line] is
     * one line, so the reason ends at any line break (a newer recorder adds its recovery summary after one).
     */
    fun reasonOf(diagnostics: String?): String? =
        diagnostics?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('\n')
}
