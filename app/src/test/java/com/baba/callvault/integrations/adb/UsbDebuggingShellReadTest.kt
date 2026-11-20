/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What [UsbDebuggingShellRead.current] gives the policy, now that the keep-alive no longer reads every tick.
 */
class UsbDebuggingShellReadTest {

    private val t0 = 1_000_000L

    @Before
    fun reset() = UsbDebuggingShellRead.forget()

    private fun answer(adbd: AdbdState, now: Long) = UsbDebuggingPolicy.of(
        settingSaysOn = false,
        adbd = adbd,
        wirelessDebuggingOn = false,
        shellSaysOn = UsbDebuggingShellRead.current(now),
    )

    @Test
    fun `S1 - a screen-off adbd restart on a redacting phone with USB on still reads ON, as with the per-tick read`() {
        // Tick: setting redacted to 0, Wireless debugging off, adbd up. R1 proves USB debugging on; no shell read.
        assertEquals(ShellReadNeed.R1_DECIDES, UsbDebuggingPolicy.shellReadNeed(false, AdbdState.RUNNING, false))
        UsbDebuggingShellRead.noteProvenOnByR1(UsbDebuggingShellRead.generation(), t0)
        assertEquals(UsbDebuggingState.ON, answer(AdbdState.RUNNING, t0))

        // adbd stops for a moment (OnePlus/Xiaomi/Samsung screen-off restart). Before this branch the last tick's shell
        // read (1) answered ON here; the R1 proof must answer the same, not fall to R2's OFF.
        assertEquals(UsbDebuggingState.ON, answer(AdbdState.STOPPED, t0 + 30_000))
    }

    @Test
    fun `an R1 proof expires like a shell read, so a long adbd stop still reads OFF`() {
        UsbDebuggingShellRead.noteProvenOnByR1(UsbDebuggingShellRead.generation(), t0)
        assertEquals(UsbDebuggingState.OFF, answer(AdbdState.STOPPED, t0 + 3 * 60_000))
    }

    @Test
    fun `a switch change forgets an R1 proof, so turning USB debugging off reads OFF at once`() {
        UsbDebuggingShellRead.noteProvenOnByR1(UsbDebuggingShellRead.generation(), t0)
        UsbDebuggingShellRead.forget()
        assertEquals(UsbDebuggingState.OFF, answer(AdbdState.STOPPED, t0 + 1_000))
    }

    @Test
    fun `a shell read is kept until it expires - nothing on a tick forgets it`() {
        UsbDebuggingShellRead.refreshWith(t0) { "1" }
        assertEquals(true, UsbDebuggingShellRead.current(t0 + 90_000))
    }

    @Test
    fun `S3 - a read still running when a switch changes is dropped, not stored as fresh`() {
        // The adb_enabled observer starts a read; USB debugging is switched off while it is in flight.
        val got = UsbDebuggingShellRead.refreshWith(t0) {
            UsbDebuggingShellRead.forget() // the switch change lands mid-read
            "1" // what the shell saw before the change
        }
        assertEquals(null, got)
        assertEquals(null, UsbDebuggingShellRead.current(t0 + 1_000))
        // So R2 decides again instead of the stale on.
        assertEquals(UsbDebuggingState.OFF, answer(AdbdState.STOPPED, t0 + 1_000))
    }

    @Test
    fun `a read begun after the switch change is kept`() {
        UsbDebuggingShellRead.forget()
        assertEquals(false, UsbDebuggingShellRead.refreshWith(t0) { "0" })
        assertEquals(false, UsbDebuggingShellRead.current(t0 + 1_000))
    }
}
