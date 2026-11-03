/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveSweepOrderTest {

    private fun p(name: String, at: Long) = DriveSweepOrder.Pending(name, at, Unit)

    @Test
    fun `uploads the newest recording first, whatever its name`() {
        // Contact-first names: alphabetical order would put an old call to "Anna" before today's to "Zoe".
        val order = DriveSweepOrder.newestFirst(listOf(p("Anna.m4a", 100), p("Zoe.m4a", 300), p("Mia.m4a", 200)))

        assertEquals(listOf("Zoe.m4a", "Mia.m4a", "Anna.m4a"), order.map { it.name })
    }

    @Test
    fun `an undated file goes last`() {
        val order = DriveSweepOrder.newestFirst(listOf(p("undated.ogg", 0), p("old.ogg", 5)))

        assertEquals(listOf("old.ogg", "undated.ogg"), order.map { it.name })
    }

    @Test
    fun `equal stamps keep a stable name order`() {
        val order = DriveSweepOrder.newestFirst(listOf(p("b.ogg", 7), p("a.ogg", 7)))

        assertEquals(listOf("a.ogg", "b.ogg"), order.map { it.name })
    }
}
