/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Intent
import android.os.Bundle
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.navigation.OpenRecordingRequest
import com.baba.callvault.ui.navigation.OpenTranscriptRequest
import com.baba.callvault.utils.AppLogger

/**
 * What an Intent into [MainActivity] asked for: a notification destination, a recording to open and
 * a transcript to open. Pulled out of the Activity so the two rules around reading it are testable.
 *
 * Reading is guarded (C75): [MainActivity] is exported, so any app can send extras whose Bundle cannot
 * be unparcelled here, and an unguarded `getStringExtra` would crash the process. Unreadable means
 * "no request", and it is logged.
 *
 * A request is consumed (C160): [forCreate] reads the launch Intent only on a first creation. On a
 * recreation (rotation, theme or language switch, process restore) the Intent still carries the
 * request already acted on, so the pending state is restored from the saved bundle instead.
 */
internal data class LaunchRequests(
    val destination: NotificationDestination,
    val recording: String?,
    val transcript: String?,
) {

    /** Writes only what is still waiting, so a handled request is not carried into the next creation. */
    fun writeTo(outState: Bundle) {
        if (destination != NotificationDestination.None) outState.putString(KEY_DESTINATION, destination.key)
        recording?.let { outState.putString(KEY_RECORDING, it) }
        transcript?.let { outState.putString(KEY_TRANSCRIPT, it) }
    }

    companion object {
        private const val TAG = "LaunchRequests"
        private const val KEY_DESTINATION = "pending_destination"
        private const val KEY_RECORDING = "pending_open_recording"
        private const val KEY_TRANSCRIPT = "pending_open_transcript"

        val NONE = LaunchRequests(NotificationDestination.None, null, null)

        /** A first creation reads the Intent; a recreation restores what was still pending. */
        fun forCreate(savedInstanceState: Bundle?, intent: Intent?): LaunchRequests =
            if (savedInstanceState == null) fromIntent(intent) else readFrom(savedInstanceState)

        fun readFrom(saved: Bundle): LaunchRequests = runCatching {
            LaunchRequests(
                destination = NotificationDestination.fromIntentExtra(saved.getString(KEY_DESTINATION), false),
                recording = OpenRecordingRequest.fromIntentExtra(saved.getString(KEY_RECORDING), false),
                transcript = OpenTranscriptRequest.fromIntentExtra(saved.getString(KEY_TRANSCRIPT), false),
            )
        }.getOrElse {
            AppLogger.w(TAG, "Saved pending request unreadable: ${it.javaClass.simpleName}")
            NONE
        }

        fun fromIntent(intent: Intent?): LaunchRequests {
            if (intent == null) return NONE
            val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            return LaunchRequests(
                destination = NotificationDestination.fromIntentExtra(
                    intent.safeStringExtra(NotificationDestination.EXTRA), fromHistory
                ),
                recording = OpenRecordingRequest.fromIntentExtra(
                    intent.safeStringExtra(OpenRecordingRequest.EXTRA), fromHistory
                ),
                transcript = OpenTranscriptRequest.fromIntentExtra(
                    intent.safeStringExtra(OpenTranscriptRequest.EXTRA), fromHistory
                ),
            )
        }

        private fun Intent.safeStringExtra(key: String): String? = try {
            getStringExtra(key)
        } catch (e: RuntimeException) {
            // BadParcelableException and friends: an extra of a class we do not have (C75).
            AppLogger.w(TAG, "Unreadable extra $key: ${e.javaClass.simpleName}")
            null
        }
    }
}
