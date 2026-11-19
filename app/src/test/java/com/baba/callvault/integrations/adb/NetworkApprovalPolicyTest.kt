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
 * When CallVault asks its recorder to approve the current Wi-Fi, instead of switching Wireless debugging on
 * and letting Android show "Allow wireless debugging on this network?".
 */
class NetworkApprovalPolicyTest {

    private fun mayApprove(
        settingOn: Boolean = true,
        builtInMode: Boolean = true,
        recorderAlive: Boolean = true,
        gate: WirelessDebuggingEnable = WirelessDebuggingEnable.WRITE,
    ) = NetworkApprovalPolicy.mayApprove(settingOn, builtInMode, recorderAlive, gate)

    @Test
    fun `approves when the setting is on, in built-in mode, with the recorder alive and the gate saying write`() {
        assertTrue(mayApprove())
    }

    @Test
    fun `never without the opt-in, which is off by default`() {
        assertFalse(mayApprove(settingOn = false))
    }

    @Test
    fun `never in Shizuku mode, which does not manage the debugging switches`() {
        assertFalse(mayApprove(builtInMode = false))
    }

    @Test
    fun `never without a live recorder, which is the only shell-uid process that can make the call`() {
        assertFalse(mayApprove(recorderAlive = false))
    }

    @Test
    fun `only where CallVault would switch Wireless debugging on anyway`() {
        // The user's own "off" must hold: approving switches Wireless debugging on.
        assertFalse(mayApprove(gate = WirelessDebuggingEnable.RESPECT_USER))
        assertFalse(mayApprove(gate = WirelessDebuggingEnable.ALREADY_ON))
        assertFalse(mayApprove(gate = WirelessDebuggingEnable.NO_WIFI))
        assertFalse(mayApprove(gate = WirelessDebuggingEnable.NO_GRANT))
    }

    @Test
    fun `approves this visit only, unless CallVault switches Wireless debugging off between uses`() {
        // Left on (the default), an approval for this visit is enough: a later restart of the recorder finds
        // it on. Switched off between uses, a once-only approval would be gone by the next restart, and the
        // prompt would come back; only adding the network to Android's list helps there.
        assertFalse(NetworkApprovalPolicy.alwaysAllow(disableWhenIdle = false))
        assertTrue(NetworkApprovalPolicy.alwaysAllow(disableWhenIdle = true))
    }

    @Test
    fun `reads the recorder's answer by name, and anything unknown is a failure`() {
        assertTrue(NetworkApprovalPolicy.approved("APPROVED"))
        assertFalse(NetworkApprovalPolicy.approved("NO_WIFI_IDENTITY"))
        assertFalse(NetworkApprovalPolicy.approved(null))
        assertEquals(false, NetworkApprovalPolicy.approved("approved"))
    }

    // ---- Shizuku mode (review N15) ----

    private fun inShizukuMode(settingOn: Boolean = true, shizukuMode: Boolean = true, recorderAlive: Boolean = true) =
        NetworkApprovalPolicy.decideInShizukuMode(settingOn, shizukuMode, recorderAlive)

    @Test
    fun `Shizuku mode asks the recorder when the setting is on and the recorder is alive`() {
        assertEquals(ShizukuNetworkApproval.ASK_RECORDER, inShizukuMode())
    }

    @Test
    fun `Shizuku mode never asks without the opt-in`() {
        assertEquals(ShizukuNetworkApproval.SETTING_OFF, inShizukuMode(settingOn = false))
    }

    @Test
    fun `the Shizuku path is for Shizuku mode only - built-in mode keeps its own`() {
        assertEquals(ShizukuNetworkApproval.NOT_SHIZUKU_MODE, inShizukuMode(shizukuMode = false))
    }

    @Test
    fun `Shizuku mode does nothing without a live recorder, and says so`() {
        assertEquals(ShizukuNetworkApproval.RECORDER_DOWN, inShizukuMode(recorderAlive = false))
    }
}
