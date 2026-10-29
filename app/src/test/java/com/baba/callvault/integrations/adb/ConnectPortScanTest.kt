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
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Issue #65: the order and bounds of the loopback search for Wireless debugging's port. */
class ConnectPortScanTest {

    private val range = 32_768..60_999

    @Test
    fun `with no last good port the order is the whole ephemeral range, low to high`() {
        val order = ConnectPortScan.order(null)
        assertEquals(range.last - range.first + 1, order.size)
        assertEquals(range.first, order.first())
        assertEquals(range.last, order.last())
    }

    @Test
    fun `the last good port comes first, then outward from it, then the rest`() {
        val order = ConnectPortScan.order(40_000)
        assertEquals(listOf(40_000, 40_001, 39_999, 40_002, 39_998), order.take(5))
        assertEquals(40_240, order[1 + 2 * 239])
        assertEquals(39_760, order[2 * 240])
        // After the window: the range from the bottom.
        assertEquals(range.first, order[1 + 2 * 240])
    }

    @Test
    fun `no port is asked twice and every port in range is asked once`() {
        val order = ConnectPortScan.order(40_000)
        assertEquals(order.size, order.toSet().size)
        assertEquals(range.toSet(), order.toSet())
    }

    @Test
    fun `the window is clipped to the range at the edges`() {
        val order = ConnectPortScan.order(range.last - 2)
        assertEquals(listOf(range.last - 2, range.last - 1, range.last - 3, range.last, range.last - 4, range.last - 5), order.take(6))
        assertTrue(order.all { it in range })
    }

    @Test
    fun `a last good port outside the range is still asked first, its window clipped`() {
        val order = ConnectPortScan.order(5_555)
        assertEquals(5_555, order.first())
        assertEquals(range.first, order[1])
        assertEquals(range.count() + 1, order.size)
    }

    @Test
    fun `excluded ports are never asked, even as the last good port`() {
        val order = ConnectPortScan.order(40_000, exclude = setOf(40_000, 40_001, 50_000))
        assertEquals(39_999, order.first())
        assertFalse(order.contains(40_000))
        assertFalse(order.contains(40_001))
        assertFalse(order.contains(50_000))
    }

    @Test
    fun `the scan returns the port that answers STLS and counts what it saw`() {
        val ports = (1..200).toList().toIntArray()
        val result = ConnectPortScan.scan(ports, threads = 4, budgetMs = 5_000) { port ->
            when (port) {
                150 -> PortProbe.ADB_TLS
                20 -> PortProbe.ADB_PLAIN
                10, 30 -> PortProbe.OPEN_NOT_ADB
                else -> PortProbe.CLOSED
            }
        }
        assertEquals(150, result.port)
        assertEquals(listOf(20), result.plainAdbPorts)
        assertTrue(result.open >= 4)
        assertFalse(result.incomplete)
    }

    @Test
    fun `a classic adb listener alone is not handed over`() {
        val result = ConnectPortScan.scan(intArrayOf(1, 2, 3), threads = 2, budgetMs = 5_000) {
            if (it == 2) PortProbe.ADB_PLAIN else PortProbe.CLOSED
        }
        assertNull(result.port)
        assertEquals(listOf(2), result.plainAdbPorts)
        assertEquals(3, result.probed)
        assertFalse(result.incomplete)
    }

    @Test
    fun `the scan stops early once found rather than asking every port`() {
        val asked = Collections.synchronizedList(mutableListOf<Int>())
        val ports = (1..10_000).toList().toIntArray()
        val result = ConnectPortScan.scan(ports, threads = 1, budgetMs = 5_000) {
            asked.add(it)
            if (it == 3) PortProbe.ADB_TLS else PortProbe.CLOSED
        }
        assertEquals(3, result.port)
        assertEquals(listOf(1, 2, 3), asked)
    }

    @Test
    fun `the scan gives up when its budget runs out, even with stuck probes`() {
        val ports = (1..1_000).toList().toIntArray()
        val started = System.currentTimeMillis()
        val result = ConnectPortScan.scan(ports, threads = 4, budgetMs = 300) {
            Thread.sleep(100)
            PortProbe.CLOSED
        }
        val took = System.currentTimeMillis() - started
        assertNull(result.port)
        assertTrue(result.incomplete)
        assertTrue("took $took ms", took < 1_500)
        assertTrue(result.probed < ports.size)
    }

    @Test
    fun `a cancelled scan stops`() {
        val cancelled = AtomicBoolean(true)
        val result = ConnectPortScan.scan(intArrayOf(1, 2, 3), threads = 1, budgetMs = 5_000, cancelled = cancelled) {
            PortProbe.ADB_TLS
        }
        assertNull(result.port)
        assertEquals(0, result.probed)
        assertTrue(result.incomplete)
    }

    @Test
    fun `the budget is read from the injected clock`() {
        var clock = 0L
        val result = ConnectPortScan.scan(intArrayOf(1, 2, 3, 4), threads = 1, budgetMs = 10, now = { clock }) {
            clock += 6
            PortProbe.CLOSED
        }
        assertEquals(2, result.probed)
        assertTrue(result.incomplete)
    }
}
