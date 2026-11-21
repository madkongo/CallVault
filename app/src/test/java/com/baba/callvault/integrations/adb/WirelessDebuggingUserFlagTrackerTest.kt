/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "switched off by the user" flag across an on that is quickly reverted: the on clears it, so a revert verdict
 * must put back what the user already had, or CallVault would switch the user's Wireless debugging back on.
 * Drives the same sequence the keep-alive observer runs, against a fake flag.
 */
class WirelessDebuggingUserFlagTrackerTest {

    private class Sim(var flag: Boolean) {
        val tracker = WirelessDebuggingUserFlagTracker()
        var lastOnAt: Long? = null
        var lastSeenOn: Boolean? = null

        fun on(at: Long) {
            tracker.onSwitchedOn(flagBefore = flag, nowMs = at)
            flag = false // what the observer's on branch does
            lastOnAt = at
            lastSeenOn = true
        }

        fun off(at: Long, ours: Boolean = false) {
            val cause = WirelessDebuggingOffCause.of(
                weJustTurnedItOff = false, weJustTurnedItOn = ours, wifi = WifiState.CONNECTED,
                msSinceLastOn = lastOnAt?.let { at - it }, lastSeenOn = lastSeenOn,
            )
            flag = tracker.flagAfterOff(cause, flagNow = flag, nowMs = at)
            lastSeenOn = false
        }
    }

    @Test
    fun `flag true, on then off within 3 s, puts the flag back`() {
        val s = Sim(flag = true)
        s.on(10_000); s.off(11_500)
        assertEquals(true, s.flag)
    }

    @Test
    fun `flag false, our own enable then a system revert, stays false`() {
        val s = Sim(flag = false)
        s.on(10_000); s.off(11_400, ours = true)
        assertEquals(false, s.flag)
    }

    @Test
    fun `flag false, a foreign on then a revert, stays false`() {
        val s = Sim(flag = false)
        s.on(10_000); s.off(11_400)
        assertEquals(false, s.flag)
    }

    @Test
    fun `an off, on, off burst leaves the flag true`() {
        val s = Sim(flag = false)
        s.off(1_000) // user off, nothing seen before: USER
        assertEquals(true, s.flag)
        s.on(20_000); s.off(20_900)
        assertEquals(true, s.flag)
    }

    @Test
    fun `two ons inside the window keep the first flag`() {
        val s = Sim(flag = true)
        s.on(10_000); s.on(10_500); s.off(11_000)
        assertEquals(true, s.flag)
    }

    @Test
    fun `user off more than 3 s after the on is the user as before`() {
        val s = Sim(flag = false)
        s.on(10_000); s.off(13_500)
        assertEquals(true, s.flag)
    }

    @Test
    fun `no restore once the window has passed`() {
        val tracker = WirelessDebuggingUserFlagTracker()
        tracker.onSwitchedOn(flagBefore = true, nowMs = 0)
        assertEquals(false, tracker.flagAfterOff(WirelessDebuggingOffCause.OFF_WHILE_ALREADY_OFF, flagNow = false, nowMs = 10_000))
    }

    @Test
    fun `other causes leave the flag alone`() {
        val tracker = WirelessDebuggingUserFlagTracker()
        tracker.onSwitchedOn(flagBefore = true, nowMs = 0)
        assertEquals(false, tracker.flagAfterOff(WirelessDebuggingOffCause.ANDROID_NO_WIFI, flagNow = false, nowMs = 100))
    }
}
