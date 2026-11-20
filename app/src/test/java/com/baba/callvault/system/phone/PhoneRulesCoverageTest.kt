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

    private fun appIdsInCatalogue(): Set<String> =
        Regex("^\\| (A\\d+) \\|", RegexOption.MULTILINE).findAll(catalogue()).map { it.groupValues[1] }.toSet()

    private val appRegistryIds get() = PhoneRules.appEntries.map { it.id }.toSet()

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
    fun everyAppConflictInTheCatalogueHasARegistryEntry() {
        val missing = appIdsInCatalogue() - appRegistryIds
        assertTrue("A-ids with no PhoneRules entry: $missing", missing.isEmpty())
    }

    @Test
    fun everyAppRegistryIdIsInTheCatalogue() {
        val stray = appRegistryIds - appIdsInCatalogue()
        assertTrue("A-ids in PhoneRules missing from device-quirks.md: $stray", stray.isEmpty())
    }

    @Test
    fun appCatalogueHasAllNineteen() {
        assertEquals((1..19).map { "A$it" }.toSet(), appIdsInCatalogue())
    }

    @Test
    fun appIdsAreKeptOutOfTheQuirkIdSet() {
        assertTrue(PhoneRules.ids.none { it.startsWith("A") })
        assertTrue(PhoneRules.entries.none { it.id.startsWith("A") })
    }

    @Test
    fun packageListKindMatchesTheFixedPackageTable() {
        val listed = PhoneRules.appEntries.filter { it.kind == DetectionKind.PACKAGE_LIST }.map { it.id }.toSet()
        assertEquals("PACKAGE_LIST ids must be exactly the ids the package table covers", KnownConflictingApps.coveredIds, listed)
    }

    @Test
    fun everyAppCrossReferenceNamesARealQuirk() {
        val refs = Regex("\\| Other apps \\| ([^|]*?) \\(see section F\\)").findAll(catalogue())
            .flatMap { it.groupValues[1].split(",") }.map { it.trim() }.toSet()
        assertTrue("cross-references name unknown A-ids: ${refs - appIdsInCatalogue()}", appIdsInCatalogue().containsAll(refs))
        assertTrue(refs.isNotEmpty())
    }

    @Test
    fun appQuirkCrossReferencesRunBothWays() {
        val text = catalogue()
        // Q07 <-> A5 and U7 <-> A2, the pairs the owner named.
        assertTrue(Regex("^### Q07 .*\\n\\| \\| \\|\\n\\|---\\|---\\|\\n\\| Other apps \\| A5", RegexOption.MULTILINE).containsMatchIn(text))
        assertTrue(Regex("^\\| U7 \\|.*Other apps: A2", RegexOption.MULTILINE).containsMatchIn(text))
        assertTrue(Regex("^\\| A5 \\|.*Q07").containsMatchIn(text.lines().first { it.startsWith("| A5 |") }))
        assertTrue(text.lines().first { it.startsWith("| A2 |") }.contains("U7"))
    }

    @Test
    fun notDetectableEntriesCarryAReason() {
        val bad = (PhoneRules.entries + PhoneRules.appEntries).filter {
            it.kind == DetectionKind.NOT_DETECTABLE && it.reason.isNullOrBlank()
        }.map { it.id }
        assertTrue("Not-detectable without a reason: $bad", bad.isEmpty())
    }

    @Test
    fun idsAreUnique() {
        assertEquals(PhoneRules.entries.size, PhoneRules.entries.map { it.id }.toSet().size)
    }
}
