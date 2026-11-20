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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard: every quirk id in docs/dev-notes/device-quirks.md has a registry entry, and the reverse.
 * Adding a quirk to the catalogue without deciding how (or whether) it can be detected fails here.
 */
class PhoneRulesCoverageTest {

    private fun catalogue(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/dev-notes/device-quirks.md")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("docs/dev-notes/device-quirks.md not found above ${System.getProperty("user.dir")}")
    }

    private fun idsInCatalogue(): Set<String> {
        val text = catalogue()
        val q = Regex("^### (Q\\d+)\\b", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }
        val u = Regex("^\\| (U\\d+) \\|", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }
        return (q + u).toSet()
    }

    @Test
    fun everyCatalogueIdHasARegistryEntry() {
        val missing = idsInCatalogue() - PhoneRules.ids
        assertTrue("Quirk ids with no PhoneRules entry (add one, or mark not-detectable with a reason): $missing", missing.isEmpty())
    }

    @Test
    fun everyRegistryIdIsInTheCatalogue() {
        val stray = PhoneRules.ids - idsInCatalogue()
        assertTrue("PhoneRules ids missing from device-quirks.md: $stray", stray.isEmpty())
    }

    @Test
    fun catalogueIsNotEmptyAndHasTheKnownRange() {
        val ids = idsInCatalogue()
        assertTrue(ids.containsAll((1..76).map { "Q%02d".format(it) }))
        assertTrue(ids.containsAll((1..8).map { "U$it" }))
    }

    @Test
    fun notDetectableEntriesCarryAReason() {
        val bad = PhoneRules.entries.filter {
            it.kind == DetectionKind.NOT_DETECTABLE && it.reason.isNullOrBlank()
        }.map { it.id }
        assertTrue("Not-detectable without a reason: $bad", bad.isEmpty())
    }

    @Test
    fun idsAreUnique() {
        assertEquals(PhoneRules.entries.size, PhoneRules.entries.map { it.id }.toSet().size)
    }
}
