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
 * Whether USB debugging is on, when the setting may be lying.
 *
 * Android 17 makes `Settings.Global.ADB_ENABLED` read as `0` for every app whatever the truth
 * (issue #40, docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md). A version check is NOT a
 * safe way to know that — Android 16 custom ROMs backport the redaction, so the same lie arrives on
 * `SDK_INT == 36`. So nothing here asks what Android version it is; it corroborates instead, using R1 and
 * R2 of the measured switch model (docs/dev-notes/2026-09-14-debugging-switches-model.md).
 *
 * Each case below states which Android versions it covers, because the no-regressions requirement is
 * about Android ≤16, where the setting is truthful and everything works today.
 */
class UsbDebuggingPolicyTest {

    private fun of(
        settingSaysOn: Boolean = false,
        adbd: AdbdState = AdbdState.UNKNOWN,
        wirelessDebuggingOn: Boolean = false,
    ) = UsbDebuggingPolicy.of(settingSaysOn, adbd, wirelessDebuggingOn)

    // ---- A setting that says ON is always believed: the redaction only ever fakes a zero ----

    @Test
    fun `a setting that reads on is the truth on every android version`() {
        assertEquals(UsbDebuggingState.ON, of(settingSaysOn = true, adbd = AdbdState.RUNNING))
        assertEquals(UsbDebuggingState.ON, of(settingSaysOn = true, adbd = AdbdState.STOPPED))
        assertEquals(UsbDebuggingState.ON, of(settingSaysOn = true, adbd = AdbdState.UNKNOWN))
    }

    // ---- The Android 17 case: the setting says off and it is provably wrong ----

    @Test
    fun `adbd running with wireless debugging off proves USB debugging is on`() {
        // R1: adbd runs while USB debugging OR Wireless debugging is on. Wireless is off and adbd is up,
        // so the only thing holding it up is USB debugging -- whatever the setting claims. This is a
        // proof, not a guess, and it is what rescues an Android 17 phone without asking its version.
        assertEquals(
            UsbDebuggingState.ON,
            of(settingSaysOn = false, adbd = AdbdState.RUNNING, wirelessDebuggingOn = false),
        )
    }

    @Test
    fun `with wireless debugging also on, adbd proves nothing`() {
        // Wireless debugging alone explains adbd being up, so USB debugging could be either. On Android
        // <=16 this reads as a truthful "off"; on 17 it is unknowable. We say UNKNOWN rather than pick.
        assertEquals(
            UsbDebuggingState.UNKNOWN,
            of(settingSaysOn = false, adbd = AdbdState.RUNNING, wirelessDebuggingOn = true),
        )
    }

    // ---- A genuine OFF must still be reported, or we lose the ability to help ----

    @Test
    fun `a stopped adbd with the setting reading off means it really is off`() {
        // R1 contrapositive: nothing is keeping adbd up. This is the case the Shizuku forks' blanket
        // "treat 0 as unknown on SDK >= 37" throws away, and with it every chance to tell a user which
        // switch to turn on.
        assertEquals(
            UsbDebuggingState.OFF,
            of(settingSaysOn = false, adbd = AdbdState.STOPPED, wirelessDebuggingOn = false),
        )
    }

    @Test
    fun `a stopped adbd while wireless debugging reads on is the R2 state, and still off`() {
        // R2, measured: turning USB debugging off stops adbd even with Wireless debugging reading on.
        // So a stopped adbd is evidence about USB debugging regardless of what Wireless says.
        assertEquals(
            UsbDebuggingState.OFF,
            of(settingSaysOn = false, adbd = AdbdState.STOPPED, wirelessDebuggingOn = true),
        )
    }

    // ---- Never invent an answer ----

    @Test
    fun `an unreadable adbd with the setting reading off is unknown, not off`() {
        // AdbdState.UNKNOWN covers "restarting" and an unreadable property. Calling that OFF is how an
        // unreadable state becomes a false claim that the user switched something off.
        assertEquals(
            UsbDebuggingState.UNKNOWN,
            of(settingSaysOn = false, adbd = AdbdState.UNKNOWN, wirelessDebuggingOn = false),
        )
    }

    // ---- The Android <=16 rows: unchanged from the boolean this replaces ----

    @Test
    fun `on android 16 and below every real state still resolves, not to unknown`() {
        // USB on, Wireless off, adbd up -- the recommended setup.
        assertEquals(UsbDebuggingState.ON, of(settingSaysOn = true, adbd = AdbdState.RUNNING))
        // USB off, Wireless off, adbd down -- the "nothing is on" state.
        assertEquals(UsbDebuggingState.OFF, of(settingSaysOn = false, adbd = AdbdState.STOPPED))
        // USB off, Wireless on, adbd up -- the only state that becomes UNKNOWN, and see below for why
        // that costs nothing.
        assertEquals(UsbDebuggingState.UNKNOWN, of(settingSaysOn = false, adbd = AdbdState.RUNNING, wirelessDebuggingOn = true))
    }

    // ---- What callers are allowed to conclude ----

    @Test
    fun `only a proven ON counts as on, so nothing acts on a guess`() {
        assertEquals(true, UsbDebuggingState.ON.isOn)
        assertEquals(false, UsbDebuggingState.OFF.isOn)
        assertEquals(false, UsbDebuggingState.UNKNOWN.isOn)
    }

    @Test
    fun `only a proven OFF may be told to the user, so we never blame a switch we cannot read`() {
        // This is the property that stops "you turned Wireless debugging off" appearing on a phone whose
        // owner did nothing -- the 2026-09-19 reboot deadlock in its Android 17 form.
        assertEquals(true, UsbDebuggingState.OFF.isOff)
        assertEquals(false, UsbDebuggingState.ON.isOff)
        assertEquals(false, UsbDebuggingState.UNKNOWN.isOff)
    }
}
