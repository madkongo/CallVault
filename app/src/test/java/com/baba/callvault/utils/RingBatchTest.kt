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
}
