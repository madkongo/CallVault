/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

import com.baba.callvault.data.health.CallGapDetector
import com.baba.callvault.data.health.CallLogEntry
import com.baba.callvault.services.recording.RecordingPolicy.CarrierAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C113: only a skip the user's settings chose is noted as seen; a recording that failed stays a gap. */
class ExcludedCallPolicyTest {

    @Test
    fun `an offered call, which the user's rules kept from auto-recording, is a deliberate skip`() {
        assertTrue(ExcludedCallPolicy.isDeliberateSkip(CarrierAction.OFFER))
    }

    @Test
    fun `a call that was to be recorded is never a deliberate skip`() {
        assertFalse(ExcludedCallPolicy.isDeliberateSkip(CarrierAction.RECORD))
    }

    @Test
    fun `phone-call recording off is a deliberate skip the sweep already ignores`() {
        assertFalse(ExcludedCallPolicy.isDeliberateSkip(CarrierAction.IGNORE))
    }

    @Test
    fun `a skipped call that was noted as seen is not a gap, an unnoted one still is`() {
        val callStart = 10_000_000L
        val entry = CallLogEntry(callStart, durationSeconds = 60, isIncoming = true, label = null)
        val endedAt = callStart + 60_000L + 2_000L
        fun sweep(observed: List<Long>) = CallGapDetector.sweep(
            entries = listOf(entry), observedCallEnds = observed,
            autoRecordIncoming = true, autoRecordOutgoing = true,
            watermark = 0L, ringCapacity = 25, windowStart = 1L,
        ).gaps
        assertEquals(0, sweep(listOf(endedAt)).size)
        assertEquals(1, sweep(emptyList()).size)
    }
}
