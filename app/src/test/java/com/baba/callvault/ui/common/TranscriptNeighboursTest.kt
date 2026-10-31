/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptNeighboursTest {

    private val order = listOf("a", "b", "c")

    @Test
    fun a_middle_call_has_both_neighbours() {
        assertEquals("a" to "c", TranscriptNeighbours.around(order, "b"))
    }

    @Test
    fun the_first_call_has_no_previous() {
        assertEquals(null to "b", TranscriptNeighbours.around(order, "a"))
    }

    @Test
    fun the_last_call_has_no_next() {
        assertEquals("b" to null, TranscriptNeighbours.around(order, "c"))
    }

    @Test
    fun a_call_outside_the_list_has_none() {
        assertEquals(null to null, TranscriptNeighbours.around(order, "x"))
        assertEquals(null to null, TranscriptNeighbours.around(emptyList(), "a"))
    }
}
