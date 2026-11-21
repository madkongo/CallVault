/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.utils.LateNumberFromLog.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C59: a blank-number call is never named after an older call's number. */
class LateNumberFromLogTest {

    private val start = 1_000_000_000_000L
    private val minute = 60_000L

    @Test
    fun `an older call from days ago is not this call`() {
        val rows = listOf(Row(start - 3 * 24 * 60 * minute, "+15551230000", RecordingDirection.INCOMING))
        assertNull(LateNumberFromLog.pick(rows, RecordingDirection.INCOMING, start))
    }

    @Test
    fun `the call that rang before we started is this call`() {
        val rows = listOf(
            Row(start - 40_000L, "+15559990000", RecordingDirection.INCOMING),
            Row(start - 3 * 24 * 60 * minute, "+15551230000", RecordingDirection.INCOMING),
        )
        assertEquals("+15559990000", LateNumberFromLog.pick(rows, RecordingDirection.INCOMING, start))
    }

    @Test
    fun `a blank number in the log confirms the call is anonymous`() {
        val rows = listOf(Row(start - 10_000L, "", RecordingDirection.INCOMING))
        assertEquals("", LateNumberFromLog.pick(rows, RecordingDirection.INCOMING, start))
    }

    @Test
    fun `only the same direction counts`() {
        val rows = listOf(Row(start - 5_000L, "+15550001111", RecordingDirection.OUTGOING))
        assertNull(LateNumberFromLog.pick(rows, RecordingDirection.INCOMING, start))
    }

    @Test
    fun `a call that began after this recording by more than clock jitter is the next call`() {
        val rows = listOf(Row(start + 2 * minute, "+15550002222", RecordingDirection.INCOMING))
        assertNull(LateNumberFromLog.pick(rows, RecordingDirection.INCOMING, start))
    }

    @Test
    fun `the read starts at the ring window before the recording`() {
        assertEquals(start - HonorCallStart.MAX_RING_MS, LateNumberFromLog.since(start))
    }
}
