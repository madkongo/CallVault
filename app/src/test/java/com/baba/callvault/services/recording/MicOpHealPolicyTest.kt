/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.MicOpHealPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a stuck microphone indicator is healed by replacing the recorder daemon.
 *
 * A capture AudioFlinger invalidates mid-call leaves the shell uid's RECORD_AUDIO op running after the call,
 * and no stop or release on that track finishes it (rc14, tester's OnePlus 13, 2026-09-11). Replacing the daemon
 * process always does. Replacing it at the wrong moment costs a recording, so every rule here is about not doing
 * that.
 */
class MicOpHealPolicyTest {

    private val minute = 60_000L

    private fun decide(
        shellMicOpsHeld: Int = 1,
        callOrRecordingActive: Boolean = false,
        canRelaunch: Boolean = true,
        lastHealAtMs: Long? = null,
        nowMs: Long = 100 * minute,
    ) = MicOpHealPolicy.decide(shellMicOpsHeld, callOrRecordingActive, canRelaunch, lastHealAtMs, nowMs)

    @Test
    fun a_stuck_op_after_the_call_is_healed() {
        assertEquals(Decision.HEAL, decide())
    }

    @Test
    fun nothing_is_done_when_no_shell_mic_op_is_running() {
        assertEquals(Decision.NOTHING_HELD, decide(shellMicOpsHeld = 0))
    }

    /** A running op during a call is the recording itself, not a leak — and killing the daemon would end it. */
    @Test
    fun never_while_a_call_or_recording_is_active() {
        assertEquals(Decision.CALL_ACTIVE, decide(callOrRecordingActive = true))
    }

    /** Shizuku mode runs no daemon of ours to replace, and its host is not ours to kill. */
    @Test
    fun shizuku_mode_relaunches_whenever_shizuku_runs() {
        // Shizuku mode is no longer left out: its recorder is CallVault's own service (2026-11-07).
        assertEquals(true, MicOpHealPolicy.canRelaunchShizuku(shizukuReady = true))
        assertEquals(false, MicOpHealPolicy.canRelaunchShizuku(shizukuReady = false))
        assertEquals(Decision.NO_WAY_BACK, decide(canRelaunch = MicOpHealPolicy.canRelaunchShizuku(shizukuReady = false)))
    }

    /**
     * A dot is alarming but costs no audio; a daemon that cannot come back costs the next call. On a phone with no
     * endpoint to relaunch through — the tester's has WRITE_SECURE_SETTINGS off — the dot stays.
     */
    @Test
    fun not_when_the_daemon_could_not_be_brought_back() {
        assertEquals(Decision.NO_WAY_BACK, decide(canRelaunch = false))
    }

    /**
     * If the op survives a replacement it belongs to something else running as shell, and replacing the daemon
     * again would only churn it after every call.
     */
    @Test
    fun not_again_within_the_cooldown() {
        val now = 100 * minute
        assertEquals(Decision.COOLING_DOWN, decide(lastHealAtMs = now - 2 * minute, nowMs = now))
        assertEquals(Decision.HEAL, decide(lastHealAtMs = now - MicOpHealPolicy.COOLDOWN_MS - 1, nowMs = now))
    }

    /** Mirrors [DaemonRecoveryPolicy]'s idea of an endpoint, plus the grant that lets the app switch WD back on. */
    @Test
    fun a_relaunch_needs_an_endpoint_and_a_network_the_launcher_will_use() {
        fun can(wd: Boolean = false, usb: Boolean = false, loopback: Boolean = false, wss: Boolean = false, offline: Boolean = false, wifi: Boolean = true) =
            MicOpHealPolicy.canRelaunch(
                wdEnabled = wd, usbDebuggingEnabled = usb, loopbackArmed = loopback,
                hasWriteSecureSettings = wss, offlineEnabled = offline, wifiConnected = wifi,
            )

        assertTrue("Wireless debugging is an endpoint", can(wd = true))
        assertTrue("an armed loopback with adbd up is an endpoint", can(usb = true, loopback = true, offline = true, wifi = false))
        assertTrue("the grant can switch Wireless debugging back on", can(wss = true, wifi = true))

        assertFalse("an armed loopback with adbd down is not dialable", can(loopback = true, offline = true))
        assertFalse("nothing to dial and no grant: the tester's phone unless loopback is armed", can(usb = true, offline = true))
        assertFalse("the grant is no use without Wi-Fi for Wireless debugging", can(usb = true, wss = true, offline = true, wifi = false))
        assertFalse("off Wi-Fi the keep-alive only relaunches with offline recording on", can(wd = true, usb = true, loopback = true, wss = true, offline = false, wifi = false))
    }
}
