/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's cut of `dumpsys batterystats -c <package>`: only the since-charge rows of the app's uid, the shell
 * uid (the recorder and adbd), root when the recorder IS root, and the device-wide header rows. Everything about
 * any other app is dropped in the recorder, before it crosses the binder.
 */
class BatteryCheckinCutTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun cut(name: String, recorderUid: Int = 2000, appUid: Int? = null, maxChars: Int = BatteryCheckin.MAX_CHARS) =
        BatteryCheckin.cut(fixture(name).lineSequence(), PACKAGE, recorderUid, appUid, maxChars)

    @Test
    fun `the cut starts with its marker and names the uids it kept`() {
        val out = cut("batterystats_checkin_a14.txt")
        assertTrue(out, out.startsWith(BatteryCheckin.HEADER))
        assertTrue(out.lineSequence().first(), out.lineSequence().first().contains("app_uid=10234"))
        assertTrue(BatteryCheckin.isCut(out))
    }

    @Test
    fun `the app uid comes from batterystats' own uid map when the caller does not give one`() {
        val rows = cut("batterystats_checkin_a14.txt").lines()
        assertTrue(rows.contains("9,10234,l,cpu,95000,35000,0"))
        assertTrue(rows.contains("9,2000,l,cpu,612000,230000,0"))
    }

    @Test
    fun `the device-wide header rows are kept`() {
        val rows = cut("batterystats_checkin_a14.txt").lines()
        listOf("9,0,l,bt,", "9,0,l,dc,", "9,0,l,m,", "9,0,l,pws,", "9,0,i,vers,").forEach { prefix ->
            assertTrue("$prefix must be kept", rows.any { it.startsWith(prefix) })
        }
    }

    @Test
    fun `no other app's row, name or package crosses the binder`() {
        val out = cut("batterystats_checkin_a14.txt")
        listOf("10111", "com.whatsapp", "WhatsApp", "+15551234567", "9,1000,", "com.android.settings", "PowerManagerService", "subsystem.modem")
            .forEach { assertFalse("'$it' must be dropped:\n$out", out.contains(it)) }
    }

    @Test
    fun `root's own rows are dropped unless the recorder runs as root`() {
        assertFalse(cut("batterystats_checkin_a14.txt").lines().contains("9,0,l,cpu,800000,400000,0"))
        assertTrue(cut("batterystats_checkin_a14.txt", recorderUid = 0).lines().contains("9,0,l,cpu,800000,400000,0"))
    }

    @Test
    fun `a wake lock name that is not ours is replaced, ours is kept`() {
        val rows = cut("batterystats_checkin_a14.txt").lines()
        assertTrue(rows.any { it.startsWith("9,2000,l,wl,other,") })
        assertFalse(rows.any { it.contains("shizuku") })
        assertTrue(rows.any { it.startsWith("9,10234,l,wl,*job*/com.baba.callvault/") })
        // A quoted name (jobs, processes) stays quoted when it is replaced.
        assertTrue(rows.contains("9,2000,l,pr,\"other\",100,50,0,1,0,0"))
    }

    @Test
    fun `a root recorder's root wake lock names are replaced too`() {
        val rows = cut("batterystats_checkin_a11_root.txt", recorderUid = 0).lines()
        assertTrue(rows.any { it.startsWith("9,0,l,wl,other,") })
        assertFalse(rows.any { it.contains("suspend_backoff") || it.contains("user@example.com") })
    }

    @Test
    fun `the recorder's uid is read back from the header`() {
        assertEquals(0, BatteryCheckin.recorderUidOf(cut("batterystats_checkin_a11_root.txt", recorderUid = 0)))
        assertEquals(2000, BatteryCheckin.recorderUidOf(cut("batterystats_checkin_a14.txt")))
        assertEquals(null, BatteryCheckin.recorderUidOf(fixture("batterystats_checkin_a14.txt")))
    }

    @Test
    fun `history rows are dropped`() {
        val out = cut("batterystats_checkin_a16_extra_fields.txt")
        assertFalse(out, out.lines().any { it.startsWith("9,h,") || it.startsWith("9,hsp,") })
    }

    @Test
    fun `a caller-known app uid wins over the map`() {
        val rows = cut("batterystats_checkin_a14.txt", appUid = 10111).lines()
        assertTrue(rows.any { it.startsWith("9,10111,l,cpu,") })
        assertFalse(rows.any { it.startsWith("9,10234,") })
    }

    @Test
    fun `the cut is bounded and says how much it left out`() {
        val many = (1..20_000).asSequence().map { "9,10234,l,wua,*walarm*:com.baba.callvault.A$it,1" }
        val out = BatteryCheckin.cut(
            sequenceOf("9,0,i,uid,10234,$PACKAGE", "9,0,l,bt,0,1,1,1,1,1,1,1,1,1,1,1") + many,
            PACKAGE, 2000, null, maxChars = 10_000,
        )
        assertTrue("got ${out.length}", out.length <= 10_000 + 200)
        assertTrue(out, out.trimEnd().lines().last().startsWith("# truncated: "))
        assertTrue(out.contains("9,0,l,bt,"))
    }

    @Test
    fun `the default bound stays well under the binder's 1 MB`() {
        // UTF-16 on the wire: 200k chars is ~400 KB.
        assertTrue(BatteryCheckin.MAX_CHARS <= 200_000)
    }

    @Test
    fun `a line that is not a checkin row is dropped`() {
        val out = BatteryCheckin.cut(sequenceOf("Permission Denial: can't dump BatteryStats", "", "garbage,,,"), PACKAGE, 2000, null)
        assertEquals(1, out.lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `quoted fields are split as one`() {
        assertEquals(listOf("9", "1", "l", "jb", "a,b", "5"), BatteryCheckin.split("9,1,l,jb,\"a,b\",5"))
    }

    private companion object {
        const val PACKAGE = "com.baba.callvault"
    }
}
