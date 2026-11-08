/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.utils.CustomFileNameTemplate.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A typed-in file-name template (ShizuCallRecorder's editor, brought back for 2.5.0). */
class CustomFileNameTemplateTest {

    @Test
    fun `a template with the date and known tags is fine`() {
        assertNull(CustomFileNameTemplate.problemOf("{contact_name}_{date}"))
        assertNull(CustomFileNameTemplate.problemOf("Call_{date}_{direction}_{phone_number}_{cross_country}"))
        assertNull(CustomFileNameTemplate.problemOf("{date} - {contact_name}"))
    }

    @Test
    fun `every preset passes the same check`() {
        FILE_NAME_TEMPLATE_PRESETS.filter { it.template != HONOR_DIALER_TEMPLATE }.forEach {
            assertNull(it.template, CustomFileNameTemplate.problemOf(it.template))
        }
    }

    @Test
    fun `blank is refused`() {
        assertEquals(Problem.EMPTY, CustomFileNameTemplate.problemOf("   "))
    }

    @Test
    fun `the date is required, because the list and merging read it back from the name`() {
        assertEquals(Problem.NEEDS_DATE, CustomFileNameTemplate.problemOf("{contact_name}_{phone_number}"))
        assertEquals(Problem.NEEDS_DATE, CustomFileNameTemplate.problemOf("{date_compact}_{contact_name}"))
    }

    @Test
    fun `characters a file name cannot hold are refused`() {
        listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|").forEach { bad ->
            assertEquals(bad, Problem.BAD_CHARACTER, CustomFileNameTemplate.problemOf("{date}${bad}{contact_name}"))
        }
    }

    @Test
    fun `an unknown or broken tag is refused`() {
        assertEquals(Problem.UNKNOWN_TAG, CustomFileNameTemplate.problemOf("{date}_{name}"))
        assertEquals(Problem.UNKNOWN_TAG, CustomFileNameTemplate.problemOf("{date}_{contact_name"))
        assertEquals(Problem.UNKNOWN_TAG, CustomFileNameTemplate.problemOf("{date}_contact}"))
    }

    @Test
    fun `an overlong template is refused`() {
        assertEquals(Problem.TOO_LONG, CustomFileNameTemplate.problemOf("{date}_" + "x".repeat(200)))
    }

    @Test
    fun `the unknown tag is named for the message`() {
        assertEquals("{name}", CustomFileNameTemplate.firstUnknownTag("{date}_{name}_{other}"))
    }

    @Test
    fun `only the presets count as presets`() {
        assertTrue(CustomFileNameTemplate.isPreset("{date}_{phone_number}"))
        assertFalse(CustomFileNameTemplate.isPreset("{contact_name}-{date}"))
    }
}
