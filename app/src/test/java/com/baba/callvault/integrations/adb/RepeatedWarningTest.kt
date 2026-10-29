/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #65 log repeated the mDNS "nothing found" line every 12 s for 25 minutes. */
class RepeatedWarningTest {

    @Test
    fun `the first warning goes through, repeats inside the interval are counted onto the next`() {
        val warning = RepeatedWarning(intervalMs = 60_000)
        assertEquals(0, warning.next(0))
        assertNull(warning.next(12_000))
        assertNull(warning.next(24_000))
        assertTrue(warning.isQuiet(30_000))
        assertEquals(2, warning.next(60_000))
        assertFalse(warning.isQuiet(120_000))
    }

    @Test
    fun `a success starts over`() {
        val warning = RepeatedWarning(intervalMs = 60_000)
        warning.next(0)
        warning.reset()
        assertFalse(warning.isQuiet(1))
        assertEquals(0, warning.next(1))
    }
}
