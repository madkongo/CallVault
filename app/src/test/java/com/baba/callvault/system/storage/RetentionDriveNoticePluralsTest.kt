/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * French, Spanish, Italian and Portuguese have a `many` plural form (for large round numbers), and lint's
 * MissingQuantity fails a plural that leaves it out. The Drive notice's count can be large, so it needs it.
 */
class RetentionDriveNoticePluralsTest {

    private val resDir = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    @Test
    fun `the Drive notice title has a many form where the language has one`() {
        listOf("values-fr", "values-es", "values-it", "values-pt-rBR").forEach { locale ->
            val xml = File(resDir, "$locale/strings.xml").readText()
            val plural = Regex("""<plurals name="notif_retention_waiting_for_drive_title">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1).orEmpty()
            assertTrue("$locale is missing quantity=\"many\"", plural.contains("quantity=\"many\""))
        }
    }
}
