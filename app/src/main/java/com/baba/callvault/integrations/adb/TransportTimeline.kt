/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager

/**
 * An always-on timeline of what the recorder depends on, one line per change — written whether or not debug logging
 * is on, into the same file as [UsbDebuggingEvents] (and so into every debug report).
 *
 * Why: on the Honor BVL-N59 (2026-11-04) a call at 15:41 was missed and the report could not say why — debug logging
 * was off, and the phone's own system log held nothing newer than its last boot. This records, as it happens: the
 * recorder alive or not, adbd, USB debugging (as the recorder reads it), Wireless debugging, Wi-Fi, the screen and the
 * lock, the off-Wi-Fi listener; and each relaunch attempt with its result. Only changes are written, so a quiet day
 * costs a handful of lines.
 */
object TransportTimeline {

    @Volatile private var last: String? = null

    /** Everything one line says. Plain values so the wording and the change rule are testable without a phone. */
    data class Facts(
        val recorderAlive: Boolean,
        val adbd: String,
        val usb: String,
        val usbShell: String,
        val wirelessDebugging: Boolean,
        val wifi: String,
        val offlinePortArmed: Boolean,
        val screen: String,
        val locked: Boolean,
        val chargeOnlyAdb: String,
        /** Battery Saver or an OEM extreme saver (review R52). */
        val powerSave: Boolean,
        /** On the battery-optimisation whitelist (review R5). */
        val batteryExempt: Boolean,
        /** "Restricted" background use (review R5). */
        val backgroundRestricted: Boolean,
        /** App standby bucket is RESTRICTED (review R5). */
        val bucketRestricted: Boolean,
        /** `sys.usb.config` as the app can read it; "" when unreadable. */
        val usbConfig: String = "",
        /** `sys.usb.state` as the app can read it; "" when unreadable. */
        val usbState: String = "",
    )

    /** Writes the current state if it differs from the last one written. [why] says what prompted the look. */
    fun snapshot(context: Context, recorderAlive: Boolean, why: String) {
        val facts = factsOf(context, recorderAlive)
        val key = changeKey(facts)
        if (key == last) return
        last = key
        UsbDebuggingEvents.record(context, "state ($why): ${render(facts)}")
    }

    private const val UNREADABLE = "(unreadable)"

    /**
     * What the phone says about the transport at the moment the recorder died (GH #89): `init.svc.adbd`,
     * `sys.usb.config` and `sys.usb.state`, each "(unreadable)" when an app cannot read it.
     */
    fun deathLine(adbd: String, usbConfig: String, usbState: String, label: String = "recorder died"): String =
        "$label: init.svc.adbd=${adbd.ifBlank { UNREADABLE }} " +
            "sys.usb.config=${usbConfig.ifBlank { UNREADABLE }} sys.usb.state=${usbState.ifBlank { UNREADABLE }}"

    /** Writes [deathLine] from the live properties. Reads three properties; cheap. */
    fun recorderDied(context: Context, label: String = "recorder died") = event(
        context,
        deathLine(
            label = label,
            // The enum plus the raw string: "restarting", "" and an unreadable property all fold into UNKNOWN.
            adbd = AdbShell.adbdDescribedForReport(),
            usbConfig = AdbShell.systemPropertyForReport("sys.usb.config"),
            usbState = AdbShell.systemPropertyForReport("sys.usb.state"),
        ),
    )

    /** A one-off event: a death, a relaunch and its outcome, a decision not to try. */
    fun event(context: Context, text: String) = UsbDebuggingEvents.record(context, "event: $text")

    /**
     * What decides whether a new line is written. The screen is left out (review R51): each lock or unlock used to add
     * about six lines and pushed older history out of the 128 KB file in a day or two. Screen changes still have their
     * own event line, and the line that IS written carries the screen as it was then.
     */
    internal fun changeKey(f: Facts): String = render(f.copy(screen = "", locked = false))

    internal fun render(f: Facts): String = buildString {
        append("recorder=").append(if (f.recorderAlive) "alive" else "DOWN")
        append(" adbd=").append(f.adbd)
        append(" usb=").append(f.usb).append("(shell:").append(f.usbShell).append(')')
        append(" wd=").append(if (f.wirelessDebugging) "on" else "off")
        append(" wifi=").append(f.wifi)
        append(" offlinePort=").append(if (f.offlinePortArmed) "armed" else "none")
        if (f.screen.isNotEmpty()) append(" screen=").append(f.screen).append(if (f.locked) "+locked" else "")
        append(" chargeOnlyAdb=").append(f.chargeOnlyAdb)
        append(" batterySaver=").append(if (f.powerSave) "ON" else "off")
        append(" batteryOpt=").append(if (f.batteryExempt) "exempt" else "ON")
        append(" bgRestricted=").append(if (f.backgroundRestricted) "YES" else "no")
        append(" bucket=").append(if (f.bucketRestricted) "RESTRICTED" else "ok")
        append(" sys.usb.config=").append(f.usbConfig.ifBlank { UNREADABLE })
        append(" sys.usb.state=").append(f.usbState.ifBlank { UNREADABLE })
    }

    private fun factsOf(context: Context, alive: Boolean): Facts {
        val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        val keyguard = runCatching { context.getSystemService(KeyguardManager::class.java) }.getOrNull()
        val screen = when (runCatching { power?.isInteractive }.getOrNull()) {
            true -> "on"
            false -> "off"
            null -> "?"
        }
        return Facts(
            recorderAlive = alive,
            adbd = AdbShell.adbdDescribedForReport(),
            usb = AdbShell.usbDebuggingState(context).toString(),
            usbShell = UsbDebuggingShellRead.describe(recorderAlive = alive),
            wirelessDebugging = AdbShell.isWirelessDebuggingEnabled(context),
            wifi = WifiState.of(context).toString(),
            offlinePortArmed = runCatching { AdbShell.isLoopbackArmed(context) }.getOrDefault(false),
            screen = screen,
            locked = runCatching { keyguard?.isKeyguardLocked }.getOrNull() == true,
            chargeOnlyAdb = (UsbOffSnapshot.chargingAdb(context) ?: "absent").toString(),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull() == true,
            // A failed read must not look like a loss: assume fine, since this only feeds a log line.
            batteryExempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() != false,
            backgroundRestricted = runCatching {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
            }.getOrNull() == true,
            bucketRestricted = runCatching {
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            }.getOrNull() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED,
            usbConfig = AdbShell.systemPropertyForReport("sys.usb.config"),
            usbState = AdbShell.systemPropertyForReport("sys.usb.state"),
        )
    }
}
