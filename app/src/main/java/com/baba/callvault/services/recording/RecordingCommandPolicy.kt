/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.services.recording

/**
 * How [RecordingForegroundService] treats a command: post its notification first, just act, or — for a
 * command with no call behind it — end the service.
 *
 * Why not "always post first", as it used to: a service's notification is posted asynchronously by
 * system_server (`ServiceRecord.postNotification`, "to avoid deadlocks"), while the keep-alive puts "Ready"
 * back on the same id with a direct `notify()`. A STOP that posted on its way out could land after "Ready"
 * and stay in the shade for good (LAVA LXX508, 2026-09-26). Only the commands sent with
 * `startForegroundService` — START and STANDBY — must post, and a manual Record needs it to go foreground.
 *
 * Why orphans end the service: the notification's own actions (swipe, Pause, Resume, Mark, Record) reach
 * the service by `startService`. On a leftover notification they create a fresh service with no call; the
 * old code re-posted "Press to start recording" and stayed up, or raised "unexpected error" on Record.
 *
 * Pure, so every case is a unit test.
 */
object RecordingCommandPolicy {

    enum class Handling {
        /** Post the notification (Android requires it for a foreground start), then act. */
        POST_THEN_HANDLE,

        /** Act on the command without posting first. */
        HANDLE_ONLY,

        /** Nothing to act on: no call, no recording. Put "Ready" back and stop the service. */
        END_ORPHAN,
    }

    /**
     * @param action the command's intent action.
     * @param hasCall a call is being recorded or waited on — a session, or call details already held or
     *   just delivered with this command.
     */
    fun handling(action: String?, hasCall: Boolean): Handling = when (action) {
        RecordingForegroundService.ACTION_START_RECORDING,
        RecordingForegroundService.ACTION_STANDBY -> Handling.POST_THEN_HANDLE

        RecordingForegroundService.ACTION_STOP_RECORDING -> Handling.HANDLE_ONLY

        RecordingForegroundService.ACTION_MANUAL_START ->
            if (hasCall) Handling.POST_THEN_HANDLE else Handling.END_ORPHAN

        else -> if (hasCall) Handling.HANDLE_ONLY else Handling.END_ORPHAN
    }
}
