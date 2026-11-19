/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N15: the recorder's Wi-Fi watch lines reach the app's always-on log once each, pushed when the app can be
 * reached and kept for the app's next drain when it cannot.
 */
class WifiJoinEventQueueTest {

    private val pushed = mutableListOf<List<String>>()
    private var appReachable = true
    private val queue = WifiJoinEventQueue(
        push = { lines -> if (appReachable) pushed += lines.toList(); appReachable },
        post = { it() },
        capacity = 3,
    )

    private fun event(n: Int) = WifiJoinEvent(n.toLong(), false, "line $n")

    @Test
    fun `a line is pushed to the app at once and not kept`() {
        queue.add(event(1))

        assertEquals(listOf(listOf(event(1).encode())), pushed)
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `lines the app could not take are kept, in order, and handed over once`() {
        appReachable = false
        queue.add(event(1))
        queue.add(event(2))

        assertEquals(listOf(event(1).encode(), event(2).encode()), queue.drain().toList())
        assertTrue(queue.drain().isEmpty())
    }

    @Test
    fun `kept lines go with the next push that gets through`() {
        appReachable = false
        queue.add(event(1))
        appReachable = true
        queue.add(event(2))

        assertEquals(listOf(listOf(event(1).encode(), event(2).encode())), pushed)
    }

    @Test
    fun `only the newest lines are kept when the app stays away, with a note of how many were dropped`() {
        appReachable = false
        (1..5).forEach { queue.add(event(it)) }

        val drained = queue.drain().map { WifiJoinEvent.decode(it)!! }
        assertEquals(
            listOf("2 earlier lines were dropped while the app could not be reached", "line 3", "line 4", "line 5"),
            drained.map { it.text },
        )
        assertTrue(drained.first().warn)
    }
}
