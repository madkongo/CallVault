/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Keeps the "Wireless debugging was switched off by the user" flag honest across an on that is quickly reverted.
 *
 * Any "on" clears the flag. When that on is then reverted within [WirelessDebuggingOffCause.ANDROID_REVERT_WINDOW_MS]
 * (a mis-tap on the tile, an OEM off/on/off burst), the off is not the user's choice, but the flag the user already
 * had must not be lost with it, or CallVault would switch the user's switch back on. So the value from before the
 * on is remembered and put back. A revert verdict can therefore only avoid setting a NEW flag, never remove one.
 * Pure; the observer supplies the clock and the stored flag.
 */
class WirelessDebuggingUserFlagTracker {
    private var savedFlag: Boolean? = null
    private var savedAtMs = 0L

    /** Call on every observed "on", with the flag as it stood before the on branch clears it. */
    @Synchronized
    fun onSwitchedOn(flagBefore: Boolean, nowMs: Long) {
        // A second on inside the window sees the flag the first one cleared; keep the first.
        val stillFresh = savedFlag != null && nowMs - savedAtMs in 0 until WirelessDebuggingOffCause.ANDROID_REVERT_WINDOW_MS
        if (stillFresh) return
        savedFlag = flagBefore
        savedAtMs = nowMs
    }

    /** The flag to store after an off with this [cause]; [flagNow] is what is stored now. */
    @Synchronized
    fun flagAfterOff(cause: WirelessDebuggingOffCause, flagNow: Boolean, nowMs: Long): Boolean = when (cause) {
        WirelessDebuggingOffCause.USER -> true
        WirelessDebuggingOffCause.ANDROID_REVERTED_AN_ON, WirelessDebuggingOffCause.OFF_WHILE_ALREADY_OFF -> {
            val saved = savedFlag
            if (saved != null && nowMs - savedAtMs in 0 until WirelessDebuggingOffCause.ANDROID_REVERT_WINDOW_MS) saved else flagNow
        }
        else -> flagNow
    }
}
