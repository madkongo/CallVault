/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** Whether Developer options are on, with the third answer the old boolean could not give. */
enum class DeveloperOptionsState {
    ON,
    OFF,

    /** Neither proven. Never told to the user as a fact, and never a reason to stop. */
    UNKNOWN;

    /**
     * Whether to point the user at Settings ▸ About phone to tap Build number seven times.
     *
     * Only a **proven** off. Routing on "not proven on" is what dead-ended issue #40: on a build that
     * redacts `DEVELOPMENT_SETTINGS_ENABLED` the read never changes, so tapping Build number — which the
     * reporter had already done — could not move the app off that screen. Ever.
     */
    val shouldOfferBuildNumberStep: Boolean get() = this == OFF

    /**
     * Always false, deliberately.
     *
     * Setup must never be blocked on this reading. Even a proven off is better handled by showing the
     * instruction and letting the user continue to pairing: if Developer options really are off they will
     * find Wireless debugging missing, with the hint already in front of them. A button that cannot
     * advance teaches the user the app is broken.
     */
    val blocksSetup: Boolean get() = false
}

/**
 * Works out [DeveloperOptionsState] without trusting a setting Android 17 redacts.
 *
 * ## The proof this rests on
 *
 * **USB debugging and Wireless debugging both live inside Developer options.** Neither can be switched on
 * while Developer options are off, and turning Developer options off takes them with it. So *any* of
 * these is a proof that Developer options are on, no matter what the setting claims:
 *
 * - Wireless debugging reads on (`adb_wifi_enabled`, which Android 17 leaves truthful);
 * - USB debugging is *proven* on by [UsbDebuggingPolicy];
 * - `adbd` is running at all — by R1 it only runs when one of those two switches is on.
 *
 * That last one matters most: it means a phone we can reach is a phone whose Developer options we can
 * prove are on, which is exactly the situation of every user who is trying to pair.
 *
 * ## Why not a version check
 *
 * See [UsbDebuggingPolicy]. In short: Android 16 ROMs backport the redaction, and it is not CTS-enforced,
 * so `SDK_INT` cannot answer it. Nothing here asks.
 *
 * Free of `Context` so every branch is tested.
 */
object DeveloperOptionsPolicy {

    fun of(
        settingSaysOn: Boolean,
        usbDebugging: UsbDebuggingState,
        wirelessDebuggingOn: Boolean,
        adbd: AdbdState,
    ): DeveloperOptionsState = when {
        settingSaysOn -> DeveloperOptionsState.ON
        // Any reachable debugging switch is a proof, because none of them exists with Developer options off.
        wirelessDebuggingOn || usbDebugging.isOn || adbd == AdbdState.RUNNING -> DeveloperOptionsState.ON
        // Nothing is on and nothing is running. On Android <=16 this is a truthful off. On a redacting
        // build it is unprovable either way — but the user has to turn a debugging switch on regardless,
        // so the Build-number instruction is still the right thing to show, and it no longer blocks.
        usbDebugging.isOff && adbd == AdbdState.STOPPED -> DeveloperOptionsState.OFF
        else -> DeveloperOptionsState.UNKNOWN
    }
}
