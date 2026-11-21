/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.baba.callvault.R
import com.baba.callvault.services.call.ForegroundStartGuard
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Things the phone can do to CallVault that stop a call being recorded, though every setting inside the app
 * looks right (R5). Declaration order is the order the Home card names them.
 *
 * [inNotice] is false for notifications being off: a notification cannot announce that notifications are off.
 */
enum class RiskReason(
    val inNotice: Boolean,
    /** The short phrase: a bullet in the background notice, and the name in "1 more: ...". */
    @param:StringRes val shortTextRes: Int,
    /** The sentence on the Home card when this is the first reason. */
    @param:StringRes val lineTextRes: Int,
    /** The label on the button that opens the right system screen. */
    @param:StringRes val fixTextRes: Int,
) {
    BATTERY_LIMITS(true, R.string.risk_short_battery, R.string.risk_line_battery, R.string.risk_fix_battery),
    BACKGROUND_RESTRICTED(true, R.string.risk_short_restricted, R.string.risk_line_restricted, R.string.risk_fix_restricted),
    APP_HIBERNATION(true, R.string.risk_short_hibernation, R.string.risk_line_hibernation, R.string.risk_fix_hibernation),
    NOTIFICATIONS_OFF(false, R.string.risk_short_notifications, R.string.risk_line_notifications, R.string.risk_fix_notifications),
}

/**
 * Reads and decides the [RiskReason]s. One place, used by the Home card and by the background notice, so the two
 * can never disagree. Every read fails safe: an exception is logged and that reason is treated as not holding.
 */
object RecordingRiskReasons {

    private const val TAG = "CV:RiskCheck"

    /** One reading of the phone. true = the risk holds, false = it does not, null = it could not be read. */
    data class Reading(
        val batteryLimited: Boolean?,
        val backgroundRestricted: Boolean?,
        val hibernation: Boolean?,
        val notificationsOff: Boolean?,
    )

    /**
     * "Pause app activity if unused" is PARKED (owner, 2026-11-21): it is on by default on almost every phone, and the
     * always-running keep-alive service is what stops Android pausing CallVault (see KeepAlivePolicy), so counting it
     * turned nearly every card amber for nothing. Still read and logged; flip this to bring it back.
     */
    internal const val COUNT_HIBERNATION = false

    /** The reasons that hold, in priority order. An unreadable value does not hold. */
    fun fromReading(reading: Reading): List<RiskReason> = listOfNotNull(
        RiskReason.BATTERY_LIMITS.takeIf { reading.batteryLimited == true },
        RiskReason.BACKGROUND_RESTRICTED.takeIf { reading.backgroundRestricted == true },
        RiskReason.APP_HIBERNATION.takeIf { COUNT_HIBERNATION && reading.hibernation == true },
        RiskReason.NOTIFICATIONS_OFF.takeIf { reading.notificationsOff == true },
    )

    /** What the background notice may announce. */
    fun forNotice(reasons: List<RiskReason>): List<RiskReason> = reasons.filter { it.inNotice }

    /**
     * What Home's status card shows as risk: only when it would otherwise say ready. A real error, a user stop,
     * a warm-up, or a failed or missed call already on the card is never replaced by this.
     */
    fun forHero(statusIsReady: Boolean, healthIsProblem: Boolean, reasons: List<RiskReason>): List<RiskReason> =
        if (statusIsReady && !healthIsProblem) reasons else emptyList()

    /** Reads the phone and returns the reasons that hold. Never throws. */
    fun read(context: Context): List<RiskReason> = fromReading(readRaw(context))

    /** Each read on its own, so one failing leaves the others standing. Never throws. */
    fun readRaw(context: Context): Reading = Reading(
        batteryLimited = safeRead("battery optimisation") { !PermissionChecks.hasBatteryExemption(context) },
        backgroundRestricted = safeRead("background restriction") {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
        },
        hibernation = safeRead("app hibernation") {
            // Android 11+: "Pause app activity if unused". Not whitelisted = Android may reset the app's permissions
            // and stop it when it is not opened for months.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) !context.packageManager.isAutoRevokeWhitelisted else null
        },
        notificationsOff = safeRead("notifications") { !NotificationManagerCompat.from(context).areNotificationsEnabled() },
    )

    private fun safeRead(what: String, read: () -> Boolean?): Boolean? = try {
        read()
    } catch (e: Exception) {
        AppLogger.w(TAG, "Could not read $what, treated as not holding: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** The system screen that fixes [reason]. Never changes a setting by itself. */
    fun fixIntent(context: Context, reason: RiskReason): Intent = when (reason) {
        RiskReason.BATTERY_LIMITS -> ForegroundStartGuard.bestBatteryIntent(context)
        RiskReason.BACKGROUND_RESTRICTED -> ForegroundStartGuard.appSettingsIntent(context)
        RiskReason.APP_HIBERNATION -> {
            val pause = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val handled = runCatching { pause.resolveActivity(context.packageManager) != null }.getOrDefault(false)
            if (handled) pause else ForegroundStartGuard.appSettingsIntent(context)
        }
        RiskReason.NOTIFICATIONS_OFF ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the fix, falling back to the app's own settings page. A failure is logged, never thrown. */
    fun launchFix(context: Context, reason: RiskReason) {
        try {
            context.startActivity(fixIntent(context, reason))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not open the fix for $reason, trying app settings: ${e.message}")
            try {
                context.startActivity(ForegroundStartGuard.appSettingsIntent(context))
            } catch (e2: Exception) {
                AppLogger.w(TAG, "Could not open app settings either: ${e2.message}")
            }
        }
    }
}
