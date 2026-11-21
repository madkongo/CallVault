/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C146: the copier process used to kill itself whenever the app unbound, filling Android's 16-entry exit history.
 * It now ends the process only when it is dropping work (a copy still going, or messages still queued).
 */
class DriveCopyInFlightTest {

    @Test
    fun `an idle copier does not end its process when the app lets go`() {
        assertFalse(DriveCopyInFlight().shouldKillOnDestroy())
    }

    @Test
    fun `a copy still going when the app lets go is abandoned by ending the process`() {
        val w = DriveCopyInFlight()
        w.received()
        assertTrue(w.shouldKillOnDestroy())
    }

    @Test
    fun `after the reply is sent the process is idle again`() {
        val w = DriveCopyInFlight()
        w.received()
        w.replied()
        assertFalse(w.shouldKillOnDestroy())
    }

    @Test
    fun `queued messages count as work`() {
        val w = DriveCopyInFlight()
        w.received(); w.received()
        w.replied()
        assertEquals(1, w.count())
        assertTrue(w.shouldKillOnDestroy())
    }

    @Test
    fun `a stray reply never drives the count below zero`() {
        val w = DriveCopyInFlight()
        w.replied()
        w.received()
        assertTrue(w.shouldKillOnDestroy())
    }
}
