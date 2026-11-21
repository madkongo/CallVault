/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.data.recordings.RecordingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** A merged recording's name must still read back as the same call (and, for Honor, still link to it). */
class MergeNamingTest {

    @Test
    fun `a date-first stem has its millisecond nudged`() {
        assertEquals("20261009_143052.124+0300_in_Dana", MergeNaming.bumpedStem("20261009_143052.123+0300_in_Dana", 1))
    }

    @Test
    fun `a contact-first stem has its millisecond nudged where the date sits`() {
        assertEquals("Dana_0541234567_20261009_143052.125+0300", MergeNaming.bumpedStem("Dana_0541234567_20261009_143052.123+0300", 2))
    }

    @Test
    fun `an honor stem has its second nudged and still parses`() {
        val bumped = MergeNaming.bumpedStem("Баланс@087011_20261020103759", 1)
        assertEquals("Баланс@087011_20261020103800", bumped)
        val parsed = RecordingsRepository.parseName("$bumped.m4a")
        assertEquals("087011", parsed.number)
        assertNotNull(parsed.startedAtMillis)
    }

    @Test
    fun `a stem with no date still gets a distinct name`() {
        assertEquals("odd name_3", MergeNaming.bumpedStem("odd name", 3))
    }

    @Test
    fun `the nudged millisecond is ASCII digits in a locale with native digits`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("20261009_143052.007+0300_in_Dana", MergeNaming.bumpedStem("20261009_143052.006+0300_in_Dana", 1))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
