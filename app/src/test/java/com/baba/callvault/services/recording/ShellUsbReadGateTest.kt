/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.integrations.adb.AdbdState
import com.baba.callvault.integrations.adb.ShellReadNeed
import com.baba.callvault.integrations.adb.UsbDebuggingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keep-alive asked the recorder for `settings get global adb_enabled` on every 60 s tick — a fork+exec in the
 * shell-uid process each awake minute, charged to "Android System". It now asks only where the read can change the
 * answer; in the everyday Android 17 state R1 answers instead, and nothing is forgotten on a tick.
 */
class ShellUsbReadGateTest {

    /** The everyday Android 17 state: setting redacted to 0, USB debugging holds adbd up, Wireless debugging off. */
    private val usbOnWdOff = UsbDebuggingPolicy.shellReadNeed(false, AdbdState.RUNNING, wirelessDebuggingOn = false)

    /** The Honor 2026-11-04 state: Wireless debugging on, so only the shell can say whether USB debugging is. */
    private val wdOn = UsbDebuggingPolicy.shellReadNeed(false, AdbdState.RUNNING, wirelessDebuggingOn = true)

    @Test
    fun `a tick does not ask the recorder when R1 already proves the answer`() {
        val gate = ShellUsbReadGate()
        repeat(5) { assertEquals(ShellReadNeed.R1_DECIDES, gate.decide(usbOnWdOff).need) }
    }

    @Test
    fun `a tick does not ask when the setting itself reads on`() {
        val gate = ShellUsbReadGate()
        val need = UsbDebuggingPolicy.shellReadNeed(true, AdbdState.RUNNING, true)
        assertEquals(ShellReadNeed.SETTING_DECIDES, gate.decide(need).need)
    }

    @Test
    fun `turning Wireless debugging on starts a read at once`() {
        val gate = ShellUsbReadGate()
        assertEquals(ShellReadNeed.R1_DECIDES, gate.decide(usbOnWdOff).need)
        // The Wireless debugging observer runs the same decision the moment the switch flips.
        assertEquals(ShellReadNeed.READ, gate.decide(wdOn).need)
    }

    @Test
    fun `while it is needed every tick reads, so the value stays fresh`() {
        val gate = ShellUsbReadGate()
        repeat(3) { assertEquals(ShellReadNeed.READ, gate.decide(wdOn).need) }
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
