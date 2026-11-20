/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Builds a [PhoneProfile] from cheap, synchronous reads: a few binder calls and preference reads. It never runs a
 * shell command, writes a setting, asks for a permission or starts anything, and it never throws: a fact that cannot
 * be read is UNKNOWN. USB default is the value the app cached earlier, not a fresh read.
 */
object PhoneProfileReader {

    private const val ANDROID_11 = 30

    fun read(context: Context): PhoneProfile {
        val prefs = runCatching { AppPreferences(context) }.getOrNull()
        return PhoneProfile(
            maker = Build.MANUFACTURER.orEmpty().ifBlank { "?" },
            model = Build.MODEL.orEmpty().ifBlank { "?" },
            rom = runCatching { RomDetector.detect(Build.MANUFACTURER.orEmpty(), ::systemProperty) }
                .getOrDefault(Rom(RomFamily.UNKNOWN, null)),
            sdk = Build.VERSION.SDK_INT,
            mode = runCatching { prefs?.getPrivilegedMode() }.getOrNull(),
            phoneCallsOn = Tri.of(runCatching { prefs?.isCarrierRecordingEnabled() }.getOrNull()),
            appCallsOn = Tri.of(runCatching { prefs?.isVoipRecordingEnabled() }.getOrNull()),
            batteryExempt = Tri.of(runCatching { PermissionChecks.hasBatteryExemption(context) }.getOrNull()),
            backgroundRestricted = Tri.of(runCatching {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
            }.getOrNull()),
            standbyBucket = StandbyBucketClass.of(runCatching {
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            }.getOrNull()),
            hibernation = hibernation(context),
            notificationsEnabled = Tri.of(runCatching {
                context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
            }.getOrNull()),
            blockedChannels = blockedChannels(context),
            usbDefault = runCatching { UsbDefaultConfig.cached(context).name.lowercase() }.getOrDefault("?"),
        )
    }

    /**
     * "Pause app activity if unused" is ON when the app is NOT on the auto-revoke allow-list. Before Android 11 the
     * feature does not exist, so it is off. Synchronous, unlike the androidx future-based call.
     */
    private fun hibernation(context: Context): Tri {
        if (Build.VERSION.SDK_INT < ANDROID_11) return Tri.NO
        return Tri.of(runCatching { !context.packageManager.isAutoRevokeWhitelisted }.getOrNull())
    }

    private fun blockedChannels(context: Context): List<String> = runCatching {
        context.getSystemService(NotificationManager::class.java)?.notificationChannels.orEmpty()
            .filter { it.importance == NotificationManager.IMPORTANCE_NONE }
            .map { it.id }.sorted()
    }.getOrDefault(emptyList())

    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    }.getOrDefault("")
}
