/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Whether a capture the app asked for ever actually began.
 *
 * **Why this is needed.** `IRecorderService.startRecording` returns void and does its real work on a
 * worker afterwards, so a codec that will not configure throws *after* the call has returned. The
 * exception is caught inside the daemon — a process with no log file of its own — the output fd is
 * closed, and the app carries on believing a recording is running. The user gets no file, no error,
 * and nothing in the debug report they send.
 *
 * Issue #28's reporter hit this by switching the audio codec to AAC: "changing from Opus to AAC
 * failed to record the next call." We could not tell him why, because nothing anywhere recorded why.
 *
 * `isRecording()` has been in the AIDL since the beginning and was never asked. This decides what to
 * do with the answer, and every rule here exists to avoid crying wolf — a false "your codec is
 * broken" on an ordinary short call would be worse than the silence it replaces.
 */
object CaptureStartCheck {

    enum class Verdict(
        /** Whether this verdict is worth putting in front of the user. Only one is. */
        val isReportable: Boolean,
    ) {
        /** Capture is running. Nothing to say. */
        STARTED(false),

        /** The daemon is alive and simply never got a capture going — the failure this exists for. */
        NEVER_STARTED(true),

        /** The call ended before the check ran. Ordinary, and common on short calls. */
        STOPPED(false),

        /**
         * The daemon could not be reached, so this cannot tell a failed start from a dead daemon.
         *
         * Deliberately silent: a dead daemon has its own watch and its own message, and reporting it
         * here as well would show the user two different errors for one event.
         */
        UNKNOWN(false),

        /**
         * The recorder says it is recording, and nothing has reached this call's file after the second look
         * (review C2, belt-and-braces). Whatever it is recording, it is not this call: a failed start, said so.
         */
        NOTHING_WRITTEN(true),

        /** Recording, and the file is still empty at the first look: look once more before saying anything. */
        LOOK_AGAIN(false),
    }

    /**
     * @param daemonReachable whether the daemon answered at all.
     * @param isRecording what it said about its own capture.
     * @param stopRequested whether the call has already ended — checked FIRST, because a short call
     *   legitimately finishes before this runs and must never be reported as a broken codec.
     */
    fun verdict(
        daemonReachable: Boolean,
        isRecording: Boolean,
        stopRequested: Boolean,
        bytesWritten: Long? = null,
        finalLook: Boolean = false,
    ): Verdict = when {
        stopRequested -> Verdict.STOPPED
        !daemonReachable -> Verdict.UNKNOWN
        !isRecording -> Verdict.NEVER_STARTED
        // Null: the size cannot be read (no staged file); the recorder's own answer is all there is.
        bytesWritten == null || bytesWritten > 0L -> Verdict.STARTED
        finalLook -> Verdict.NOTHING_WRITTEN
        else -> Verdict.LOOK_AGAIN
    }
}
