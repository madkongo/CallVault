/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What switching USB debugging will cost, so the user hears it before the switch moves. */
enum class UsbDebuggingOff {
    /** Nothing is lost. */
    PROCEED,

    /**
     * Off-Wi-Fi recording stops working. The loopback listener lives inside adbd, and AOSP stops adbd
     * when both debugging flags are off — which is what happens the moment the phone leaves Wi-Fi.
     */
    WARN_LOSES_OFFLINE,

    /**
     * Not on Wi-Fi right now, so the recorder has no way back in at all until the phone joins a network
     * it trusts for Wireless debugging. This is #39: the app sat on "starting up" indefinitely.
     */
    WARN_NO_WAY_IN,
}

/**
 * Decides whether turning USB debugging off needs a warning first. Free of `Context` so it is tested.
 *
 * The rule behind it, from AOSP `AdbService.stopAdbd()`: adbd is stopped only when **both** the USB and
 * the Wi-Fi debugging flags are false. USB debugging is therefore the only switch that keeps adbd alive
 * independently of the network.
 */
object UsbDebuggingOffGuard {

    fun decide(turnOn: Boolean, offlineRecordingOn: Boolean, wifi: WifiState): UsbDebuggingOff = when {
        turnOn -> UsbDebuggingOff.PROCEED
        // Only a positive "not connected" is evidence there is no way in; unknown is not.
        wifi == WifiState.NOT_CONNECTED -> UsbDebuggingOff.WARN_NO_WAY_IN
        offlineRecordingOn -> UsbDebuggingOff.WARN_LOSES_OFFLINE
        else -> UsbDebuggingOff.PROCEED
    }
}
