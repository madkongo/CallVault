/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Third review 2026-11-20 (F2): an app that leaves the audio mode in a call (WhatsApp holding IN_COMMUNICATION, issue
 * #53) silently skipped every folder check for ever: Home stayed green and no alert came while calls piled up.
 */
class CallGuardTest {

    private val min = 60_000L

    @Test
    fun `no call - check, and any streak ends`() {
        val d = CallGuard.decide(audioCall = false, carrierCall = false, streakStart = 5L, now = 10L)
        assertFalse(d.skip)
        assertNull(d.streakStart)
    }

    @Test
    fun `an app-call audio mode skips the check, for at most the cap`() {
        val first = CallGuard.decide(audioCall = true, carrierCall = false, streakStart = null, now = 1_000L)
        assertTrue(first.skip)
        assertEquals(1_000L, first.streakStart)
        val stillShort = CallGuard.decide(true, false, first.streakStart, now = 1_000L + 29 * min)
        assertTrue(stillShort.skip)
        val stuck = CallGuard.decide(true, false, first.streakStart, now = 1_000L + CallGuard.STUCK_AFTER_MS)
        assertFalse(stuck.skip)
        assertTrue(stuck.overridden)
    }

    @Test
    fun `a real phone call always skips`() {
        assertTrue(CallGuard.decide(audioCall = true, carrierCall = true, streakStart = 0L, now = 10 * 60 * min).skip)
    }
}
