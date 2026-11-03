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
import org.junit.Test

/**
 * The "Never turn Wireless debugging off" setting (#30 follow-up, asked by mirror176).
 *
 * With it on, `AdbShell.releaseWirelessDebugging` never switches Wireless debugging off. With it off, the
 * answer is exactly what it was before the setting existed.
 */
class WirelessDebuggingNeverTurnOffTest {

    private val plans = WirelessDebuggingPlan.entries
    private val owners = listOf(true, false)

    @Test
    fun `with the setting on, nothing is ever switched off`() {
        plans.forEach { plan ->
            owners.forEach { ours ->
                val decision = WirelessDebuggingPolicy.releaseDecision(plan, weEnabledIt = ours, neverTurnOff = true)
                assertEquals("$plan ours=$ours", WirelessDebuggingRelease.LEAVE_SETTING_ON, decision)
            }
        }
    }

    @Test
    fun `with the setting off, our own droppable switch is switched off as before`() {
        assertEquals(
            WirelessDebuggingRelease.SWITCH_OFF,
            WirelessDebuggingPolicy.releaseDecision(WirelessDebuggingPlan.DROP_USB_KEEPS_ADBD, weEnabledIt = true, neverTurnOff = false),
        )
    }

    @Test
    fun `with the setting off, the user's switch is still left alone`() {
        plans.forEach { plan ->
            assertEquals(
                WirelessDebuggingRelease.LEAVE_USERS_SWITCH,
                WirelessDebuggingPolicy.releaseDecision(plan, weEnabledIt = false, neverTurnOff = false),
            )
        }
    }

    @Test
    fun `with the setting off, the only transport still stays on`() {
        assertEquals(
            WirelessDebuggingRelease.KEEP_ONLY_TRANSPORT,
            WirelessDebuggingPolicy.releaseDecision(WirelessDebuggingPlan.KEEP_ONLY_TRANSPORT, weEnabledIt = true, neverTurnOff = false),
        )
    }

    @Test
    fun `with the setting off, the decision matches mayRelease in every case`() {
        plans.forEach { plan ->
            owners.forEach { ours ->
                val switchesOff = WirelessDebuggingPolicy.releaseDecision(plan, ours, neverTurnOff = false) ==
                    WirelessDebuggingRelease.SWITCH_OFF
                assertEquals("$plan ours=$ours", WirelessDebuggingPolicy.mayRelease(plan, ours), switchesOff)
            }
        }
    }

    @Test
    fun `the settings-screen sign shows only when something is being left on`() {
        assertFalse(WirelessDebuggingRelease.SWITCH_OFF.leavesItOn)
        assertFalse(WirelessDebuggingRelease.KEEP_ONLY_TRANSPORT.leavesItOn)
        assertEquals(true, WirelessDebuggingRelease.LEAVE_SETTING_ON.leavesItOn)
        assertEquals(true, WirelessDebuggingRelease.LEAVE_USERS_SWITCH.leavesItOn)
    }
}
