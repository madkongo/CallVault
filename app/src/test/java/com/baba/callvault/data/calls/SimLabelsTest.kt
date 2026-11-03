/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimLabelsTest {

    @Test
    fun `a name the user gave the SIM is shown`() {
        assertEquals("Work", SimLabels.label("Work", "Partner", 1))
    }

    @Test
    fun `Android's default name falls back to the slot`() {
        // Measured on the OP9: an unnamed SIM reads "SIM1" with carrier "Partner".
        assertEquals("SIM 1", SimLabels.label("SIM1", "Partner", 0))
        assertEquals("SIM 2", SimLabels.label("SIM 2", "Cellcom", 1))
        assertEquals("SIM 2", SimLabels.label("Card 2", null, 1))
        assertEquals("SIM 1", SimLabels.label("eSIM", null, 0))
    }

    @Test
    fun `the carrier's own name is not a user name`() {
        assertEquals("SIM 1", SimLabels.label("Partner", "Partner", 0))
    }

    @Test
    fun `no name and no slot says nothing`() {
        assertNull(SimLabels.label(null, null, null))
        assertNull(SimLabels.label("SIM1", "Partner", -1))
    }

    @Test
    fun `the word for SIM can be translated`() {
        assertEquals("SIM-карта 2", SimLabels.label("", null, 1, simWord = "SIM-карта"))
    }
}
