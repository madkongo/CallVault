/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Who switched Wireless debugging off. Only [USER] may make CallVault hold back from switching it on again.
 *
 * Android writes the switch to 0 itself in two cases measured on 2026-09-14 (see
 * docs/dev-notes/2026-09-14-debugging-switches-model.md): when it refuses a write on a network the user has
 * not trusted, and when Wi-Fi drops. Treating either as the user's choice would leave recording down after
 * Wi-Fi came back, for a decision nobody made.
 */
enum class WirelessDebuggingOffCause {
    OURS,
    ANDROID_REFUSED,
    ANDROID_NO_WIFI,

    /**
     * The phone left a Wi-Fi network moments ago. AOSP writes the switch off on every disconnect, and when the
     * phone moves straight to another network Wi-Fi already reads connected again (OP9, 2026-10-21).
     */
    ANDROID_NETWORK_CHANGED,

    /**
     * CallVault restarted adbd moments ago, and another app reacted by switching it off. A tester, POCO X7 Pro,
     * 2026-10-22: 1.2-1.5 s after each of our `tcpip:` arms, every time; Shizuku's watchdog is the likely writer.
     */
    AFTER_OUR_ADBD_RESTART,

    /**
     * Wireless debugging went off within [ANDROID_REVERT_WINDOW_MS] of an "on" that anyone made, CallVault or not.
     * Android reverts an on it does not trust 1.2-1.5 s later (POCO, OP9 C99); a person does not switch it on and
     * straight off again.
     */
    ANDROID_REVERTED_AN_ON,

    /** An "off" arrived while the last value seen was already off: the "on" between them was never delivered. */
    OFF_WHILE_ALREADY_OFF,
    USER;

    companion object {
        /**
         * @param wifiJustLost a Wi-Fi network was lost within the last few seconds.
         * @param weJustRestartedAdbd CallVault opened `tcpip:` or `usb:` within the last few seconds.
         * @param msSinceLastOn how long ago the last "on" of the switch was seen, by anyone; null if none was.
         * @param lastSeenOn the value the observer saw last (null if it has seen none yet).
         */
        fun of(
            weJustTurnedItOff: Boolean,
            weJustTurnedItOn: Boolean,
            wifi: WifiState,
            wifiJustLost: Boolean = false,
            weJustRestartedAdbd: Boolean = false,
            msSinceLastOn: Long? = null,
            lastSeenOn: Boolean? = null,
        ): WirelessDebuggingOffCause = when {
            weJustTurnedItOff -> OURS
            weJustTurnedItOn -> ANDROID_REFUSED
            wifi == WifiState.NOT_CONNECTED -> ANDROID_NO_WIFI
            wifiJustLost -> ANDROID_NETWORK_CHANGED
            weJustRestartedAdbd -> AFTER_OUR_ADBD_RESTART
            msSinceLastOn != null && msSinceLastOn in 0 until ANDROID_REVERT_WINDOW_MS -> ANDROID_REVERTED_AN_ON
            lastSeenOn == false -> OFF_WHILE_ALREADY_OFF
            else -> USER
        }

        /** About twice the measured reverts (1.2-1.5 s on the POCO and the OP9). */
        const val ANDROID_REVERT_WINDOW_MS = 3_000L
    }
}
