/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.onboarding

import android.content.Context
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.integrations.adb.AdbConnectionManager
import com.kitsumed.shizucallrecorder.system.permissions.PermissionChecks
import com.kitsumed.shizucallrecorder.system.storage.SafHelper
import com.kitsumed.shizucallrecorder.ui.viewmodels.AppNavigationViewModel

/**
 * OnboardingStatus aggregates all permission and setup states needed for the app to operate.
 *
 * Used by [AppNavigationViewModel] to decide which screen to show
 */
object OnboardingStatus {

    /**
     * An object that contains the state of every prerequisite (permissions) the app requires to work.
     *
     * @param disclaimerAccepted        True if the user has accepted the app disclaimer.
     * @param notificationsGranted      True if the app can post notifications.
     * @param contactsGranted           True if READ_CONTACTS is granted.
     * @param phoneStateGranted         True if READ_PHONE_STATE is granted.
     * @param callLogGranted            True if the app has permission to access the call log.
     * @param batteryExempted           True if the app is on the battery-optimisation whitelist.
     * @param storageSelected           True if a valid SAF recording folder has been chosen.
     * @param adbConnected              True if CallVault's embedded ADB connection is established
     *                                  (paired once + connected). Replaces the old Shizuku gate.
     */
    data class Status(
        val disclaimerAccepted: Boolean,
        val notificationsGranted: Boolean,
        val contactsGranted: Boolean,
        val phoneStateGranted: Boolean,
        val callLogGranted: Boolean,
        val batteryExempted: Boolean,
        val storageSelected: Boolean,
        val adbConnected: Boolean
    ) {
        /**
         * Returns true only when every prerequisite is satisfied, including the disclaimer.
         */
        fun isComplete(): Boolean {
            return disclaimerAccepted &&
                notificationsGranted &&
                contactsGranted &&
                phoneStateGranted &&
                callLogGranted &&
                batteryExempted &&
                storageSelected &&
                adbConnected
        }
    }

    /**
     * Reads the current state of permissions and other requirements and returns a [Status] data class/object.
     *
     * @param context     App context used for permission checks, SAF folder validation and other context based checks.
     * @param preferences The app-wide [AppPreferences] to perform checks based on user app settings.
     * @return A fully populated [Status] reflecting the current device state.
     */
    fun getStatus(context: Context, preferences: AppPreferences): Status {
        val storageUri = preferences.getRecordingFolderUri()
        return Status(
            disclaimerAccepted       = preferences.isDisclaimerAccepted(),
            notificationsGranted     = PermissionChecks.hasNotificationPermission(context),
            contactsGranted          = PermissionChecks.hasContactsPermission(context),
            phoneStateGranted        = PermissionChecks.hasPhoneStatePermission(context),
            callLogGranted           = PermissionChecks.hasCallLogPermission(context),
            batteryExempted          = PermissionChecks.hasBatteryExemption(context),
            storageSelected          = SafHelper.isFolderValid(context, storageUri),
            // Embedded-ADB replaces Shizuku. Onboarding is satisfied once the user has paired (a
            // persisted flag) — not only while a live connection exists, since a connection is
            // per-process and would otherwise force re-onboarding on every launch. The live
            // connection is (re)established lazily by the recording path and on app start.
            adbConnected             = preferences.isAdbPaired() ||
                AdbConnectionManager.getInstance(context).isConnected
        )
    }
}
