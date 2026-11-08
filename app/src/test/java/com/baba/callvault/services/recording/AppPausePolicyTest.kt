/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Noticing that the phone paused the app (Honor BVL-N59, 2026-11-05), without touching phones that never do. */
class AppPausePolicyTest {

    private val tick = AppPausePolicy.TICK_MS
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `an on-time tick is not a pause`() {
        assertEquals(0L, AppPausePolicy.pausedMs(1_000, 1_000 + tick))
        assertEquals(0L, AppPausePolicy.pausedMs(1_000, 1_000 + tick + 2_000))
    }

    @Test
    fun `a tick that wakes 48 seconds late is a 48 second pause`() {
        assertEquals(48_000L, AppPausePolicy.pausedMs(1_000, 1_000 + tick + 48_000))
    }

    @Test
    fun `the threshold itself counts, just under it does not`() {
        assertEquals(AppPausePolicy.MIN_PAUSE_MS, AppPausePolicy.pausedMs(0, tick + AppPausePolicy.MIN_PAUSE_MS))
        assertEquals(0L, AppPausePolicy.pausedMs(0, tick + AppPausePolicy.MIN_PAUSE_MS - 1))
    }

    @Test
    fun `a phone that never paused keeps today's capture path`() {
        assertFalse(AppPausePolicy.preferRecorderCapture(lastPauseAtMs = 0, nowMs = 10 * day))
    }

    @Test
    fun `a recent pause moves calls to the recorder's own capture`() {
        assertTrue(AppPausePolicy.preferRecorderCapture(lastPauseAtMs = 10 * day, nowMs = 11 * day))
    }

    @Test
    fun `a pause older than the memory window no longer counts`() {
        assertFalse(AppPausePolicy.preferRecorderCapture(10 * day, 10 * day + AppPausePolicy.REMEMBER_MS))
    }

    @Test
    fun `a pause stamped in the future after a clock change does not count`() {
        assertFalse(AppPausePolicy.preferRecorderCapture(lastPauseAtMs = 20 * day, nowMs = 10 * day))
    }
}
