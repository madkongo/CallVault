/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val texts = mapOf(
        "VoipIgnoreContactsRow" to SettingsSearch.normalize("Skip ignored contacts on app calls whatsapp telegram"),
        "IgnoreContactsIncomingRow" to SettingsSearch.normalize("Ignored contacts incoming calls"),
        "ConnectionRows" to SettingsSearch.normalize("Wireless debugging wifi usb"),
    )

    @Test
    fun `every word must match, anywhere in the row`() {
        assertEquals(setOf("VoipIgnoreContactsRow"), SettingsSearch.matchingRows(texts, "contacts whatsapp"))
        assertEquals(setOf("VoipIgnoreContactsRow", "IgnoreContactsIncomingRow"), SettingsSearch.matchingRows(texts, "contacts"))
    }

    @Test
    fun `case and accents do not matter`() {
        assertEquals(setOf("ConnectionRows"), SettingsSearch.matchingRows(texts, "WIFI"))
        assertEquals("telephone", SettingsSearch.normalize("Téléphone"))
    }

    @Test
    fun `a blank query matches nothing rather than everything`() {
        assertTrue(SettingsSearch.matchingRows(texts, "   ").isEmpty())
    }

    @Test
    fun `every settings row can be searched`() {
        // A new row must be added to SettingsSearchIndex, or the search would never find it.
        val rows = SettingsGroup.entries.flatMap { SettingsGroups.rowsOf(it) }.toSet()
        assertEquals(emptySet<String>(), rows - SettingsSearchIndex.rows.keys)
        val empty = SettingsSearchIndex.rows.filter { (name, e) -> name in rows && e.strings.isEmpty() && e.words.isEmpty() }.keys
        assertEquals("rows with nothing to find them by", setOf("TranscriptionModeNote"), empty)
    }
}
