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

/**
 * What the keep-alive should do next when the daemon is down.
 *
 * Written after a field wedge on 2026-08-18: a OnePlus 12 sat with no daemon across an app restart
 * *and* a full reboot, retrying a relaunch that timed out after 45 s on every cycle, silently.
 * Recording was dead throughout and nothing in the UI said so.
 */
class DaemonRecoveryPolicyTest {

    @Test
    fun restores_wireless_debugging_when_nothing_is_running_at_all() {
        val policy = DaemonRecoveryPolicy()

        assertEquals(
            RecoveryStep.RESTORE_WIRELESS_DEBUGGING,
            policy.nextStep(
                wirelessDebuggingEnabled = false,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun usb_debugging_alone_is_not_something_this_app_can_dial() {
        // The gap in the old guard: it skipped restoring Wireless debugging whenever USB debugging was
        // on. USB debugging keeps adbd running but offers no TCP port, and the embedded client speaks
        // TCP — a cable is not an endpoint. With no armed loopback there was nothing to connect to and
        // no way back.
        val policy = DaemonRecoveryPolicy()

        assertEquals(
            RecoveryStep.RESTORE_WIRELESS_DEBUGGING,
            policy.nextStep(
                wirelessDebuggingEnabled = false,
                usbDebuggingEnabled = true,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun an_armed_loopback_port_is_useless_when_adbd_is_not_running() {
        // service.adb.tcp.port says WHERE adbd would listen, not that it exists. Assuming otherwise
        // caused the 1.4.8 regression on the Samsung S24 FE; adbd runs only while USB or Wireless
        // debugging is enabled.
        val policy = DaemonRecoveryPolicy()

        assertEquals(
            RecoveryStep.RESTORE_WIRELESS_DEBUGGING,
            policy.nextStep(
                wirelessDebuggingEnabled = false,
                usbDebuggingEnabled = false,
                loopbackArmed = true,
            )
        )
    }

    @Test
    fun an_armed_loopback_port_is_dialable_when_adbd_is_running() {
        // USB debugging on + loopback armed is a real endpoint. This is the state the 2026-08-18 device
        // was actually in, which is why restoring an endpoint could not have rescued it.
        val policy = DaemonRecoveryPolicy()

        assertEquals(
            RecoveryStep.CONNECT,
            policy.nextStep(
                wirelessDebuggingEnabled = false,
                usbDebuggingEnabled = true,
                loopbackArmed = true,
            )
        )
    }

    @Test
    fun just_connects_when_wireless_debugging_is_already_on() {
        val policy = DaemonRecoveryPolicy()

        assertEquals(
            RecoveryStep.CONNECT,
            policy.nextStep(
                wirelessDebuggingEnabled = true,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun escalates_to_a_rebuild_after_repeated_failures() {
        // THE 2026-08-18 WEDGE. The endpoint was genuinely present, so the old code kept choosing to
        // connect and kept hitting the same 45 s timeout. Retrying an identical attempt forever is not
        // a recovery strategy, and this is the only step that would have rescued that device.
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)

        policy.onAttemptFailed()
        policy.onAttemptFailed()

        assertEquals(
            RecoveryStep.REBUILD_CONNECTION,
            policy.nextStep(
                wirelessDebuggingEnabled = true,
                usbDebuggingEnabled = true,
                loopbackArmed = true,
            )
        )
    }

    @Test
    fun does_not_escalate_before_the_threshold() {
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)

        policy.onAttemptFailed()

        assertEquals(
            RecoveryStep.CONNECT,
            policy.nextStep(
                wirelessDebuggingEnabled = true,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun a_success_clears_the_failure_streak() {
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)
        policy.onAttemptFailed()
        policy.onAttemptFailed()

        policy.onAttemptSucceeded()

        assertEquals(
            RecoveryStep.CONNECT,
            policy.nextStep(
                wirelessDebuggingEnabled = true,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun keeps_escalating_until_something_actually_works() {
        // Escalating once and then dropping back to the same failing connect would simply re-wedge, so
        // the escalation stands until an attempt succeeds.
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)
        repeat(3) { policy.onAttemptFailed() }

        assertEquals(
            RecoveryStep.REBUILD_CONNECTION,
            policy.nextStep(
                wirelessDebuggingEnabled = true,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun a_missing_endpoint_still_wins_over_escalation() {
        // Rebuilding a connection to nothing is pointless; restore an endpoint first.
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 1)
        policy.onAttemptFailed()

        assertEquals(
            RecoveryStep.RESTORE_WIRELESS_DEBUGGING,
            policy.nextStep(
                wirelessDebuggingEnabled = false,
                usbDebuggingEnabled = false,
                loopbackArmed = false,
            )
        )
    }

    @Test
    fun reports_when_recovery_has_been_failing_long_enough_to_tell_the_user() {
        // The outage was invisible: nothing in the UI said recording was dead. This is what Home reads.
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)

        assertFalse(policy.isStuck)

        policy.onAttemptFailed()
        policy.onAttemptFailed()
        assertTrue(policy.isStuck)

        policy.onAttemptSucceeded()
        assertFalse(policy.isStuck)
    }

    @Test
    fun `the stuck state is published so Home can follow it live`() {
        // After a re-pair the recorder came back 4 s after Home last looked, and the card stayed on
        // "Recording is down" until the screen was reopened (OP9, 2026-09-29).
        val policy = DaemonRecoveryPolicy(escalateAfterFailures = 2)
        assertFalse(policy.stuckState.value)
        policy.onAttemptFailed()
        assertFalse(policy.stuckState.value)
        policy.onAttemptFailed()
        assertTrue(policy.stuckState.value)
        policy.onAttemptSucceeded()
        assertFalse(policy.stuckState.value)
    }
}
