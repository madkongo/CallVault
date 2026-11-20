/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProfileTest {

    @Test fun unknownProfileReadsEverythingAsUnknown() {
        val p = PhoneProfile.UNKNOWN
        assertEquals(Tri.UNKNOWN, p.batteryExempt)
        assertEquals(Tri.UNKNOWN, p.backgroundRestricted)
        assertEquals(StandbyBucketClass.UNKNOWN, p.standbyBucket)
        assertEquals(Tri.UNKNOWN, p.hibernation)
        assertEquals(Tri.UNKNOWN, p.notificationsEnabled)
        assertEquals(null, p.mode)
    }

    @Test fun logLineNamesEveryFactAndContainsNoPersonalData() {
        val line = PhoneProfile.UNKNOWN.copy(maker = "OnePlus", model = "CPH2581", sdk = 36, batteryExempt = Tri.NO).logLine()
        listOf("maker=OnePlus", "model=CPH2581", "sdk=36", "exempt=no", "restricted=?", "bucket=?", "hib=?", "notif=?", "usb=?")
            .forEach { assertTrue("$it in $line", line.contains(it)) }
        assertFalse(line.contains('\n'))
    }

    @Test fun hashChangesWhenAFactChangesAndNotOtherwise() {
        val a = PhoneProfile.UNKNOWN.copy(maker = "x", batteryExempt = Tri.YES)
        assertEquals(a.hash(), a.copy().hash())
        assertNotEquals(a.hash(), a.copy(batteryExempt = Tri.NO).hash())
    }

    @Test fun standbyBucketIsCollapsedSoTheLogDoesNotChurn() {
        assertEquals(StandbyBucketClass.RESTRICTED, StandbyBucketClass.of(45))
        assertEquals(StandbyBucketClass.RARE, StandbyBucketClass.of(40))
        assertEquals(StandbyBucketClass.NORMAL, StandbyBucketClass.of(10))
        assertEquals(StandbyBucketClass.NORMAL, StandbyBucketClass.of(30))
        assertEquals(StandbyBucketClass.UNKNOWN, StandbyBucketClass.of(null))
        assertEquals(StandbyBucketClass.NEVER, StandbyBucketClass.of(50))
        assertEquals(StandbyBucketClass.RESTRICTED, StandbyBucketClass.of(45))
    }

    @Test fun reportLinesHaveBothSections() {
        val lines = PhoneProfileReport.lines(PhoneProfile.UNKNOWN)
        assertTrue(lines.contains("--- Phone profile ---"))
        assertTrue(lines.contains("--- Active phone rules ---"))
    }
}
