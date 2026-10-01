/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameTemplatesTest {

    @Test
    fun `every preset example substitutes all tokens with no leftover braces`() {
        FILE_NAME_TEMPLATE_PRESETS.forEach { preset ->
            val example = fileNameTemplateExample(preset.template)
            assertFalse(
                "Template '${preset.template}' left an unresolved token in '$example'",
                example.contains("{") || example.contains("}"),
            )
        }
    }

    @Test
    fun `a stored template round-trips to its own preset`() {
        FILE_NAME_TEMPLATE_PRESETS.forEach { preset ->
            assertTrue(presetForTemplateOrFirst(preset.template).template == preset.template)
        }
    }

    @Test
    fun `an unknown template falls back to the first preset`() {
        val first = FILE_NAME_TEMPLATE_PRESETS.first()
        assertTrue(presetForTemplateOrFirst("{nope}_{missing}").template == first.template)
    }

    @Test
    fun `the new contact-first and number-first orders are offered`() {
        val templates = FILE_NAME_TEMPLATE_PRESETS.map { it.template }
        assertTrue(templates.contains("{contact_name}_{phone_number}_{date}"))
        assertTrue(templates.contains("{phone_number}_{contact_name}_{date}"))
    }
}
