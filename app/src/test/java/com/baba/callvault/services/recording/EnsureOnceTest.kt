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

/** Review N22 follow-up: the recording channels are created once per process, and again only when found missing. */
class EnsureOnceTest {

    @Test
    fun `the work runs once however often it is asked for`() {
        val once = EnsureOnce()
        var runs = 0
        repeat(3) { once.run(force = false) { runs++; true } }
        assertEquals(1, runs)
    }

    @Test
    fun `a forced run repeats the work after it has succeeded`() {
        val once = EnsureOnce()
        var runs = 0
        once.run(force = false) { runs++; true }
        once.run(force = true) { runs++; true }
        assertEquals(2, runs)
    }

    @Test
    fun `a failed run is tried again next time`() {
        val once = EnsureOnce()
        var runs = 0
        once.run(force = false) { runs++; false }
        once.run(force = false) { runs++; true }
        once.run(force = false) { runs++; true }
        assertEquals(2, runs)
    }
}
