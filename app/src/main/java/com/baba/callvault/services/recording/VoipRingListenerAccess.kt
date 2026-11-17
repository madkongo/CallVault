/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger

/**
 * Switches the ring listener ([VoipRingListenerService]) on while app-call recording is on, through
 * the recorder host's shell (`cmd notification allow_listener`), which both modes have: no screen to
 * visit, no new setting. Writing the `enabled_notification_listeners` secure setting from the app was
 * tried first and does nothing on Android 14: the setting changed and the system never bound the
 * listener (measured on the OP9, 2026-11-17).
 *
 * Only a switch we set is ever undone: it is removed again only when we approved it and app-call
 * recording is off; a listener the user enabled themselves is not touched.
 */
object VoipRingListenerAccess {
    private const val TAG = "CV:RingAccess"
    private const val PREFS = "voip_ring_listener"
    private const val KEY_APPROVED_BY_US = "approved_by_us"

    /** The host may not be connected yet when the app starts: wait for it, but not forever. */
    private const val WAIT_STEP_MS = 5_000L
    private const val WAIT_MAX_MS = 3 * 60_000L

    /** Brings the system's approval in line with whether app-call recording is on. Blocking: call it off the main thread. */
    fun sync(context: Context) {
        runCatching {
            val wanted = AppPreferences(context).isVoipRecordingEnabled()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!wanted && !prefs.getBoolean(KEY_APPROVED_BY_US, false)) return
            val component = ComponentName(context, VoipRingListenerService::class.java).flattenToString()
            val userId = Process.myUserHandle().hashCode()
            var waited = 0L
            var service = RecorderConnection.service
            while (service == null && waited < WAIT_MAX_MS) {
                Thread.sleep(WAIT_STEP_MS)
                waited += WAIT_STEP_MS
                service = RecorderConnection.service
            }
            if (service == null) {
                AppLogger.i(TAG, "Ring listener not switched: the recorder host did not connect within ${waited / 1000}s")
                return
            }
            val answer = service.notificationListenerAccess(component, wanted, userId)
            if (wanted) prefs.edit().putBoolean(KEY_APPROVED_BY_US, true).apply()
            else prefs.edit().putBoolean(KEY_APPROVED_BY_US, false).apply()
            AppLogger.i(TAG, "Ring listener ${if (wanted) "approved" else "removed"} through the host (user $userId): $answer")
        }.onFailure { AppLogger.w(TAG, "Ring listener switch failed: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
