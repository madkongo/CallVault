/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.transcription.LongTranscriptionWarning.Confirmation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTranscriptionWarningTest {

    private val thresholdMs = LongTranscriptionWarning.THRESHOLD_MINUTES * 60_000L
    private val eightyMinutesMs = 80L * 60_000L

    @Test
    fun `a local run below the threshold is not warned about`() {
        assertFalse(LongTranscriptionWarning.shouldWarn(isCloud = false, audioMs = thresholdMs - 1))
    }

    @Test
    fun `a local run at the threshold is warned about`() {
        assertTrue(LongTranscriptionWarning.shouldWarn(isCloud = false, audioMs = thresholdMs))
    }

    @Test
    fun `a local run above the threshold is warned about`() {
        assertTrue(LongTranscriptionWarning.shouldWarn(isCloud = false, audioMs = eightyMinutesMs))
    }

    @Test
    fun `a cloud run is never warned about`() {
        assertFalse(LongTranscriptionWarning.shouldWarn(isCloud = true, audioMs = thresholdMs - 1))
        assertFalse(LongTranscriptionWarning.shouldWarn(isCloud = true, audioMs = thresholdMs))
        assertFalse(LongTranscriptionWarning.shouldWarn(isCloud = true, audioMs = eightyMinutesMs))
    }

    @Test
    fun `an unknown length is not warned about`() {
        assertFalse(LongTranscriptionWarning.shouldWarn(isCloud = false, audioMs = 0L))
    }

    @Test
    fun `without a measured speed the estimate is one and a half times the call`() {
        assertEquals(120L * 60_000L, LongTranscriptionWarning.estimateMs(eightyMinutesMs, measuredEstimateMs = null))
    }

    @Test
    fun `a measured estimate is quoted as it is`() {
        val measured = 100L * 60_000L
        assertEquals(measured, LongTranscriptionWarning.estimateMs(eightyMinutesMs, measuredEstimateMs = measured))
    }

    @Test
    fun `a zero measured estimate falls back to the default factor`() {
        assertEquals(120L * 60_000L, LongTranscriptionWarning.estimateMs(eightyMinutesMs, measuredEstimateMs = 0L))
    }

    @Test
    fun `the slowdown is the ratio to one decimal`() {
        assertEquals(1.5, LongTranscriptionWarning.slowdown(eightyMinutesMs, 120L * 60_000L), 0.0)
        // The real OP9 Pro call: 57:45 took 92 min.
        assertEquals(1.6, LongTranscriptionWarning.slowdown(3_465_000L, 92L * 60_000L), 0.0)
    }

    @Test
    fun `the slowdown of an unknown length is the default`() {
        assertEquals(LongTranscriptionWarning.DEFAULT_SLOWDOWN, LongTranscriptionWarning.slowdown(0L, 1_000L), 0.0)
    }

    @Test
    fun `a cloud run never asks`() {
        listOf(true, false).forEach { flag ->
            assertEquals(
                Confirmation.NONE,
                LongTranscriptionWarning.confirmation(
                    isCloud = true, isImport = flag, confirmBeforeRun = !flag, audioMs = eightyMinutesMs,
                ),
            )
        }
    }

    @Test
    fun `with the notice on, a local run asks in full whatever its length`() {
        assertEquals(Confirmation.FULL, confirm(confirmBeforeRun = true, audioMs = 60_000L))
        assertEquals(Confirmation.FULL, confirm(confirmBeforeRun = true, audioMs = eightyMinutesMs))
    }

    @Test
    fun `an import always asks in full`() {
        assertEquals(Confirmation.FULL, confirm(isImport = true, confirmBeforeRun = false, audioMs = 60_000L))
    }

    @Test
    fun `with the notice turned off, a long call still gets the long-call line`() {
        assertEquals(Confirmation.LONG_CALL_ONLY, confirm(confirmBeforeRun = false, audioMs = thresholdMs))
    }

    @Test
    fun `with the notice turned off, a short call starts straight away`() {
        assertEquals(Confirmation.NONE, confirm(confirmBeforeRun = false, audioMs = thresholdMs - 1))
        assertEquals(Confirmation.NONE, confirm(confirmBeforeRun = false, audioMs = 0L))
    }

    private fun confirm(isImport: Boolean = false, confirmBeforeRun: Boolean, audioMs: Long) =
        LongTranscriptionWarning.confirmation(
            isCloud = false, isImport = isImport, confirmBeforeRun = confirmBeforeRun, audioMs = audioMs,
        )
}
