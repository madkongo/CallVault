/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review C171: the interrupt that unstuck one call's stop must not leak into the next job on the recorder's worker. */
class InterruptSafeJobTest {

    @Test
    fun a_stray_interrupt_is_gone_when_the_next_job_starts() {
        var interruptedAtStart = true
        val job = InterruptSafeJob.wrap { interruptedAtStart = Thread.currentThread().isInterrupted }

        Thread.currentThread().interrupt()   // the previous job's interrupt, landing after that job had finished
        try {
            job.run()
        } finally {
            Thread.interrupted()             // never leave the flag on the test thread
        }
        assertFalse(interruptedAtStart)
    }

    @Test
    fun the_job_itself_still_runs_and_can_be_interrupted_afterwards() {
        var ran = false
        var interruptedAfter = false
        InterruptSafeJob.wrap {
            ran = true
            Thread.currentThread().interrupt()   // an interrupt aimed at THIS job is still honoured
            interruptedAfter = Thread.currentThread().isInterrupted
        }.run()
        Thread.interrupted()
        assertTrue(ran)
        assertTrue(interruptedAfter)
    }
}
