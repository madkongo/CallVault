/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.ui.viewmodels

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import com.kitsumed.shizucallrecorder.integrations.adb.AdbPairingService
import com.kitsumed.shizucallrecorder.integrations.adb.AdbShell
import com.kitsumed.shizucallrecorder.onboarding.OnboardingStatus
import com.kitsumed.shizucallrecorder.system.openAppSettings
import com.kitsumed.shizucallrecorder.ui.screens.PermissionsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Brain" of the permissions setup flow.
 *
 * This ViewModel decides which action to take based on the current [OnboardingStatus.Status].
 */
class PermissionsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Application context — safe to store in a ViewModel because it lives as long as the
     * app process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /**
     * Works through each missing setup step in the correct order and invokes the matching
     * callback. Once all steps are complete, calls [onPermissionGranted] so the UI can refresh.
     *
     * For each runtime permission:
     *  - First press → the system permission dialog is shown via [requestRuntimePermission].
     *  - If the OS cannot show the popup (permanent denial), [PermissionsScreen] handles the
     *    fallback by calling [openAppSettings] in the launcher result callback.
     *
     * @param status                   Current state of every permission and setup step.
     * @param requestRuntimePermission Launches the system permission dialog for a given permission.
     * @param launchFolderPicker       Opens the folder picker to choose a recording folder.
     * @param onPermissionGranted      Called after any step completes so the UI can refresh.
     */
    fun onGrantAccess(
        status: OnboardingStatus.Status,
        requestRuntimePermission: (String) -> Unit,
        launchFolderPicker: () -> Unit,
        onPermissionGranted: () -> Unit
    ) {
        when {
            // Notifications FIRST: ADB pairing is driven entirely through a notification (the inline
            // "enter pairing code" reply action), so without POST_NOTIFICATIONS the pairing step is
            // invisible and "Setup ADB" appears to do nothing. Gate ADB behind notifications.
            !status.notificationsGranted     -> requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            !status.adbConnected             -> setupAdb(onPermissionGranted)
            !status.contactsGranted          -> requestRuntimePermission(Manifest.permission.READ_CONTACTS)
            !status.phoneStateGranted        -> requestRuntimePermission(Manifest.permission.READ_PHONE_STATE)
            !status.callLogGranted           -> requestRuntimePermission(Manifest.permission.READ_CALL_LOG)
            !status.batteryExempted          -> {
                appContext.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${appContext.packageName}".toUri()
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            // Recording-folder selection has moved OUT of onboarding into the in-app settings wizard;
            // it is no longer a prerequisite to finish setup.
            else                             -> { /* All permissions granted; setup complete. */ }
        }
        // Always trigger a refresh of the UI to detect and show new permission changes.
        onPermissionGranted()
    }

    /**
     * Sets up the embedded-ADB connection (replaces the old "open Shizuku" step).
     *
     * Tries to connect with the persisted, already-authorised key. If the device has never been
     * paired (connect fails), starts [AdbPairingService] so the user can pair once via the
     * notification (enter the 6-digit code). Refreshes the UI when done so the card turns green.
     */
    private fun setupAdb(onDone: () -> Unit) {
        viewModelScope.launch {
            val connected = withContext(Dispatchers.IO) { AdbShell.ensureConnected(appContext) }
            if (!connected) {
                // Not paired yet (or connect failed) — guide the user through one-time pairing.
                AdbPairingService.start(appContext)
            }
            onDone()
        }
    }
}