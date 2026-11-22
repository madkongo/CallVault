/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What CallVault may do when adbd is not running.
 *
 * Measured 2026-09-14 (docs/dev-notes/2026-09-14-debugging-switches-model.md): turning USB debugging off
 * stops adbd even with Wireless debugging on (stock init.usb.configfs.rc), and nothing restarts it. On
 * the OP9, switching Wireless debugging off and on brought it back and left USB debugging off.
 */
class AdbdRevivalPolicyTest {

    private fun decide(
        adbd: AdbdState = AdbdState.STOPPED,
        usbOn: Boolean = false,
        wdOn: Boolean = false,
        wifi: WifiState = WifiState.CONNECTED,
        hasGrant: Boolean = true,
        mayEnable: Boolean = true,
        shizukuServerRunning: Boolean = false,
        stoppedStreak: AdbdStoppedStreak = AdbdStoppedStreak(checks = 1, stoppedForMs = 0),
    ) = AdbdRevivalPolicy.decide(adbd, usbOn, wdOn, wifi, hasGrant, mayEnable, shizukuServerRunning, stoppedStreak)

    @Test
    fun `a running adbd is left alone`() {
        assertEquals(AdbdRevival.NOTHING, decide(adbd = AdbdState.RUNNING, wdOn = true))
    }

    @Test
    fun `an unreadable state is not evidence that adbd is down`() {
        assertEquals(AdbdRevival.NOTHING, decide(adbd = AdbdState.UNKNOWN, wdOn = true))
    }

    @Test
    fun `with usb debugging on, adbd is init's to start`() {
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true))
    }

    @Test
    fun `wireless debugging on but adbd stopped is the usb-off case, and cycling it is the cure`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true))
    }

    @Test
    fun `both off on wifi, switch wireless debugging on`() {
        assertEquals(AdbdRevival.ENABLE_WIRELESS_DEBUGGING, decide())
    }

    @Test
    fun `no wifi means wireless debugging cannot run, so nothing is written`() {
        assertEquals(AdbdRevival.NEEDS_WIFI, decide(wdOn = true, wifi = WifiState.NOT_CONNECTED))
        assertEquals(AdbdRevival.NEEDS_WIFI, decide(wifi = WifiState.NOT_CONNECTED))
    }

    @Test
    fun `an unknown wifi state still tries`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, wifi = WifiState.UNKNOWN))
    }

    @Test
    fun `without the grant the switches cannot be written`() {
        assertEquals(AdbdRevival.NO_GRANT, decide(wdOn = true, hasGrant = false))
    }

    @Test
    fun `when switching it on is not allowed, both-off is left alone`() {
        // Shizuku mode, or a switch the user turned off: only a cycle that ends where it started is ok.
        assertEquals(AdbdRevival.NOTHING, decide(mayEnable = false))
    }

    @Test
    fun `a cycle is still allowed, because it ends with the switch as it was`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, mayEnable = false))
    }

    // --- A live Shizuku server contradicts a stopped reading, and it is the reliable half (#39) ---

    @Test
    fun `leaves the switches alone while a Shizuku server still answers`() {
        // The hazard: cycling here restarts a live adbd and kills the Shizuku server it is hosting —
        // the reporter's "it disables automatically within a second".
        assertEquals(AdbdRevival.NOTHING, decide(wdOn = true, shizukuServerRunning = true))
    }

    @Test
    fun `a live Shizuku server also blocks switching Wireless debugging on from off`() {
        assertEquals(AdbdRevival.NOTHING, decide(wdOn = false, shizukuServerRunning = true))
    }

    @Test
    fun `without a Shizuku server the stopped reading is acted on as before`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(wdOn = true, shizukuServerRunning = false))
        assertEquals(AdbdRevival.ENABLE_WIRELESS_DEBUGGING, decide(wdOn = false, shizukuServerRunning = false))
    }

    @Test
    fun `USB debugging still wins over the Shizuku check`() {
        // Nothing to do either way; the order of the two NOTHING branches must not change the answer.
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, shizukuServerRunning = true))
    }

    // --- GH89-1: USB debugging reads on, but adbd stays stopped (Honor BVL-N49, Android 17, ~48 h) ---

    private val firstSighting = AdbdStoppedStreak(checks = 1, stoppedForMs = 0)
    private val stuck = AdbdStoppedStreak(checks = 2, stoppedForMs = 60_000)

    @Test
    fun `usb debugging on and the first stopped sighting still gives init its chance`() {
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = true, stoppedStreak = firstSighting))
    }

    @Test
    fun `usb debugging on and adbd stopped on two checks cycles wireless debugging`() {
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(usbOn = true, wdOn = true, stoppedStreak = stuck))
    }

    @Test
    fun `usb debugging on needs both two checks and the long stop before it cycles`() {
        val longOneCheck = AdbdStoppedStreak(checks = 1, stoppedForMs = AdbdRevivalPolicy.STUCK_AFTER_MS * 10)
        val twoChecksShort = AdbdStoppedStreak(checks = 2, stoppedForMs = AdbdRevivalPolicy.STUCK_AFTER_MS - 1)
        val both = AdbdStoppedStreak(checks = 2, stoppedForMs = AdbdRevivalPolicy.STUCK_AFTER_MS)
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = true, stoppedStreak = longOneCheck))
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = true, stoppedStreak = twoChecksShort))
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(usbOn = true, wdOn = true, stoppedStreak = both))
    }

    @Test
    fun `a stuck stop never cycles a switch that is not ours to cycle, as in Shizuku mode`() {
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = true, mayEnable = false, stoppedStreak = stuck))
        assertEquals(
            AdbdRevivalReason.USB_ON_STUCK_NOT_OURS,
            AdbdRevivalPolicy.decideWithReason(AdbdState.STOPPED, true, true, WifiState.CONNECTED, true, false, false, stuck).reason,
        )
    }

    @Test
    fun `a stuck stop with usb on never switches wireless debugging on from off`() {
        // WD reads off: the user may have turned it off. Only a cycle of a switch that reads on is allowed.
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = false, stoppedStreak = stuck))
    }

    @Test
    fun `a stuck stop with usb on still needs wifi, the grant, and no live Shizuku server`() {
        assertEquals(AdbdRevival.NEEDS_WIFI, decide(usbOn = true, wdOn = true, wifi = WifiState.NOT_CONNECTED, stoppedStreak = stuck))
        assertEquals(AdbdRevival.NO_GRANT, decide(usbOn = true, wdOn = true, hasGrant = false, stoppedStreak = stuck))
        assertEquals(AdbdRevival.NOTHING, decide(usbOn = true, wdOn = true, shizukuServerRunning = true, stoppedStreak = stuck))
    }

    @Test
    fun `a running adbd is left alone whatever the streak says`() {
        assertEquals(AdbdRevival.NOTHING, decide(adbd = AdbdState.RUNNING, usbOn = true, wdOn = true, stoppedStreak = stuck))
    }

    // --- GH89-2: every NOTHING names its real branch ---

    private fun reason(
        adbd: AdbdState = AdbdState.STOPPED,
        usbOn: Boolean = false,
        wdOn: Boolean = false,
        shizukuServerRunning: Boolean = false,
        mayEnable: Boolean = true,
        streak: AdbdStoppedStreak = firstSighting,
    ) = AdbdRevivalPolicy.decideWithReason(adbd, usbOn, wdOn, WifiState.CONNECTED, true, mayEnable, shizukuServerRunning, streak).reason

    @Test
    fun `each branch reports its own reason`() {
        assertEquals(AdbdRevivalReason.ADBD_NOT_STOPPED, reason(adbd = AdbdState.RUNNING))
        assertEquals(AdbdRevivalReason.SHIZUKU_SERVER_ANSWERS, reason(shizukuServerRunning = true))
        assertEquals(AdbdRevivalReason.USB_ON_WAITING_FOR_INIT, reason(usbOn = true, wdOn = true))
        assertEquals(AdbdRevivalReason.USB_ON_STUCK_CYCLE, reason(usbOn = true, wdOn = true, streak = stuck))
        assertEquals(AdbdRevivalReason.MAY_NOT_ENABLE, reason(mayEnable = false))
        assertEquals(AdbdRevivalReason.WD_ON_CYCLE, reason(wdOn = true))
        assertEquals(AdbdRevivalReason.BOTH_OFF_ENABLE, reason())
    }

    @Test
    fun `a stuck stop with usb on and wireless debugging off reports that it may not enable`() {
        assertEquals(AdbdRevivalReason.USB_ON_STUCK_WD_OFF, reason(usbOn = true, wdOn = false, streak = stuck))
    }

    // --- the streak counter ---

    @Test
    fun `the tracker counts spaced stopped sightings and resets on any other reading`() {
        val t = AdbdStoppedTracker()
        assertEquals(AdbdStoppedStreak(1, 0), t.observe(AdbdState.STOPPED, 1_000))
        // a second look milliseconds later (the observer and the keep-alive arrive together) is the same sighting
        assertEquals(AdbdStoppedStreak(1, 100), t.observe(AdbdState.STOPPED, 1_100))
        assertEquals(AdbdStoppedStreak(2, 9_000), t.observe(AdbdState.STOPPED, 10_000))
        t.observe(AdbdState.RUNNING, 11_000)
        assertEquals(AdbdStoppedStreak(1, 0), t.observe(AdbdState.STOPPED, 12_000))
    }

    @Test
    fun `an unknown reading also ends the streak`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0)
        t.observe(AdbdState.STOPPED, 10_000)
        assertEquals(AdbdStoppedStreak(0, 0), t.observe(AdbdState.UNKNOWN, 20_000))
    }

    @Test
    fun `an old sighting does not make a brief stop days later look stuck (H1)`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0)
        t.observe(AdbdState.STOPPED, 10_000)
        val days = 3L * 24 * 3_600_000
        // the phone was healthy in between but nobody looked: the old streak must not carry over
        assertEquals(AdbdStoppedStreak(1, 0), t.observe(AdbdState.STOPPED, days))
    }

    @Test
    fun `sightings further apart than the stale gap restart the streak, closer ones keep it`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0)
        assertEquals(2, t.observe(AdbdState.STOPPED, AdbdStoppedTracker.STALE_GAP_MS).checks)
        assertEquals(AdbdStoppedStreak(1, 0), t.observe(AdbdState.STOPPED, AdbdStoppedTracker.STALE_GAP_MS * 2 + 1))
    }

    @Test
    fun `a keep-alive tick every minute keeps a real streak alive long enough to reach the stuck rule`() {
        val t = AdbdStoppedTracker()
        var streak = AdbdStoppedStreak(0, 0)
        for (tick in 0..1) streak = t.observe(AdbdState.STOPPED, tick * 60_000L)
        assertEquals(AdbdRevival.CYCLE_WIRELESS_DEBUGGING, decide(usbOn = true, wdOn = true, stoppedStreak = streak))
    }

    // --- R1: a stale streak must not make a live wait give up at once ---

    @Test
    fun `the continuous stop clock restarts when looks are far apart, though the streak survives`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0)
        // a keep-alive tick 60 s later, then the self-heal loop polls at 61 s
        t.observe(AdbdState.STOPPED, 60_000)
        assertEquals(0L, t.continuousStoppedMs)
        t.observe(AdbdState.STOPPED, 61_500)
        assertEquals(1_500L, t.continuousStoppedMs)
        // the streak itself (for the cycle decision) is still alive
        assertEquals(3, t.observe(AdbdState.STOPPED, 66_000).checks)
    }

    @Test
    fun `a wait loop polling every 1500 ms reaches the give-up only after its own continuous stop`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0) // stale sighting from an earlier tick
        var now = 90_000L
        var gaveUpAt = -1L
        while (gaveUpAt < 0 && now < 120_000) {
            t.observe(AdbdState.STOPPED, now)
            if (StoppedAdbdWaits.persistentlyStopped(AdbdState.STOPPED, t.continuousStoppedMs)) gaveUpAt = now
            now += 1_500
        }
        // first poll at 90 s must not give up; it takes GIVE_UP_AFTER_MS of this loop's own looks
        assertTrue(gaveUpAt >= 90_000 + StoppedAdbdWaits.GIVE_UP_AFTER_MS)
    }

    @Test
    fun `a running reading clears the continuous clock`() {
        val t = AdbdStoppedTracker()
        t.observe(AdbdState.STOPPED, 0)
        t.observe(AdbdState.STOPPED, 3_000)
        t.observe(AdbdState.RUNNING, 4_000)
        assertEquals(0L, t.continuousStoppedMs)
    }
}
