/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A page opened before the first transcript must pick it up without reopening the app. */
class OnceTranscriptsExistTest {

    @Test
    fun `an existing database is queried straight away`() = runTest {
        var opened = 0
        val values = onceExists(exists = { true }, empty = 0, open = { opened++; flowOf(5) }).toList()
        assertEquals(listOf(5), values)
        assertEquals(1, opened)
    }

    @Test
    fun `empty first, then the real answer once the database appears`() = runTest {
        var checks = 0
        val values = onceExists(
            exists = { checks++ >= 3 }, // missing for the first look and two waits
            empty = 0,
            open = { flowOf(7) },
            pollMs = 10,
        ).take(2).toList()
        assertEquals(listOf(0, 7), values)
    }

    @Test
    fun `nothing is opened while the database is missing`() = runTest {
        var opened = 0
        val values = onceExists(exists = { false }, empty = 0, open = { opened++; flowOf(1) }, pollMs = 10)
            .take(1).toList()
        assertEquals(listOf(0), values)
        assertEquals(0, opened)
    }
}
