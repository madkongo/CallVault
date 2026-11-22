/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C99 review: while a user-off verdict is pending, recovery must not switch Wireless debugging back on. */
class UserOffPendingTest {
    @Test
    fun `blocks inside the window`() {
        val p = UserOffPending()
        p.start(nowMs = 1_000)
        assertTrue(p.isPending(nowMs = 1_000))
        assertTrue(p.isPending(nowMs = 1_000 + WirelessDebuggingSettledVerdict.SETTLE_WINDOW_MS))
    }

    @Test
    fun `expires by itself after the window plus a second`() {
        val p = UserOffPending()
        p.start(nowMs = 1_000)
        assertFalse(p.isPending(nowMs = 1_000 + WirelessDebuggingSettledVerdict.SETTLE_WINDOW_MS + 1_000))
    }

    @Test
    fun `clear ends it at once`() {
        val p = UserOffPending()
        p.start(nowMs = 1_000)
        p.clear()
        assertFalse(p.isPending(nowMs = 1_001))
    }

    @Test
    fun `never started is not pending`() {
        assertFalse(UserOffPending().isPending(nowMs = 0))
    }
}
