/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownConflictingAppsTest {

    private fun manifest(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File(dir, "src/main/AndroidManifest.xml")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("AndroidManifest.xml not found")
    }

    @Test fun detectsOnlyThePackagesThePhoneConfirms() {
        val found = KnownConflictingApps.detect { it == "com.jemcik.jemrec" || it == "com.not.in.list" }
        assertEquals(listOf("com.jemcik.jemrec"), KnownConflictingApps.packageNames(found))
    }

    @Test fun neverAsksAboutAPackageOutsideTheFixedList() {
        val asked = mutableSetOf<String>()
        KnownConflictingApps.detect { asked += it; false }
        assertEquals(KnownConflictingApps.entries.map { it.packageName }.toSet(), asked)
    }

    @Test fun aFailingLookupReadsAsNotInstalled() {
        assertTrue(KnownConflictingApps.detect { error("boom") }.isEmpty())
    }

    @Test fun nothingInstalledMeansNothingReported() {
        assertTrue(KnownConflictingApps.packageNames(KnownConflictingApps.detect { false }).isEmpty())
    }

    @Test fun shizuCallRecorderAndAVaultShareOnePackage() {
        val e = KnownConflictingApps.entries.single { it.packageName == "com.kitsumed.shizucallrecorder" }
        assertTrue(e.displayName.contains("AVault"))
        assertTrue(e.ids.containsAll(listOf("A1", "A2")))
    }

    @Test fun packageNamesAreUniqueAndEveryIdIsKnown() {
        val names = KnownConflictingApps.entries.map { it.packageName }
        assertEquals(names.size, names.toSet().size)
        val known = PhoneRules.appEntries.map { it.id }.toSet()
        assertTrue(known.containsAll(KnownConflictingApps.coveredIds))
    }

    @Test fun manifestNeverAsksForTheFullPackageList() {
        val m = manifest()
        assertFalse(m.contains("android.permission.QUERY_ALL_PACKAGES"))
        // The visibility the lookup relies on: launcher apps.
        assertTrue(m.contains("android.intent.category.LAUNCHER"))
    }

    @Test fun reportListsPackagesWithTheirIdsAndOnlyMatches() {
        val p = PhoneProfile.UNKNOWN.copy(conflictingApps = listOf("com.jemcik.jemrec"))
        val lines = PhoneProfileReport.lines(p)
        val line = lines.single { it.startsWith("other apps") }
        assertTrue(line, line.contains("com.jemcik.jemrec A1/A2/A4/A5"))
        assertFalse(lines.joinToString("\n").contains("com.truecaller"))
        assertTrue(PhoneProfileReport.lines(PhoneProfile.UNKNOWN).single { it.startsWith("other apps") }.endsWith("none of the known packages"))
    }

    @Test fun alwaysOnLogWritesOnlyWhenTheMatchesChange() {
        val a = PhoneProfile.UNKNOWN.copy(conflictingApps = listOf("com.jemcik.jemrec"))
        assertTrue(PhoneProfileReport.changeLines(a.hash(), emptySet(), a).isEmpty())
        val b = a.copy(conflictingApps = listOf("com.jemcik.jemrec", "com.truecaller"))
        val lines = PhoneProfileReport.changeLines(a.hash(), emptySet(), b)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("apps=com.jemcik.jemrec,com.truecaller"))
    }
}
