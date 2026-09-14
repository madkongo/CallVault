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
    USER;

    companion object {
        fun of(weJustTurnedItOff: Boolean, weJustTurnedItOn: Boolean, wifi: WifiState): WirelessDebuggingOffCause = when {
            weJustTurnedItOff -> OURS
            weJustTurnedItOn -> ANDROID_REFUSED
            wifi == WifiState.NOT_CONNECTED -> ANDROID_NO_WIFI
            else -> USER
        }
    }
}
