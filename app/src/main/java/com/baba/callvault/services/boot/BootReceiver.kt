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
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.call.CallMonitorService
import com.baba.callvault.utils.AppLogger

/**
 * On boot, if the user has set up ADB, start a foreground service that re-enables Wireless
 * debugging (if the OEM disabled it) and reconnects so recording works without opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!AppPreferences(context).isAdbPaired()) return
        AppLogger.i(TAG, "Boot completed; starting ADB connection service + post-boot call monitor")
        AdbConnectionService.start(context)
        // Hold a live telephony listener for a bounded window so the first call(s) after a reboot are
        // detected in real time, instead of via the post-boot-delayed PHONE_STATE broadcast.
        CallMonitorService.start(context)
    }

    companion object {
        private const val TAG = "CV:BootReceiver"
    }
}
