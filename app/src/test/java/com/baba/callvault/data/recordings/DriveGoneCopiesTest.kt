/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.data.recordings.DriveCatalogRepair.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveGoneCopiesTest {

    private val rows = listOf("a.ogg" to "u:a", "b.ogg" to "u:b", "c.ogg" to "u:c")

    @Test
    fun `a copy missing from the folder and reported gone is forgotten`() {
        val gone = DriveCatalogRepair.goneCopies(rows, setOf("a.ogg", "c.ogg")) { Presence.GONE }

        assertEquals(listOf("u:b"), gone)
    }

    @Test
    fun `missing from the listing alone is not enough`() {
        // Offline or a refused grant: Drive cannot answer, so the copy is kept.
        assertTrue(DriveCatalogRepair.goneCopies(rows, setOf("a.ogg")) { Presence.UNKNOWN }.isEmpty())
        assertTrue(DriveCatalogRepair.goneCopies(rows, setOf("a.ogg")) { Presence.PRESENT }.isEmpty())
    }

    @Test
    fun `a copy still in the folder is never asked about`() {
        val asked = mutableListOf<String>()
        DriveCatalogRepair.goneCopies(rows, setOf("a.ogg", "b.ogg", "c.ogg")) { asked += it; Presence.GONE }

        assertTrue(asked.isEmpty())
    }

    @Test
    fun `with no usable listing copies are checked one by one until one is still there`() {
        // A deleted folder or a failed listing: only Drive's own answer per copy decides.
        val gone = DriveCatalogRepair.goneCopies(rows, null) { if (it == "u:c") Presence.GONE else Presence.UNKNOWN }

        assertEquals(listOf("u:c"), gone)
    }

    // Review R22: with no usable listing every copy used to be opened, and opening a Drive document can download it.

    @Test
    fun `a copy that is still there proves the listing wrong and ends the blind check`() {
        val asked = mutableListOf<String>()

        val gone = DriveCatalogRepair.goneCopies(rows, emptySet()) { asked += it; if (it == "u:a") Presence.PRESENT else Presence.GONE }

        assertTrue(gone.isEmpty())
        assertEquals(listOf("u:a"), asked)
    }

    @Test
    fun `the blind check opens at most the cap per run`() {
        val many = (1..500).map { "n$it.ogg" to "u:$it" }
        val asked = mutableListOf<String>()

        val gone = DriveCatalogRepair.goneCopies(many, null) { asked += it; Presence.GONE }

        assertEquals(DriveCatalogRepair.MAX_BLIND_OPENS, asked.size)
        assertEquals(DriveCatalogRepair.MAX_BLIND_OPENS, gone.size)
    }

    @Test
    fun `the blind check does not run on a metered connection`() {
        val asked = mutableListOf<String>()

        assertTrue(DriveCatalogRepair.goneCopies(rows, null, blindAllowed = false) { asked += it; Presence.GONE }.isEmpty())
        assertTrue(DriveCatalogRepair.goneCopies(rows, emptySet(), blindAllowed = false) { asked += it; Presence.GONE }.isEmpty())

        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a good listing is not capped and not tied to the connection`() {
        val many = (1..500).map { "n$it.ogg" to "u:$it" }

        val gone = DriveCatalogRepair.goneCopies(many, setOf("other.ogg"), blindAllowed = false) { Presence.GONE }

        assertEquals(500, gone.size)
    }
}
