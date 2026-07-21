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
import android.content.pm.PackageInstaller
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * Receives PackageInstaller session status for [UpdateInstaller.installViaPackageInstaller].
 *
 * STATUS_PENDING_USER_ACTION forwards the system confirmation dialog; SUCCESS is NOT handled here
 * (the process is killed during the replacement — [UpdatePackageReplacedReceiver] reports it);
 * failures clear the pending state and post the failure notification.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { AppLogger.w(TAG, "Could not launch install confirmation: ${it.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Normally unreachable (process dies on replacement); harmless if it does arrive —
                // MY_PACKAGE_REPLACED owns the success notification.
                AppLogger.i(TAG, "Install session reported success")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLogger.e(TAG, "Update install failed: status=$status message=$message")
                AppPreferences(context).setPendingUpdateTag(null)
                UpdateNotifications.showUpdateFailure(
                    context,
                    context.getString(R.string.update_notif_failure_install_text)
                )
            }
        }
    }

    companion object {
        private const val TAG = "CV:UpdateInstallRecv"
    }
}
