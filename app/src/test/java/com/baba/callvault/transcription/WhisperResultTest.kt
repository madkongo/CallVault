/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** C42: whisper's return code used to be dropped, so a pass that decoded nothing counted as silence. */
class WhisperResultTest {

    @Test
    fun `zero is a finished pass`() {
        WhisperResult.check(rc = 0, aborted = false, pass = 1, passes = 3)
    }

    @Test
    fun `a failing code throws and names the pass and the code`() {
        try {
            WhisperResult.check(rc = -1, aborted = false, pass = 2, passes = 5)
            fail("a failed pass must not be stored as silence")
        } catch (e: WhisperResult.PassFailedException) {
            assertTrue(e.message, e.message!!.contains("2/5"))
            assertTrue(e.message, e.message!!.contains("-1"))
            assertEquals(-1, e.code)
        }
    }

    @Test
    fun `a stop is not a failure whatever the code`() {
        WhisperResult.check(rc = -6, aborted = true, pass = 1, passes = 1)
    }
}
