/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.baba.callvault.server.VoipCallFacts
import com.baba.callvault.utils.AppLogger

/**
 * Hears an app's incoming-call notification at the moment it posts, which is the only time an
 * incoming app call says it is incoming (BabApps item 113; see [VoipRingMemory]).
 *
 * Reads the structure of a notification (category, action count, call type, ongoing, call timer) and
 * nothing else: never its title or text, so no name or number is read, logged or kept. Every
 * notification that is not a call is dropped on the first lines.
 */
class VoipRingListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        VoipRingMemory.onListenerConnected(SystemClock.elapsedRealtime())
        AppLogger.i(TAG, "Ring listener connected")
    }

    override fun onListenerDisconnected() {
        VoipRingMemory.onListenerDisconnected()
        AppLogger.i(TAG, "Ring listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        runCatching {
            val extras = n.extras
            val callType = if (extras.containsKey(EXTRA_CALL_TYPE)) extras.getInt(EXTRA_CALL_TYPE) else null
            val chrono = if (extras.containsKey(Notification.EXTRA_SHOW_CHRONOMETER)) extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) else null
            val incomingFlag = if (extras.containsKey(EXTRA_IS_INCOMING)) extras.getBoolean(EXTRA_IS_INCOMING) else null
            val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
            val groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val facts = VoipCallFacts(
                category = n.category,
                actionCount = n.actions?.size ?: 0,
                callType = callType,
                showChronometer = chrono,
                ongoing = ongoing,
                incomingFlag = incomingFlag,
            )
            val incoming = VoipDirection.isRingMarker(facts, hasFullScreenIntent = n.fullScreenIntent != null)
            // A call's own markers, for telling its ongoing notice from the app's other ongoing ones (C30).
            val callLike = n.category == Notification.CATEGORY_CALL || callType != null || incomingFlag != null || chrono == true
            if (!incoming && !ongoing) {
                // Not ringing and not ongoing: only matters as the ringing notice rewritten in place (C30); nothing logged.
                VoipRingMemory.onPosted(sbn.packageName, sbn.key, false, false, groupSummary, SystemClock.elapsedRealtime())
                return
            }
            val before = VoipRingMemory.verdict(sbn.packageName, SystemClock.elapsedRealtime())
            VoipRingMemory.onPosted(sbn.packageName, sbn.key, incoming, ongoing, groupSummary, SystemClock.elapsedRealtime(), callLike)
            val after = VoipRingMemory.verdict(sbn.packageName, SystemClock.elapsedRealtime())
            if (incoming && before != VoipRingMemory.Verdict.UNRESOLVED_RING) {
                AppLogger.i(TAG, "Incoming call notification seen from ${sbn.packageName}: ${facts.describe()}")
            } else if (after == VoipRingMemory.Verdict.ANSWERED_RING && before != after) {
                AppLogger.i(TAG, "Ringing call from ${sbn.packageName} was answered (ongoing notification posted)")
            }
        }.onFailure { AppLogger.d(TAG, "ring listener post failed: ${it.javaClass.simpleName}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { VoipRingMemory.onRemoved(sbn.packageName, sbn.key, SystemClock.elapsedRealtime()) }
    }

    private companion object {
        const val TAG = "CV:RingListener"
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_IS_INCOMING = "IS_INCOMING_CALL"
    }
}
