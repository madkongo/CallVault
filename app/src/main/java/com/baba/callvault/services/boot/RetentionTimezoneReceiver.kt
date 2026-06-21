/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baba.callvault.system.storage.RetentionScheduler
import com.baba.callvault.utils.AppLogger

/**
 * Re-anchors the daily retention sweep to the device's LOCAL time when the time zone (or the clock)
 * changes, so the user-chosen HH:mm keeps meaning local time wherever they are — without needing to
 * reopen the app. The sweep is also re-anchored on app start ([RetentionScheduler.apply] in the
 * Application's onCreate); this receiver covers the travel case while the app is backgrounded.
 */
class RetentionTimezoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                AppLogger.i(TAG, "Time/zone changed (${intent.action}); re-anchoring retention sweep to local time.")
                runCatching { RetentionScheduler.apply(context.applicationContext) }
                    .onFailure { AppLogger.w(TAG, "Re-anchor failed: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "CV:RetentionScheduler"
    }
}
