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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The already-open adbd tcp port probe: which ports to ask, which answers count, and when not to ask again.
 *
 * A few ROMs (OEM / TV-derived / eng builds) have adbd listening on tcp from boot via `persist.adb.tcp.port`
 * or `service.adb.listen_addrs`. There CallVault can connect with no Wi-Fi at all. Everywhere else the probe
 * must cost next to nothing.
 */
class OpenAdbPortsTest {

    @Test
    fun `with no adbd tcp property set, nothing is asked`() {
        val ports = LoopbackPorts.alreadyOpenCandidates(null, null, emptyList(), emptySet(), OUR_PORT)

        assertEquals(emptyList<Int>(), ports)
    }

    @Test
    fun `5555 is never a candidate unless a property names it`() {
        // Any app can bind 127.0.0.1:5555 and pose as adbd to get CallVault's key to sign its token.
        val unnamed = LoopbackPorts.alreadyOpenCandidates(41_000, 6_000, listOf(7_000), emptySet(), OUR_PORT)
        val named = LoopbackPorts.alreadyOpenCandidates(null, 5555, emptyList(), emptySet(), OUR_PORT)

        assertFalse(5555 in unnamed)
        assertEquals(listOf(5555), named)
    }

    @Test
    fun `the live listener, then the ROM's persistent port, then listen_addrs`() {
        val ports = LoopbackPorts.alreadyOpenCandidates(
            serviceTcpPort = 41_000,
            persistTcpPort = 6_000,
            listenAddrPorts = listOf(7_000),
            alreadyTried = emptySet(),
            ourPort = OUR_PORT,
        )

        assertEquals(listOf(41_000, 6_000, 7_000), ports)
    }

    @Test
    fun `a port named twice is asked once`() {
        val ports = LoopbackPorts.alreadyOpenCandidates(5555, 5555, listOf(5555), emptySet(), OUR_PORT)

        assertEquals(listOf(5555), ports)
    }

    @Test
    fun `the listener CallVault armed itself is not asked`() {
        val ports = LoopbackPorts.alreadyOpenCandidates(OUR_PORT, null, emptyList(), emptySet(), OUR_PORT)

        assertEquals(emptyList<Int>(), ports)
    }

    @Test
    fun `ports offline mode has just been refused on are not asked again`() {
        val ports = LoopbackPorts.alreadyOpenCandidates(
            serviceTcpPort = 41_000,
            persistTcpPort = 6_000,
            listenAddrPorts = listOf(5555),
            alreadyTried = setOf(41_000, 5555),
            ourPort = OUR_PORT,
        )

        assertEquals(listOf(6_000), ports)
    }

    @Test
    fun `listen_addrs keeps only tcp ports`() {
        assertEquals(listOf(5555), LoopbackPorts.parseListenAddrs("tcp:5555,vsock:5555"))
        assertEquals(listOf(5555, 6000), LoopbackPorts.parseListenAddrs(" tcp:5555 , tcp:localhost:6000"))
    }

    @Test
    fun `an unset or junk listen_addrs gives no ports`() {
        assertEquals(emptyList<Int>(), LoopbackPorts.parseListenAddrs(null))
        assertEquals(emptyList<Int>(), LoopbackPorts.parseListenAddrs(""))
        assertEquals(emptyList<Int>(), LoopbackPorts.parseListenAddrs("vsock:5555"))
        assertEquals(emptyList<Int>(), LoopbackPorts.parseListenAddrs("tcp:abc,tcp:0,tcp:70000"))
    }

    @Test
    fun `only a classic adb answer is worth a connect`() {
        assertTrue(LoopbackPorts.isAlreadyOpenAdb(PortProbe.ADB_PLAIN))
        assertFalse(LoopbackPorts.isAlreadyOpenAdb(PortProbe.CLOSED))
        assertFalse(LoopbackPorts.isAlreadyOpenAdb(PortProbe.OPEN_NOT_ADB))
        assertFalse("Wireless debugging's TLS port is not this probe's", LoopbackPorts.isAlreadyOpenAdb(PortProbe.ADB_TLS))
    }

    @Test
    fun `asks the first time`() {
        val gate = OpenAdbPortGate()

        assertEquals(listOf(5555), gate.portsToProbe(listOf(5555)))
    }

    @Test
    fun `after a miss the same ports are not asked again`() {
        val gate = OpenAdbPortGate()
        gate.recordMiss(listOf(5555))

        assertEquals(emptyList<Int>(), gate.portsToProbe(listOf(5555)))
    }

    @Test
    fun `a change in adbd's ports asks again despite the miss`() {
        val gate = OpenAdbPortGate()
        gate.recordMiss(listOf(5555))

        assertEquals(listOf(41_000, 5555), gate.portsToProbe(listOf(41_000, 5555)))
    }

    @Test
    fun `a hit clears the miss so a lost connection asks again at once`() {
        val gate = OpenAdbPortGate()
        gate.recordMiss(listOf(5555))
        gate.recordHit()

        assertEquals(listOf(5555), gate.portsToProbe(listOf(5555)))
    }

    private companion object {
        const val OUR_PORT = 45_678
    }
}
