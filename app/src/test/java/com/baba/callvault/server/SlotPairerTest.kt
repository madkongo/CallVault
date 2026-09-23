/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.server.SlotPairer.Take
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Pairing the two sides of an app call by the real time their audio was captured, not by arrival.
 *
 * Issue #41's log: the near side stalled 155 times in two minutes while the far side kept coming, and
 * pairing by arrival consumed the far queue at the near side's pace — the far party ended 6.4 s late.
 * The file now walks 20 ms slots of real time; a side that has no audio for a slot gets silence
 * there and the other side stays where it was.
 */
class SlotPairerTest {

    private val chunk = 20_000_000L          // 20 ms in nanos
    private val pairer = SlotPairer(chunkNanos = chunk)

    @Test
    fun `a chunk whose audio belongs to the slot is taken`() {
        assertEquals(Take.TAKE, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = 1_000 * chunk))
        // Within half a chunk either way still belongs here: HAL timestamps are not chunk-aligned.
        assertEquals(Take.TAKE, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = 1_000 * chunk + chunk / 2 - 1))
        assertEquals(Take.TAKE, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = 1_000 * chunk - chunk / 2))
    }

    @Test
    fun `a side with nothing yet, or whose next audio is later than the slot, gets silence`() {
        assertEquals(Take.SILENCE, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = null))
        assertEquals(Take.SILENCE, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = 1_001 * chunk))
    }

    @Test
    fun `a chunk older than the slot is discarded, its slot has already gone by`() {
        assertEquals(Take.DISCARD, pairer.classify(slotNanos = 1_000 * chunk, headContentNanos = 999 * chunk))
    }

    @Test
    fun `the loop runs a slot only once its audio has had time to arrive`() {
        // A chunk's audio exists at its content time but is read a buffer later — up to 160 ms for
        // the near record. Running the slot before that would silence a side whose chunk is on its way.
        val slot = 5_000 * chunk
        assertFalse(pairer.due(slotNanos = slot, nowNanos = slot + SlotPairer.GRACE_NANOS - 1))
        assertTrue(pairer.due(slotNanos = slot, nowNanos = slot + SlotPairer.GRACE_NANOS))
    }

    @Test
    fun `a one second near stall leaves the far side exactly where it was`() {
        // Far: continuous from t=0. Near: continuous, but nothing captured between 2.0 s and 3.0 s.
        val far = ArrayDeque((0 until 300).map { it * chunk })
        val near = ArrayDeque((0 until 300).filter { it < 100 || it >= 150 }.map { it * chunk })
        val pairs = mutableListOf<Pair<Long?, Long?>>()
        var slot = 0L
        repeat(300) {
            val n = take(near, slot)
            val f = take(far, slot)
            pairs += n to f
            slot += chunk
        }
        // Every far chunk sits in the slot of its own capture time.
        pairs.forEachIndexed { i, (_, f) -> assertEquals(i * chunk, f) }
        // The near side is silent for exactly the stalled second, and in step everywhere else.
        assertEquals(50, pairs.count { it.first == null })
        pairs.forEachIndexed { i, (n, _) -> if (n != null) assertEquals(i * chunk, n) }
        assertTrue(near.isEmpty() && far.isEmpty())
    }

    @Test
    fun `a side that started late is padded at the head, not shifted`() {
        // Far first at 169 ms, near first at 202 ms (the reporter's numbers). Anchoring at the earlier
        // one puts one silent near slot at the head; nothing else moves.
        val far = ArrayDeque((0 until 10).map { 169_000_000L + it * chunk })
        val near = ArrayDeque((0 until 10).map { 202_000_000L + it * chunk })
        val t0 = pairer.anchor(firstNear = near.peek(), firstFar = far.peek())
        assertEquals(169_000_000L, t0)
        var slot = t0
        val nearTaken = mutableListOf<Long?>()
        repeat(11) { nearTaken += take(near, slot); take(far, slot); slot += chunk }
        assertEquals(null, nearTaken[0])
        assertEquals(202_000_000L, nearTaken[2])   // 209 ms slot ± half a chunk holds the 202 ms audio
    }

    /** Applies [SlotPairer.classify] the way the loop does: discard until the head belongs or is later. */
    private fun take(q: ArrayDeque<Long>, slot: Long): Long? {
        while (true) {
            when (pairer.classify(slot, q.peek())) {
                Take.TAKE -> return q.poll()
                Take.SILENCE -> return null
                Take.DISCARD -> q.poll()
            }
        }
    }
}
