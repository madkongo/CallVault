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
    /** Android's own trusted list as `dumpsys adb` shows it; null = could not be read. */
    private var trustedByAndroid: Set<String>? = null
    /** Survives approver instances, as the recorder process's own record does. */
    private val leftovers = ApprovalLeftovers()
    /** Reads after the approval that throw before answering (an error in the middle of the switch-back). */
    private var throwingReadsAfterApproval = 0

    private fun approver() = KeptSwitchApprover(
        identity = { identity },
        trustedBssids = { trustedByAndroid },
        readWirelessDebugging = {
            if (approvals.isEmpty() && readsBeforeApproval.isNotEmpty()) {
                readsBeforeApproval.removeFirst().also { wd = it }
            } else {
                if (approvals.isNotEmpty() && throwingReadsAfterApproval > 0) {
                    throwingReadsAfterApproval--
                    throw IllegalStateException("settings died")
                }
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
        lateWindowMs = 5_000L,
        leftovers = leftovers,
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
    fun `an access point Android already trusts is not approved again`() {
        // OP9 2026-11-19: 40:18 was trusted by the user's own tap, the app had no key for it, and the recorder
        // approved it again, so Android's list then held it twice.
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID, result.outcome)
        assertTrue(result.outcome.trusted)
        assertTrue(approvals.isEmpty())
        assertEquals(0, offWrites)
        assertEquals(0, tcpChecks)
    }

    @Test
    fun `Android's list is the truth when it can be read - an app key for an access point it no longer holds is approved again`() {
        trustedByAndroid = setOf("11:22:33:44:55:66")

        val result = approver().run(setOf(BssidPrivacy.key("aa:bb:cc:dd:ee:ff", salt)), salt)

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORED_OFF, result.outcome)
        assertEquals(listOf(true), approvals)
    }

    @Test
    fun `the trusted list in dumpsys adb is read case-insensitively, keys and other text ignored`() {
        val dump = """
            ADB MANAGER STATE (dumpsys adb):
              debugging_manager={
                connected_to_adb=true
                user_keys=QAAAAHk2 user@host
                keystore=<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <keyStore version="1"><adbKey key="QAAAAHk2 user@host" lastConnection="1700000000000" /><wifiAP bssid="AA:BB:CC:DD:EE:FF" /><wifiAP bssid="aa:bb:cc:dd:ee:ff" /><wifiAP bssid="10:20:30:40:50:60" /></keyStore>
              }
        """.trimIndent()

        assertEquals(setOf("aa:bb:cc:dd:ee:ff", "10:20:30:40:50:60"), WirelessDebuggingApproval.parseTrustedBssids(dump))
    }

    @Test
    fun `only the keystore field counts, not a key comment that happens to say keystore`() {
        val dump = """
            ADB MANAGER STATE (dumpsys adb):
              debugging_manager={
                connected_to_adb=true
                user_keys=QAAAAHk2 keystore@11:22:33:44:55:66
                keystore=<keyStore version="1"><wifiAP bssid="aa:bb:cc:dd:ee:ff" /></keyStore>
              }
        """.trimIndent()

        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), WirelessDebuggingApproval.parseTrustedBssids(dump))
    }

    @Test
    fun `a dump whose only 'keystore' is inside a key comment has an empty trusted list`() {
        val dump = "ADB MANAGER STATE (dumpsys adb):\n  debugging_manager={\n    user_keys=QAAA keystore@11:22:33:44:55:66\n  }"

        assertEquals(emptySet<String>(), WirelessDebuggingApproval.parseTrustedBssids(dump))
    }

    @Test
    fun `a shell command that hangs is stopped at its timeout`() {
        val started = System.nanoTime()

        val output = WirelessDebuggingApproval.runCommandWithTimeout(arrayOf("/bin/sleep", "20"), timeoutMs = 500L)

        assertNull(output)
        assertTrue((System.nanoTime() - started) / 1_000_000L < 5_000L)
        assertEquals("hi\n", WirelessDebuggingApproval.runCommandWithTimeout(arrayOf("/bin/echo", "hi"), timeoutMs = 5_000L))
    }

    @Test
    fun `a dump with no keystore means an empty trusted list, and a refused or failed dump means unknown`() {
        assertEquals(emptySet<String>(), WirelessDebuggingApproval.parseTrustedBssids("ADB MANAGER STATE (dumpsys adb):\n  debugging_manager={\n  connected_to_adb=false\n}"))
        assertNull(WirelessDebuggingApproval.parseTrustedBssids("Permission Denial: can't dump adb from pid=1, uid=10311"))
        assertNull(WirelessDebuggingApproval.parseTrustedBssids(null))
    }

    @Test
    fun `a trusted list in binary XML still yields its access points`() {
        // Android 12+ may write the keystore as binary XML; the address strings stay plain ASCII inside it.
        val abx = "ABX\u0000\u0001\u0010keyStore\u002f\u0011\u0000\u0005wifiAP\u002f\u0000\u0011aa:bb:cc:dd:ee:ff\u0003"

        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), WirelessDebuggingApproval.parseTrustedBssids("ADB MANAGER STATE (dumpsys adb):\n keystore=$abx"))
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
        assertTrue(result.restoreMs >= 8_000L)
        assertFalse(result.outcome.trusted)
    }

    @Test
    fun `switched on by Android after the first wait - seen by the late re-read and switched back off`() {
        androidTurnsOnAfterReads = 40 // past the 3 s of 100 ms reads

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.APPROVED_RESTORED_OFF, result.outcome)
        assertEquals(1, offWrites)
        assertEquals(0, wd)
        assertTrue(result.restoreMs in 3_000L..8_000L)
    }

    @Test
    fun `not confirmed, then on within the leftover window - the next check switches its own leftover off`() {
        androidTurnsOnAfterReads = Int.MAX_VALUE
        assertEquals(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, approver().run(emptySet(), salt).outcome)

        // Soon after (within the window), Android does switch it on, and has the access point on its list.
        now += 10_000L
        wd = 1
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")
        val next = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.LEFTOVER_RESTORED_OFF, next.outcome)
        assertEquals(1, offWrites)
        assertEquals(0, wd)
        assertEquals(1, approvals.size)

        // Once put back, it is no longer ours: a later 'on' there is someone else's and is left alone.
        wd = 1
        assertEquals(KeptSwitchOutcome.TRUSTED_WIRELESS_DEBUGGING_ON, approver().run(emptySet(), salt).outcome)
        assertEquals(1, offWrites)
    }

    @Test
    fun `an expired leftover is never switched off - the user may have switched it on - and a warning says so`() {
        androidTurnsOnAfterReads = Int.MAX_VALUE
        val approvedAt = now
        assertEquals(KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED, approver().run(emptySet(), salt).outcome)

        // Days later the user switches Wireless debugging on (to pair), and the phone rejoins that access point.
        now = approvedAt + 3L * 24 * 60 * 60 * 1000
        wd = 1
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")
        val later = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.LEFTOVER_EXPIRED_LEFT_ON, later.outcome)
        assertEquals(0, offWrites)
        assertEquals(1, wd)
        assertEquals(now - approvedAt, later.restoreMs)
        val (warn, text) = KeptSwitchReport.describe(later)
        assertTrue(warn)
        assertTrue(text, text.contains("may be left over from our approval"))
        assertTrue(text, text.contains("left alone because it may be the user's"))

        // Dropped once read: the next look is a plain "on that is not ours".
        assertEquals(KeptSwitchOutcome.TRUSTED_WIRELESS_DEBUGGING_ON, approver().run(emptySet(), salt).outcome)
        assertEquals(0, offWrites)
    }

    @Test
    fun `a leftover just past the window is already expired`() {
        androidTurnsOnAfterReads = Int.MAX_VALUE
        val approvedAt = now
        approver().run(emptySet(), salt)

        now = approvedAt + KeptSwitchApprover.LEFTOVER_WINDOW_MS + 1L
        wd = 1
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")

        assertEquals(KeptSwitchOutcome.LEFTOVER_EXPIRED_LEFT_ON, approver().run(emptySet(), salt).outcome)
        assertEquals(0, offWrites)
    }

    @Test
    fun `trusted, and Wireless debugging on that CallVault did not leave on - left on, nothing written`() {
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")
        wd = 1

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.TRUSTED_WIRELESS_DEBUGGING_ON, result.outcome)
        assertTrue(result.outcome.trusted)
        assertEquals(0, offWrites)
        assertEquals(1, wd)
    }

    @Test
    fun `a leftover is not switched off outside tcp mode`() {
        androidTurnsOnAfterReads = Int.MAX_VALUE
        approver().run(emptySet(), salt)
        wd = 1
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")
        tcpMode = false

        assertEquals(KeptSwitchOutcome.NOT_TCP_MODE, approver().run(emptySet(), salt).outcome)
        assertEquals(0, offWrites)
        assertEquals(1, wd)
    }

    @Test
    fun `an error after approving still switches back off what the approval switched on`() {
        throwingReadsAfterApproval = 1
        androidTurnsOnAfterReads = 1

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.INTERRUPTED_RESTORED_OFF, result.outcome)
        assertEquals(1, offWrites)
        assertEquals(0, wd)
    }

    @Test
    fun `an error after approving with the switch still off writes nothing, and keeps the leftover for later`() {
        throwingReadsAfterApproval = 1
        androidTurnsOnAfterReads = Int.MAX_VALUE

        val result = approver().run(emptySet(), salt)

        assertEquals(KeptSwitchOutcome.INTERRUPTED_STILL_OFF, result.outcome)
        assertEquals(0, offWrites)
        wd = 1
        trustedByAndroid = setOf("aa:bb:cc:dd:ee:ff")
        assertEquals(KeptSwitchOutcome.LEFTOVER_RESTORED_OFF, approver().run(emptySet(), salt).outcome)
    }

    @Test
    fun `an error after approving with the switch unreadable is reported as unknown`() {
        throwingReadsAfterApproval = 2

        assertEquals(KeptSwitchOutcome.INTERRUPTED_STATE_UNKNOWN, approver().run(emptySet(), salt).outcome)
        assertEquals(0, offWrites)
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
