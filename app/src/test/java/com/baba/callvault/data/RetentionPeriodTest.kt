/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPeriodTest {

    @Test
    fun `the longer periods from #78 are offered`() {
        listOf(60, 90, 120).forEach { days ->
            assertEquals("$days days must be a preset", days, RetentionPeriod.fromDays(days).days)
        }
    }

    @Test
    fun `the dropdown lists keep-forever first, then the periods from shortest to longest`() {
        val days = RetentionPeriod.entries.map { it.days }
        assertEquals(0, days.first())
        assertEquals(days.drop(1).sorted(), days.drop(1))
    }

    @Test
    fun `a day count with no preset shows as keep forever in the dropdown`() {
        // Display only: the cleanup job uses the stored day count itself (RetentionPolicy.cutoffFor).
        assertEquals(RetentionPeriod.FOREVER, RetentionPeriod.fromDays(45))
        assertEquals(RetentionPeriod.FOREVER, RetentionPeriod.fromDays(-1))
    }
}
