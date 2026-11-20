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
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import java.util.concurrent.TimeUnit
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.RecordingNotificationHelper
import com.baba.callvault.system.permissions.PermissionChecks

/**
 * Builds a [PhoneProfile] from cheap, synchronous reads: a few binder calls and preference reads. It never runs a
 * shell command, writes a setting, asks for a permission or starts anything, and it never throws: a fact that cannot
 * be read is UNKNOWN. USB default is the value the app cached earlier, not a fresh read.
 */
object PhoneProfileReader {

    /** The channels a recorded call and its warnings depend on (same list as the report's channel block). */
    private val RELEVANT_CHANNELS = setOf(
        DaemonKeepAliveService.CHANNEL_ID,
        RecordingNotificationHelper.CHANNEL_ID_SERVICE,
        RecordingNotificationHelper.CHANNEL_ID_ERROR,
    )
    private const val HIBERNATION_WAIT_SECONDS = 2L

    fun read(context: Context): PhoneProfile {
        val prefs = runCatching { AppPreferences(context) }.getOrNull()
        return PhoneProfile(
            maker = Build.MANUFACTURER.orEmpty().ifBlank { "?" },
            model = Build.MODEL.orEmpty().ifBlank { "?" },
            rom = runCatching { RomDetector.detect(::systemProperty) }
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
            conflictingApps = conflictingApps(context),
        )
    }

    /**
     * Which of the fixed [KnownConflictingApps] packages are installed. A read-only lookup by exact name, answered for
     * launcher apps through the manifest's LAUNCHER <queries>; the installed-app list is never enumerated.
     */
    private fun conflictingApps(context: Context): List<String> = runCatching {
        val pm = context.packageManager
        KnownConflictingApps.packageNames(KnownConflictingApps.detect { name ->
            try { pm.getPackageInfo(name, 0); true } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        })
    }.getOrDefault(emptyList())

    /**
     * "Pause app activity if unused": androidx answers per ROM (Android 11+, and the Play-services backport), so a
     * phone that removed the feature or exempts the app reads NO. Called from a background thread (it waits on a
     * binder-backed future for at most [HIBERNATION_WAIT_SECONDS]); a timeout or error reads UNKNOWN.
     */
    private fun hibernation(context: Context): Tri = hibernationOf(runCatching {
        PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(HIBERNATION_WAIT_SECONDS, TimeUnit.SECONDS)
    }.getOrNull())

    /** The androidx status code to a [Tri]: only a confirmed restriction is YES; unavailable or disabled is NO. */
    internal fun hibernationOf(status: Int?): Tri = when (status) {
        UnusedAppRestrictionsConstants.API_30_BACKPORT,
        UnusedAppRestrictionsConstants.API_30,
        UnusedAppRestrictionsConstants.API_31 -> Tri.YES
        UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
        UnusedAppRestrictionsConstants.DISABLED -> Tri.NO
        else -> Tri.UNKNOWN // ERROR, a timeout, or a code this version does not know
    }

    private fun blockedChannels(context: Context): List<String> = runCatching {
        context.getSystemService(NotificationManager::class.java)?.notificationChannels.orEmpty()
            .filter { it.id in RELEVANT_CHANNELS && it.importance == NotificationManager.IMPORTANCE_NONE }
            .map { it.id }.sorted()
    }.getOrDefault(emptyList())

    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    }.getOrDefault("")
}
