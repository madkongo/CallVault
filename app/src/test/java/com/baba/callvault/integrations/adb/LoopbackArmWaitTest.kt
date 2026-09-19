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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Waiting for the loopback listener after a `tcpip:` arm.
 *
 * The case that made this necessary: the OP12's boot of 2026-09-19 17:04, where a single attempt 2 s
 * after the arm was refused three times running and turned a two-second job into 73 seconds and five or
 * six Wireless-debugging cycles. See docs/dev-notes/2026-09-19-reboot-deadlock-wd-off-by-user.md.
 */
class LoopbackArmWaitTest {

    /** A clock that only moves when the code under test sleeps — so the test is instant and exact. */
    private class Fake(
        var armed: Boolean = true,
        /** Connect succeeds once the clock has passed this. */
        val listeningFrom: Long = Long.MAX_VALUE,
    ) {
        var clock = 0L
        var connects = 0
        fun now() = clock
        fun sleep(ms: Long) { clock += ms }
        fun connect(): Boolean { connects++; return clock >= listeningFrom }
    }

    private fun await(f: Fake, budgetMs: Long = 12_000, intervalMs: Long = 1_500) =
        LoopbackArmWait.awaitListener(
            budgetMs = budgetMs,
            intervalMs = intervalMs,
            now = f::now,
            sleep = f::sleep,
            isArmed = { f.armed },
            connect = f::connect,
        )

    @Test
    fun `a listener that is already up costs one attempt and no waiting`() {
        val f = Fake(listeningFrom = 0)
        assertTrue(await(f))
        assertEquals(1, f.connects)
        assertEquals(0L, f.clock)
    }

    @Test
    fun `a listener that takes a few seconds is waited for, not written off`() {
        // The OP12's boot: the port was armed but nothing was bound yet. The old code took one look at
        // 2 s, called the whole arm a failure, and sent the next round through a 12 s mDNS timeout.
        val f = Fake(listeningFrom = 6_000)
        assertTrue(await(f))
        assertEquals(6_000L, f.clock)
    }

    @Test
    fun `an arm that never took is abandoned at once, not waited out`() {
        // The property is the only thing that tells "not listening yet" from "the request never landed".
        // Without this check a genuine failure would hold the caller for the whole budget.
        val f = Fake(armed = false)
        assertFalse(await(f))
        assertEquals(1, f.connects)
        assertEquals(0L, f.clock)
    }

    @Test
    fun `a listener that never comes up gives up at the budget`() {
        val f = Fake(armed = true, listeningFrom = Long.MAX_VALUE)
        assertFalse(await(f))
        assertTrue("must not run past the budget", f.clock <= 12_000 + 1_500)
    }

    @Test
    fun `the port going away mid-wait ends the wait`() {
        // adbd restarted again underneath us -- e.g. the user turned USB debugging off. Waiting out the
        // rest of the budget would only delay the caller's real recovery.
        val f = object {
            val inner = Fake(armed = true, listeningFrom = Long.MAX_VALUE)
        }.inner
        var looks = 0
        val ok = LoopbackArmWait.awaitListener(
            budgetMs = 12_000,
            intervalMs = 1_500,
            now = f::now,
            sleep = f::sleep,
            isArmed = { looks++; looks < 3 },
            connect = f::connect,
        )
        assertFalse(ok)
        assertTrue("gave up well inside the budget", f.clock < 6_000)
    }
}
