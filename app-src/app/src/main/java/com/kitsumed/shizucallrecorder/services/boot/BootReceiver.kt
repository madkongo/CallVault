/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.services.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * On boot, if the user has set up ADB, start a foreground service that re-enables Wireless
 * debugging (if the OEM disabled it) and reconnects so recording works without opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!AppPreferences(context).isAdbPaired()) return
        AppLogger.i(TAG, "Boot completed; starting ADB connection service")
        AdbConnectionService.start(context)
    }

    companion object {
        private const val TAG = "SCR:BootReceiver"
    }
}
