/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.integrations.adb.ShizukuNetworkApproval
import com.baba.callvault.server.KeptSwitchOutcome
import com.baba.callvault.server.KeptSwitchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review N15, app side: in Shizuku mode a joined access point is approved through the Shizuku-hosted recorder, the
 * app remembers which ones were, and the app itself never writes Wireless debugging or claims it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShizukuWifiApprovalTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = AppPreferences(context)
    private var recorderAlive = true
    private val asked = mutableListOf<Set<String>>()
    private var answer: () -> String? = { restoredOff.encode() }

    private val restoredOff = KeptSwitchResult(KeptSwitchOutcome.APPROVED_RESTORED_OFF, "k1", "**:**:**:**:ee:ff", 180L)

    private val approval = ShizukuWifiApproval(
        context = context,
        recorderAlive = { recorderAlive },
        askRecorder = { keys -> asked += keys.toSet(); answer() },
    )

    @Before
    fun setUp() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        prefs.setWifiNetworkAutoApproveEnabled(true)
        Settings.Global.putInt(context.contentResolver, WD, 0)
    }

    @Test
    fun `asks the recorder and remembers the approved access point`() {
        val result = approval.approveIfWanted()

        assertEquals(ShizukuNetworkApproval.ASK_RECORDER, result.decision)
        assertEquals(KeptSwitchOutcome.APPROVED_RESTORED_OFF, result.recorder?.outcome)
        assertEquals(setOf("k1"), prefs.getApprovedAccessPointKeys())
    }

    @Test
    fun `passes the access points approved before, so the recorder can skip them`() {
        prefs.addApprovedAccessPointKey("k0")

        approval.approveIfWanted()

        assertEquals(listOf(setOf("k0")), asked)
    }

    @Test
    fun `an access point already approved changes nothing`() {
        prefs.addApprovedAccessPointKey("k1")
        answer = { KeptSwitchResult(KeptSwitchOutcome.ALREADY_APPROVED, "k1", "**:**:**:**:ee:ff").encode() }

        val result = approval.approveIfWanted()

        assertEquals(KeptSwitchOutcome.ALREADY_APPROVED, result.recorder?.outcome)
        assertEquals(setOf("k1"), prefs.getApprovedAccessPointKeys())
    }

    @Test
    fun `never writes the Wireless debugging switch or claims it`() {
        approval.approveIfWanted()

        // The only write is the recorder's own switch-back; the app leaves the setting alone…
        assertEquals(0, Settings.Global.getInt(context.contentResolver, WD, -1))
        // …and records nothing that would let built-in mode later treat the switch as ours, or as the user's off.
        assertFalse(prefs.wasWirelessDebuggingEnabledByUs())
        assertFalse(prefs.wasWirelessDebuggingTurnedOffByUser())
    }

    @Test
    fun `does nothing with the recorder down`() {
        recorderAlive = false

        val result = approval.approveIfWanted()

        assertEquals(ShizukuNetworkApproval.RECORDER_DOWN, result.decision)
        assertNull(result.recorder)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `does nothing when the user has the feature off`() {
        prefs.setWifiNetworkAutoApproveEnabled(false)

        assertEquals(ShizukuNetworkApproval.SETTING_OFF, approval.approveIfWanted().decision)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `stays out of built-in mode, which approves through its own path`() {
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)

        assertEquals(ShizukuNetworkApproval.NOT_SHIZUKU_MODE, approval.approveIfWanted().decision)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a failed switch-back is reported, and the access point still counts as trusted`() {
        answer = { KeptSwitchResult(KeptSwitchOutcome.APPROVED_RESTORE_FAILED, "k2", "**:**:**:**:01:02", 300L).encode() }

        val result = approval.approveIfWanted()

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORE_FAILED, result.recorder?.outcome)
        assertEquals(setOf("k2"), prefs.getApprovedAccessPointKeys())
    }

    @Test
    fun `an unconfirmed approval is not remembered, so the next join tries again`() {
        answer = { KeptSwitchResult(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, "k3", "**:**:**:**:01:03", 3_000L).encode() }

        approval.approveIfWanted()

        assertTrue(prefs.getApprovedAccessPointKeys().isEmpty())
    }

    @Test
    fun `an older recorder, or one that throws, is reported and nothing is remembered`() {
        answer = { null }
        assertNull(approval.approveIfWanted().recorder)

        answer = { throw android.os.DeadObjectException() }
        assertNull(approval.approveIfWanted().recorder)

        assertTrue(prefs.getApprovedAccessPointKeys().isEmpty())
    }

    @Test
    fun `the join watch is wanted only in Shizuku mode with the setting on`() {
        assertTrue(WifiJoinWatch.shouldWatch(PrivilegedMode.SHIZUKU, settingOn = true))
        assertFalse(WifiJoinWatch.shouldWatch(PrivilegedMode.SHIZUKU, settingOn = false))
        assertFalse(WifiJoinWatch.shouldWatch(PrivilegedMode.STANDALONE, settingOn = true))
    }

    private companion object {
        const val WD = "adb_wifi_enabled"
    }
}
