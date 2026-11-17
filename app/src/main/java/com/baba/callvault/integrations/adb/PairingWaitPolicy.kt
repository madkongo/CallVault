/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Decisions for the "waiting for Wireless debugging" state of [AdbPairingService] that depend only on the
 * Android version and what the `adb_wifi_enabled` setting reads. BabApps item 92: on an Android 17 QPR3 beta
 * that setting is hidden from apps, so "off" may only mean "cannot tell", and the user needs to be told the
 * exact path to the pairing dialog.
 */
object PairingWaitPolicy {

    /** Android 17. From here the notification names the path instead of just "turn on Wireless debugging". */
    const val ANDROID_17_API = 37

    /** What `Settings.Global.getInt(…, "adb_wifi_enabled", -1)` returns when the setting is on. */
    private const val READS_ON = 1

    /** Whether the waiting notification should spell out "Wireless debugging → Pair device with pairing code". */
    fun usesPathWording(sdkInt: Int): Boolean = sdkInt >= ANDROID_17_API

    /**
     * Whether a "waiting" read of the setting may be unreliable, so the mDNS probe and discovery should report
     * every callback to the setup journal: Android 17 or later, or any read other than "on" (0, or -1 = absent).
     */
    fun settingMayBeHidden(sdkInt: Int, rawRead: Int): Boolean = sdkInt >= ANDROID_17_API || rawRead != READS_ON
}
