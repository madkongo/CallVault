/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baba.callvault.BuildConfig
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * Fires after THIS app was replaced by an update. When the updater initiated that install (the
 * pending tag is set), posts the success notification and clears all updater state. Updates
 * installed by other means (manual sideload, Obtainium) simply clear any stale state silently.
 */
class UpdatePackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = AppPreferences(context)
        val pendingTag = preferences.getPendingUpdateTag()
        AppLogger.i(TAG, "Package replaced; now ${BuildConfig.VERSION_NAME} (pending update tag: $pendingTag)")

        if (pendingTag != null) {
            UpdateNotifications.showUpdateSuccess(context, BuildConfig.VERSION_NAME)
        }
        preferences.setPendingUpdateTag(null)
        preferences.setAvailableUpdateTag(null)
        preferences.setLastNotifiedUpdateTag(null)
        UpdateNotifications.cancelAvailable(context)
        UpdateManager.cleanupDownloadCache(context)
    }

    companion object {
        private const val TAG = "CV:UpdateReplacedRecv"
    }
}
