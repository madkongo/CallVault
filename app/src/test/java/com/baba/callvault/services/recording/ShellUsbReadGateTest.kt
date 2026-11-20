/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.AdbdState
import com.baba.callvault.integrations.adb.UsbDebuggingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep-alive asked the recorder for `settings get global adb_enabled` on every 60 s tick — a fork+exec in the
 * shell-uid process each awake minute, charged to "Android System". It only answers anything when the app's own read
 * is 0 and Wireless debugging (or an unreadable adbd) leaves USB debugging open.
 */
class ShellUsbReadGateTest {

    /** The everyday Android 17 state: setting redacted to 0, USB debugging holds adbd up, Wireless debugging off. */
    private val usbOnWdOff = UsbDebuggingPolicy.needsShellRead(false, AdbdState.RUNNING, wirelessDebuggingOn = false)

    /** The Honor 2026-11-04 state: Wireless debugging on, so only the shell can say whether USB debugging is. */
    private val wdOn = UsbDebuggingPolicy.needsShellRead(false, AdbdState.RUNNING, wirelessDebuggingOn = true)

    @Test
    fun `a tick does not ask the recorder when the read cannot change the answer`() {
        val gate = ShellUsbReadGate()
        repeat(5) {
            val step = gate.decide(usbOnWdOff)
            assertFalse(step.read)
            assertTrue(step.forget) // a value left over from a needed state must not read as fresh
        }
    }

    @Test
    fun `a tick does not ask when the setting itself reads on`() {
        val gate = ShellUsbReadGate()
        assertFalse(gate.decide(UsbDebuggingPolicy.needsShellRead(true, AdbdState.RUNNING, true)).read)
    }

    @Test
    fun `turning Wireless debugging on starts a read at once`() {
        val gate = ShellUsbReadGate()
        assertFalse(gate.decide(usbOnWdOff).read)
        // The Wireless debugging observer runs the same decision the moment the switch flips.
        val step = gate.decide(wdOn)
        assertTrue(step.read)
        assertFalse(step.forget)
    }

    @Test
    fun `while it is needed every tick reads, so the value stays fresh`() {
        val gate = ShellUsbReadGate()
        repeat(3) { assertTrue(gate.decide(wdOn).read) }
    }

    @Test
    fun `turning Wireless debugging off again forgets the read`() {
        val gate = ShellUsbReadGate()
        gate.decide(wdOn)
        val step = gate.decide(usbOnWdOff)
        assertFalse(step.read)
        assertTrue(step.forget)
    }

    @Test
    fun `it logs only when the need changes, not every tick`() {
        val gate = ShellUsbReadGate()
        assertNotNull(gate.decide(usbOnWdOff).log)
        assertNull(gate.decide(usbOnWdOff).log)
        assertNull(gate.decide(usbOnWdOff).log)
        val started = gate.decide(wdOn).log
        assertNotNull(started)
        assertNull(gate.decide(wdOn).log)
        val stopped = gate.decide(usbOnWdOff).log
        assertNotNull(stopped)
        assertEquals(false, started == stopped)
    }
}
