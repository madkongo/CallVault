/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.summary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C175: a quick second tap on the summary icon must not queue the same call twice. */
class SummaryRequestGuardTest {

    @Test
    fun `a second tap on the same recording inside the window is dropped`() {
        val guard = SummaryRequestGuard()
        assertTrue(guard.tryAcquire("a.ogg", 1_000L))
        assertFalse(guard.tryAcquire("a.ogg", 1_400L))
    }

    @Test
    fun `a different recording is not held back`() {
        val guard = SummaryRequestGuard()
        assertTrue(guard.tryAcquire("a.ogg", 1_000L))
        assertTrue(guard.tryAcquire("b.ogg", 1_100L))
    }

    @Test
    fun `the same recording can be asked again once the window has passed`() {
        val guard = SummaryRequestGuard()
        assertTrue(guard.tryAcquire("a.ogg", 1_000L))
        assertTrue(guard.tryAcquire("a.ogg", 1_000L + SummaryRequestGuard.WINDOW_MS))
    }

    @Test
    fun `a finished run or a stop lets the next request through at once`() {
        val guard = SummaryRequestGuard()
        assertTrue(guard.tryAcquire("a.ogg", 1_000L))
        guard.release("a.ogg")
        assertTrue(guard.tryAcquire("a.ogg", 1_050L))
        guard.clear()
        assertTrue(guard.tryAcquire("a.ogg", 1_060L))
    }

    @Test
    fun `a clock that went backwards does not block a request`() {
        val guard = SummaryRequestGuard()
        assertTrue(guard.tryAcquire("a.ogg", 5_000L))
        assertTrue(guard.tryAcquire("a.ogg", 1_000L))
    }
}
