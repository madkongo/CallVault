/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import com.baba.callvault.transcription.TranscriptionRunGate.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptionRunGateTest {

    @Before
    fun reset() = TranscriptionRunGate.reset()

    @Test
    fun `an after-call run waits while a tapped run is going`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.TAP))
        assertFalse(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
    }

    @Test
    fun `an after-call run waits while another after-call run is going`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
        assertFalse(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
    }

    @Test
    fun `a tap waits for a running after-call run`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
        assertFalse(TranscriptionRunGate.tryEnter(Kind.TAP))
    }

    @Test
    fun `a tap never waits for the sweep, as before`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.SWEEP))
        assertTrue(TranscriptionRunGate.tryEnter(Kind.TAP))
    }

    @Test
    fun `the sweep is never refused`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
        assertTrue(TranscriptionRunGate.tryEnter(Kind.SWEEP))
    }

    @Test
    fun `leaving frees the gate`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
        TranscriptionRunGate.leave(Kind.AFTER_CALL)
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
    }

    @Test
    fun `leaving twice cannot make the count negative`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.TAP))
        TranscriptionRunGate.leave(Kind.TAP)
        TranscriptionRunGate.leave(Kind.TAP)
        assertTrue(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
        assertFalse(TranscriptionRunGate.tryEnter(Kind.AFTER_CALL))
    }

    @Test
    fun `a lone run owns the engine, two do not`() {
        assertTrue(TranscriptionRunGate.tryEnter(Kind.SWEEP))
        assertTrue(TranscriptionRunGate.isOnlyRun())
        assertTrue(TranscriptionRunGate.tryEnter(Kind.TAP))
        assertFalse(TranscriptionRunGate.isOnlyRun())
    }
}
