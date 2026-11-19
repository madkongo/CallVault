/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N15: the recorder approves a joined access point (always-allow) and leaves Wireless debugging as it
 * found it. The only write it makes is switching off what its own approval switched on.
 */
class KeptSwitchApproverTest {

    private val wifi = WifiIdentity(ssid = "\"Home\"", bssid = "aa:bb:cc:dd:ee:ff")
    private var now = 1_000L
    private var wd: Int? = 0
    private val approvals = mutableListOf<Boolean>()
    private var approvalAnswer = NetworkApproval.APPROVED
    /** What Android does after a successful approval from off: switch Wireless debugging on, a moment later. */
    private var androidTurnsOnAfterReads = 2
    private var offWrites = 0
    private var offWriteSucceeds = true
    private var identity: WifiIdentity? = wifi
    private var tcpMode = true
    private var tcpChecks = 0
    /** Reads answered before any approval, in order, overriding [wd] (to put a user's tap between two reads). */
    private val readsBeforeApproval = ArrayDeque<Int?>()
    private val salt = "0123456789abcdef"

    private fun approver() = KeptSwitchApprover(
        identity = { identity },
        readWirelessDebugging = {
            if (approvals.isEmpty() && readsBeforeApproval.isNotEmpty()) {
                readsBeforeApproval.removeFirst().also { wd = it }
            } else {
                if (approvals.isNotEmpty() && wd == 0 && --androidTurnsOnAfterReads <= 0) wd = 1
                wd
            }
        },
        adbdListensOnTcp = { tcpChecks++; tcpMode },
        approve = { _, always -> approvals += always; approvalAnswer },
        writeWirelessDebuggingOff = { offWrites++; if (offWriteSucceeds) wd = 0; offWriteSucceeds },
        clock = { now },
        sleep = { now += it },
        confirmTimeoutMs = 3_000L,
    )

    @Test
    fun `off before - approves always-allow, waits for Android to switch it on, then switches it back off`() {
        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORED_OFF, result.outcome)
        assertEquals(listOf(true), approvals)
        assertEquals(1, offWrites)
        assertEquals(0, wd)
        assertTrue(result.restoreMs in 0L..3_000L)
        assertTrue(result.outcome.trusted)
    }

    @Test
    fun `on before - nothing is called and nothing is written`() {
        wd = 1

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.WIRELESS_DEBUGGING_ON, result.outcome)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
        assertEquals(1, wd)
    }

    @Test
    fun `an access point approved before is left alone`() {
        val result = approver().run(setOf(BssidPrivacy.key("AA:BB:CC:DD:EE:FF", salt)), salt)

        assertEquals(KeptSwitchOutcome.ALREADY_APPROVED, result.outcome)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
    }

    @Test
    fun `an unreadable switch is not guessed at`() {
        wd = null

        assertEquals(KeptSwitchOutcome.WIRELESS_DEBUGGING_UNREADABLE, approver().run(emptySet(), salt).outcome)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
    }

    @Test
    fun `no Wi-Fi identity - nothing done`() {
        identity = null

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.NO_WIFI_IDENTITY, result.outcome)
        assertTrue(approvals.isEmpty())
    }

    @Test
    fun `a failed approval writes nothing`() {
        approvalAnswer = NetworkApproval.UNSUPPORTED

        assertEquals(KeptSwitchOutcome.APPROVAL_FAILED, approver().run(emptySet(), salt).outcome)
        assertEquals(0, offWrites)
    }

    @Test
    fun `never seen on - nothing is written, so a hidden 'on' cannot be switched off`() {
        androidTurnsOnAfterReads = Int.MAX_VALUE

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, result.outcome)
        assertEquals(0, offWrites)
        assertTrue(result.restoreMs >= 3_000L)
        assertFalse(result.outcome.trusted)
    }

    @Test
    fun `a failed switch-back is reported, with Wireless debugging left on`() {
        offWriteSucceeds = false

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORE_FAILED, result.outcome)
        assertEquals(1, wd)
    }

    @Test
    fun `the result crosses the binder intact, and only a masked address is in it`() {
        val result = approver().run(emptySet(), salt)

        val decoded = KeptSwitchResult.decode(result.encode())

        assertEquals(result, decoded)
        assertEquals("**:**:**:**:ee:ff", decoded!!.maskedBssid)
        assertFalse(result.encode().contains("aa:bb"))
        assertNull(KeptSwitchResult.decode(null))
        assertNull(KeptSwitchResult.decode("APPROVED"))
    }

    @Test
    fun `not in tcp mode - nothing is approved and nothing is written`() {
        // Q01: where Wireless debugging is adbd's only transport, switching it off can stop adbd, and with it
        // Shizuku and the recorder. Only tcp mode was measured safe (OP9, 2026-11-19).
        tcpMode = false

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.NOT_TCP_MODE, result.outcome)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
    }

    @Test
    fun `switched on between the first read and the approval - left on, nothing approved or written`() {
        readsBeforeApproval.addAll(listOf(0, 1))

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.WIRELESS_DEBUGGING_TURNED_ON_MEANWHILE, result.outcome)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
        assertEquals(1, wd)
    }

    @Test
    fun `keys depend on the per-install salt, so they cannot be looked up from a vendor prefix`() {
        val bssid = "aa:bb:cc:dd:ee:ff"
        assertEquals(BssidPrivacy.key(bssid, salt), BssidPrivacy.key(bssid.uppercase(), salt))
        assertFalse(BssidPrivacy.key(bssid, salt) == BssidPrivacy.key(bssid, "another-install"))
    }

    @Test
    fun `settings output parses to on, off, or unreadable`() {
        assertEquals(1, WirelessDebuggingApproval.parseSettingValue("1\n"))
        assertEquals(0, WirelessDebuggingApproval.parseSettingValue("0"))
        assertEquals(0, WirelessDebuggingApproval.parseSettingValue("null"))
        assertNull(WirelessDebuggingApproval.parseSettingValue(null))
        assertNull(WirelessDebuggingApproval.parseSettingValue("Exception: permission denied"))
    }

    @Test
    fun `tcp mode is a positive port in either property`() {
        assertTrue(WirelessDebuggingApproval.isTcpPort("5555\n", ""))
        assertTrue(WirelessDebuggingApproval.isTcpPort("", "5555"))
        assertFalse(WirelessDebuggingApproval.isTcpPort("", ""))
        assertFalse(WirelessDebuggingApproval.isTcpPort("0", "-1"))
        assertFalse(WirelessDebuggingApproval.isTcpPort(null, "abc"))
    }
}
