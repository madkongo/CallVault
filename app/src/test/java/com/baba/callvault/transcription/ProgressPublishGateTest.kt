/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressPublishGateTest {

    @Test
    fun `the first sample is published`() {
        assertTrue(ProgressPublishGate().shouldPublish(0, 1, "a.ogg", 0))
    }

    @Test
    fun `a repeated sample is not written again`() {
        val gate = ProgressPublishGate()
        gate.shouldPublish(0, 1, "a.ogg", 12)
        assertFalse(gate.shouldPublish(0, 1, "a.ogg", 12))
    }

    @Test
    fun `a new percent, recording, count or total is written`() {
        val gate = ProgressPublishGate()
        gate.shouldPublish(0, 2, "a.ogg", 12)
        assertTrue(gate.shouldPublish(0, 2, "a.ogg", 13))
        assertTrue(gate.shouldPublish(0, 2, "b.ogg", 13))
        assertTrue(gate.shouldPublish(1, 2, "b.ogg", 13))
        assertTrue(gate.shouldPublish(1, 3, "b.ogg", 13))
    }

    @Test
    fun `a 22 minute run sampled every 400 ms writes at most once per whole percent`() {
        val gate = ProgressPublishGate()
        val samples = 22 * 60 * 1000 / 400 // 3,300
        val writes = (0 until samples).count { i -> gate.shouldPublish(0, 1, "a.ogg", i * 100 / samples) }
        assertTrue("wrote $writes times", writes <= 100)
        assertEquals(100, writes)
    }
}
