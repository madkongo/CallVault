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

/** C108: how long the lookups before the capture takes is written to the always-on log, so a slow phone shows up in a report. */
class VoipStartTimingTest {

    @Test
    fun `the line carries the total and each lookup`() {
        assertEquals(
            "app call start: lookups before the capture opened took 340 ms (app 120 ms, caller 220 ms)",
            VoipStartTiming.line(appMs = 120L, callerMs = 220L),
        )
    }

    @Test
    fun `a negative clock step never prints a negative time`() {
        assertEquals(
            "app call start: lookups before the capture opened took 0 ms (app 0 ms, caller 0 ms)",
            VoipStartTiming.line(appMs = -5L, callerMs = -1L),
        )
    }
}
