/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Starting the recording service from the call path, without ever throwing into it (review N26).
 *
 * From Android 12 an app in the background may not start a foreground service. The PHONE_STATE broadcast is NOT one
 * of the exemptions Android lists (developer.android.com, "Restrictions on starting a foreground service from the
 * background"); the exemptions that apply to us are a visible activity, a notification tap, and the user turning
 * battery optimisation off for the app. With none of them, `startForegroundService` throws
 * `ForegroundServiceStartNotAllowedException` (Android 14 and 17 emulators, 2026-11-20) and an unguarded call
 * killed the app. The battery exemption is the only one the user can grant, so a refusal is recorded and Home
 * says so until the exemption is on.
 */
object ForegroundStartGuard {

    sealed interface Outcome {
        data object Started : Outcome
        data class Refused(val cause: Throwable) : Outcome
    }

    /** Runs [start]; a refusal (IllegalStateException on A12+, SecurityException) comes back as a value. */
    fun attempt(start: () -> Unit): Outcome = try {
        start()
        Outcome.Started
    } catch (e: Exception) {
        Outcome.Refused(e)
    }

    /** One line for the always-on log: what was refused, why, and whether the user-grantable exemption is on. */
    fun describe(action: String, cause: Throwable, batteryExempt: Boolean): String =
        ("service start refused for $action: ${cause.javaClass.simpleName}: ${cause.message ?: "no message"} " +
            "batteryExempt=$batteryExempt").replace(Regex("[\\r\\n]+"), " ")

    /** Warn only while the user can still fix it: a refusal happened and the exemption is not on. */
    fun shouldWarnAboutBattery(refusedAtMs: Long, batteryExempt: Boolean): Boolean =
        refusedAtMs > 0L && !batteryExempt

    /** Quirk Q53 in the design catalogue: no foreground-start allow-list entry and no battery exemption (Android 12+). */
    const val QUIRK_ID = "Q53"

    private const val SDK_FGS_BACKGROUND_LIMIT = Build.VERSION_CODES.S

    /**
     * The one place that decides whether this phone, right now, is exposed to the background start limit (Q53):
     * Android 12+, recording on, the user has not exempted the app, and NOTHING that keeps the app on the foreground
     * start allow-list is actually running ([keepAliveRunning]: a probe of the live service, not the mode setting, so
     * a killed keep-alive counts too). Pure, so it can move into a quirk registry unchanged.
     */
    fun setupNeedsBatteryExemption(
        keepAliveRunning: Boolean,
        recordingAllowed: Boolean,
        batteryExempt: Boolean,
        sdkInt: Int,
    ): Boolean = sdkInt >= SDK_FGS_BACKGROUND_LIMIT && recordingAllowed && !batteryExempt && !keepAliveRunning

    /** The always-on line for a positive [setupNeedsBatteryExemption], so a report shows why Home asked. */
    fun describeSetup(sdkInt: Int): String =
        "$QUIRK_ID: keepAlive=not-running batteryExempt=false sdk=$sdkInt: Home asks for the battery exemption"

    /** Opens the system's own "allow unrestricted battery use" prompt for this app (permission is in the manifest). */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The prompt when some activity handles it, else the app's own settings page. */
    fun bestBatteryIntent(context: Context): Intent {
        val prompt = batteryExemptionIntent(context)
        val handled = runCatching { prompt.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        return if (handled) prompt else appSettingsIntent(context)
    }

    /** The app's own settings page, for a phone that has no handler for [batteryExemptionIntent]. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val PREFS = "cv_fgs_start_refusal"
    private const val KEY_REFUSED_AT = "refused_at_ms"

    fun clearRefused(context: Context) {
        runCatching { if (refusedAt(context) != 0L) prefs(context).edit().remove(KEY_REFUSED_AT).apply() }
    }

    fun markRefused(context: Context, atMs: Long = System.currentTimeMillis()) {
        runCatching { prefs(context).edit().putLong(KEY_REFUSED_AT, atMs).apply() }
    }

    fun refusedAt(context: Context): Long =
        runCatching { prefs(context).getLong(KEY_REFUSED_AT, 0L) }.getOrDefault(0L)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
