/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which loopback port to use when adbd is already listening in tcp mode.
 *
 * A tester, POCO X7 Pro, 2026-10-22: Shizuku's watchdog with TCP mode moved adbd to its own port. CallVault only
 * accepted its own port, so it re-armed, which restarted adbd and stopped Shizuku, whose watchdog moved adbd
 * back. Recording stayed down until Shizuku was uninstalled.
 */
class LoopbackPortsTest {

    @Test
    fun `reads a real port from the property`() {
        assertEquals(5555, LoopbackPorts.parse("5555"))
        assertEquals(5555, LoopbackPorts.parse(" 5555\n"))
    }

    @Test
    fun `an unset, zero, negative or junk property is no port`() {
        assertNull(LoopbackPorts.parse(null))
        assertNull(LoopbackPorts.parse(""))
        assertNull(LoopbackPorts.parse("0"))
        assertNull(LoopbackPorts.parse("-1"))
        assertNull(LoopbackPorts.parse("65536"))
        assertNull(LoopbackPorts.parse("abc"))
    }

    @Test
    fun `our own port armed means only our port is tried`() {
        assertEquals(listOf(47279), LoopbackPorts.candidates(ourPort = 47279, adbdPort = 47279))
    }

    @Test
    fun `nothing armed still tries our port, for a listener the property has not caught up with`() {
        assertEquals(listOf(47279), LoopbackPorts.candidates(ourPort = 47279, adbdPort = null))
    }

    @Test
    fun `another app's port is tried first, so we use it instead of restarting adbd`() {
        assertEquals(listOf(5555, 47279), LoopbackPorts.candidates(ourPort = 47279, adbdPort = 5555))
    }

    @Test
    fun `another app's port is usable only in offline mode, where CallVault dials loopback`() {
        // Outside offline mode ensureConnected never dials loopback. Counting Shizuku's port there made
        // recovery pick CONNECT over restoring Wireless debugging and lose ~90 s (review, 2026-10-22).
        assertTrue(LoopbackPorts.isUsable(ourPort = 47279, adbdPort = 5555, offlineMode = true))
        assertFalse(LoopbackPorts.isUsable(ourPort = 47279, adbdPort = 5555, offlineMode = false))
        assertTrue(LoopbackPorts.isUsable(ourPort = 47279, adbdPort = 47279, offlineMode = false))
        assertFalse(LoopbackPorts.isUsable(ourPort = 47279, adbdPort = null, offlineMode = true))
    }

    @Test
    fun `any tcp port counts as a listener, but only ours may be closed`() {
        assertTrue(LoopbackPorts.isListening(adbdPort = 5555))
        assertTrue(LoopbackPorts.isListening(adbdPort = 47279))
        assertFalse(LoopbackPorts.isListening(adbdPort = null))

        assertTrue(LoopbackPorts.isOurs(ourPort = 47279, adbdPort = 47279))
        assertFalse(LoopbackPorts.isOurs(ourPort = 47279, adbdPort = 5555))
        assertFalse(LoopbackPorts.isOurs(ourPort = 47279, adbdPort = null))
    }
}
