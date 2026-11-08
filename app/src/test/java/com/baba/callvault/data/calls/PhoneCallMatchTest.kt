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

class PhoneCallMatchTest {

    private val t = 1_791_545_452_000L

    @Test
    fun `the nearest answered call within two minutes is the match`() {
        val calls = listOf(
            PhoneCall(t - 90_000, 30, CallKind.OUTGOING, accountId = "far"),
            PhoneCall(t + 3_000, 12, CallKind.INCOMING, accountId = "near"),
        )
        assertEquals("near", PhoneCallMatch.nearest(calls, t)?.accountId)
    }

    @Test
    fun `a missed call is never the call that was recorded`() {
        assertNull(PhoneCallMatch.nearest(listOf(PhoneCall(t, 0, CallKind.MISSED)), t))
    }

    @Test
    fun `nothing within two minutes is no match`() {
        assertNull(PhoneCallMatch.nearest(listOf(PhoneCall(t - 300_000, 5, CallKind.INCOMING)), t))
    }
}
