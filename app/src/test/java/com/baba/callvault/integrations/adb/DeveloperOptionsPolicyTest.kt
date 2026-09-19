/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether Developer options are on, when `DEVELOPMENT_SETTINGS_ENABLED` may be lying.
 *
 * This is the one that cost issue #40's reporter everything: a brand-new install on Android 17, where the
 * setting reads `0` for every app, so onboarding's button routed to the About-phone screen forever and
 * pairing was unreachable. He never recorded a call.
 *
 * The key insight is that **both debugging switches live inside Developer options**, so either of them
 * being on is a proof that Developer options are on — no version check, and no trusting the setting.
 */
class DeveloperOptionsPolicyTest {

    private fun of(
        settingSaysOn: Boolean = false,
        usbDebugging: UsbDebuggingState = UsbDebuggingState.UNKNOWN,
        wirelessDebuggingOn: Boolean = false,
        adbd: AdbdState = AdbdState.UNKNOWN,
    ) = DeveloperOptionsPolicy.of(settingSaysOn, usbDebugging, wirelessDebuggingOn, adbd)

    @Test
    fun `a setting that reads on is the truth`() {
        assertEquals(DeveloperOptionsState.ON, of(settingSaysOn = true))
    }

    // ---- The proofs. Each of these is a switch that cannot be reached with Developer options off. ----

    @Test
    fun `wireless debugging being on proves developer options are on`() {
        // This is issue #40's reporter exactly: "Debugging switches at the time of the problem: Both ON".
        // Before this, onboarding sent him to About-phone to enable something already enabled.
        assertEquals(DeveloperOptionsState.ON, of(settingSaysOn = false, wirelessDebuggingOn = true))
    }

    @Test
    fun `USB debugging proven on proves developer options are on`() {
        assertEquals(DeveloperOptionsState.ON, of(settingSaysOn = false, usbDebugging = UsbDebuggingState.ON))
    }

    @Test
    fun `a running adbd proves developer options are on`() {
        // adbd only runs when one of the two debugging switches is on (R1), and neither can be reached
        // with Developer options off. Covers the phone whose switches we cannot read at all.
        assertEquals(DeveloperOptionsState.ON, of(settingSaysOn = false, adbd = AdbdState.RUNNING))
    }

    // ---- A genuine OFF is still reported, so the Build-number instruction is still given ----

    @Test
    fun `nothing on and the setting reading off is taken as off`() {
        // Android <=16 with Developer options genuinely off -- unchanged from today. On a redacting build
        // this is also the honest answer: we cannot prove it either way, and the user needs to turn a
        // debugging switch on regardless, so the advice is right even if the premise is uncertain.
        assertEquals(
            DeveloperOptionsState.OFF,
            of(settingSaysOn = false, usbDebugging = UsbDebuggingState.OFF, wirelessDebuggingOn = false, adbd = AdbdState.STOPPED),
        )
    }

    @Test
    fun `an unreadable adbd with nothing else known is not called off`() {
        // No evidence at all. Saying "off" here is how a guess becomes an instruction to the user.
        assertEquals(
            DeveloperOptionsState.UNKNOWN,
            of(settingSaysOn = false, usbDebugging = UsbDebuggingState.UNKNOWN, adbd = AdbdState.UNKNOWN),
        )
    }

    // ---- What onboarding is allowed to do with it ----

    @Test
    fun `only a proven off may send the user to the About phone screen`() {
        // The #40 dead end: routing on "not proven on" instead of "proven off" meant a redacting phone
        // could never leave that screen, because tapping Build number cannot change a redacted read.
        assertEquals(true, DeveloperOptionsState.OFF.shouldOfferBuildNumberStep)
        assertEquals(false, DeveloperOptionsState.ON.shouldOfferBuildNumberStep)
        assertEquals(false, DeveloperOptionsState.UNKNOWN.shouldOfferBuildNumberStep)
    }

    @Test
    fun `setup is never blocked on this, whatever it says`() {
        // Even a proven off must not block: the instruction is shown, and the user carries on to pairing.
        // If Developer options really are off they will find Wireless debugging missing and the hint is
        // right there -- which is strictly better than a button that cannot advance.
        DeveloperOptionsState.entries.forEach { assertEquals(false, it.blocksSetup) }
    }
}
