/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps item 111: Telecom cancels the user's call if the redirection service does not answer, so it must always answer. */
class CallRedirectAnswerTest {

    @Test
    fun answersFirst_thenLogsTheTimings() {
        val events = mutableListOf<String>()
        var t = 1_000L
        CallRedirectAnswer.answer(
            boundAtMs = 400L,
            now = { t.also { t += 7 } },
            place = { events += "place" },
            describe = { events += "describe"; "acct" },
            log = { events += "log:$it" },
        )
        assertEquals("place", events[0])
        assertEquals("describe", events[1])
        assertTrue(events[2], events[2].contains("bind->onPlaceCall=600ms placeCallUnmodified=7ms acct"))
    }

    @Test
    fun answersEvenWhenLoggingThrows() {
        var placed = 0
        CallRedirectAnswer.answer(0, { 0L }, { placed++ }, { "x" }, { throw IllegalStateException("disk full") })
        assertEquals(1, placed)
    }

    @Test
    fun answersEvenWhenDescribingThrows() {
        var placed = 0
        val lines = mutableListOf<String>()
        CallRedirectAnswer.answer(0, { 0L }, { placed++ }, { throw NullPointerException() }, { lines += it })
        assertEquals(1, placed)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aFailingAnswerIsLoggedAndDoesNotEscape() {
        val lines = mutableListOf<String>()
        CallRedirectAnswer.answer(0, { 0L }, { throw SecurityException("gone") }, { "x" }, { lines += it })
        assertTrue(lines.single().contains("placeCallUnmodified FAILED SecurityException"))
    }
}
