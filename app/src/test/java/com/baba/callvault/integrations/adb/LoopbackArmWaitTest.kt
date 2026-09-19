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
 * Two device boots shaped these. The 17:04 boot showed a single attempt 2 s after the arm being refused
 * three times running, turning a two-second job into 73 s and five or six Wireless-debugging cycles. The
 * 17:28 boot then showed the first fix giving up after **4 ms and 9 ms**, because it treated an unset
 * `service.adb.tcp.port` as proof the arm had failed — when in fact `adbd` had simply not restarted yet.
 * See docs/dev-notes/2026-09-19-reboot-deadlock-wd-off-by-user.md.
 */
class LoopbackArmWaitTest {

    /** A clock that only moves when the code under test sleeps — so the test is instant and exact. */
    private class Fake(
        /** The property starts unset while adbd restarts, and appears at this time. */
        val armedFrom: Long = 0,
        /** Connect succeeds once the clock has passed this. */
        val listeningFrom: Long = Long.MAX_VALUE,
    ) {
        var clock = 0L
        var connects = 0
        fun now() = clock
        fun sleep(ms: Long) { clock += ms }
        fun armed() = clock >= armedFrom
        fun connect(): Boolean { connects++; return clock >= listeningFrom }
    }

    private fun await(f: Fake, budgetMs: Long = 12_000, graceMs: Long = 5_000, intervalMs: Long = 1_500) =
        LoopbackArmWait.awaitListener(
            budgetMs = budgetMs,
            graceMs = graceMs,
            intervalMs = intervalMs,
            now = f::now,
            sleep = f::sleep,
            isArmed = f::armed,
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
    fun `the port appearing late is waited through, not read as failure`() {
        // The 17:28 boot exactly: two seconds after the arm the property was still unset, and the first
        // fix quit in 4 ms. adbd had not restarted yet -- the arm had landed perfectly well.
        val f = Fake(armedFrom = 4_000, listeningFrom = 4_500)
        assertTrue(await(f))
        assertTrue("must have waited past the grace", f.clock >= 4_500)
    }

    @Test
    fun `a listener that takes a few seconds is waited for, not written off`() {
        val f = Fake(armedFrom = 0, listeningFrom = 6_000)
        assertTrue(await(f))
        assertEquals(6_000L, f.clock)
    }

    @Test
    fun `an arm that never took is abandoned after the grace, not at the budget`() {
        // Nothing ever sets the property, so after the grace this is a real failure and waiting out the
        // rest of the budget would only delay the caller's fallback.
        val f = Fake(armedFrom = Long.MAX_VALUE, listeningFrom = Long.MAX_VALUE)
        assertFalse(await(f))
        assertTrue("gave up at the grace, not the budget", f.clock in 5_000..6_500)
    }

    @Test
    fun `once the port is armed the full budget is available, however slow the listener`() {
        val f = Fake(armedFrom = 0, listeningFrom = Long.MAX_VALUE)
        assertFalse(await(f))
        assertTrue("used the whole budget", f.clock >= 12_000)
        assertTrue("but did not run past it", f.clock <= 12_000 + 1_500)
    }

    @Test
    fun `the port going away after the grace ends the wait`() {
        // adbd restarted again underneath us -- e.g. USB debugging was switched off.
        var looks = 0
        val f = Fake(armedFrom = 0, listeningFrom = Long.MAX_VALUE)
        val ok = LoopbackArmWait.awaitListener(
            budgetMs = 12_000,
            graceMs = 0,
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
