/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "1.5" in the long-call line: one decimal, in the reader's own decimal mark. */
class SlowdownFormatTest {

    @Test
    fun `one decimal in English`() {
        assertEquals("1.5", formatSlowdown(1.5, Locale.US))
    }

    @Test
    fun `a whole factor still shows its decimal`() {
        assertEquals("2.0", formatSlowdown(2.0, Locale.US))
    }

    @Test
    fun `a comma decimal mark where the language uses one`() {
        assertEquals("1,5", formatSlowdown(1.5, Locale.GERMANY))
    }

    @Test
    fun `a long call's length and run time are quoted in hours and minutes`() {
        // 80 min and 120 min, as formatEstimate rounds them.
        assertEquals(80, quotedMinutes(80L * 60_000L))
        assertEquals(120, quotedMinutes(120L * 60_000L))
    }
}
