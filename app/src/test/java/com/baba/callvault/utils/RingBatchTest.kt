/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C138: the recorder host's log ring left in one piece, past the binder's 1 MB limit, and was cleared before the reply. */
class RingBatchTest {

    @Test
    fun `a ring that fits leaves in one batch and is emptied`() {
        val ring = ArrayDeque(listOf("a", "bb", "ccc"))
        assertEquals(listOf("a", "bb", "ccc"), AppLogger.takeBatch(ring, 100))
        assertTrue(ring.isEmpty())
    }

    @Test
    fun `a big ring leaves oldest first in bounded batches and nothing is lost`() {
        val lines = (1..50).map { "line-%03d-".format(it) + "z".repeat(90) }
        val ring = ArrayDeque(lines)
        val got = mutableListOf<String>()
        var batches = 0
        while (ring.isNotEmpty()) {
            val batch = AppLogger.takeBatch(ring, 1_000)
            assertTrue(batch.isNotEmpty())
            assertTrue("batch of ${batch.sumOf { it.length }} chars", batch.sumOf { it.length } <= 1_000)
            got += batch
            batches++
        }
        assertEquals(lines, got)
        assertTrue(batches > 1)
    }

    @Test
    fun `one line longer than the cap is cut to it rather than blocking the ring`() {
        val ring = ArrayDeque(listOf("q".repeat(5_000), "next"))
        val first = AppLogger.takeBatch(ring, 1_000)
        assertEquals(1, first.size)
        assertEquals(1_000, first[0].length)
        assertEquals(listOf("next"), AppLogger.takeBatch(ring, 1_000))
    }

    @Test
    fun `drainAll asks until the recorder has nothing and returns every batch`() {
        val batches = ArrayDeque(listOf(arrayOf("a", "b"), arrayOf("c"), emptyArray()))
        val got = AppLogger.drainAll({ batches.removeFirst() }, maxRounds = 10)
        assertEquals(listOf("a", "b", "c"), got)
        assertTrue(batches.isEmpty())
    }

    @Test
    fun `drainAll keeps the batches already taken when a later call throws`() {
        var calls = 0
        val got = AppLogger.drainAll({
            calls++
            if (calls == 3) throw IllegalStateException("binder died")
            arrayOf("batch$calls")
        }, maxRounds = 10)
        assertEquals(listOf("batch1", "batch2"), got)
    }

    @Test
    fun `drainAll stops at the round limit`() {
        var calls = 0
        AppLogger.drainAll({ calls++; arrayOf("x") }, maxRounds = 5)
        assertEquals(5, calls)
    }
}
