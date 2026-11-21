/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/** C37: a carrier capture that wrote nothing is reported once, and counted. */
class NoFileReportTest {

    @Test
    fun `a capture that wrote nothing posts the notice and a health row`() {
        assertEquals(
            NoFileReport.Plan(notify = true, recordHealth = true),
            NoFileReport.plan(capturedNothing = true, captureNeverStartedNotified = false),
        )
    }

    @Test
    fun `when the never-started notice already told the user only the health row is added`() {
        assertEquals(
            NoFileReport.Plan(notify = false, recordHealth = true),
            NoFileReport.plan(capturedNothing = true, captureNeverStartedNotified = true),
        )
    }

    @Test
    fun `a capture stopped within seconds is an ordinary short call, not a failure`() {
        assertEquals(
            NoFileReport.Plan(notify = false, recordHealth = false),
            NoFileReport.plan(capturedNothing = true, captureNeverStartedNotified = false, capturedMs = 1_200L),
        )
    }

    @Test
    fun `a call that kept a file reports nothing here`() {
        assertEquals(
            NoFileReport.Plan(notify = false, recordHealth = false),
            NoFileReport.plan(capturedNothing = false, captureNeverStartedNotified = false),
        )
    }
}
