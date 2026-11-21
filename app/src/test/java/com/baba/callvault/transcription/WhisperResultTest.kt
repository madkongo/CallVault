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

    @Test
    fun `a VAD failure is retried once without VAD and the transcript survives`() {
        val seen = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        val used = WhisperResult.runPass("vad.bin", { false }, 1, 2, { logs += it }) { vad ->
            seen += vad
            if (vad != null) -1 else 0
        }
        assertEquals(listOf<String?>("vad.bin", null), seen)
        assertEquals(null, used)
        assertEquals(1, logs.size)
    }

    @Test
    fun `a failed retry throws`() {
        try {
            WhisperResult.runPass("vad.bin", { false }, 1, 1, {}) { -1 }
            fail("both decodes failed")
        } catch (e: WhisperResult.PassFailedException) {
            assertEquals(-1, e.code)
        }
    }

    @Test
    fun `a clean pass keeps its VAD and decodes once`() {
        var calls = 0
        val used = WhisperResult.runPass("vad.bin", { false }, 1, 1, {}) { calls++; 0 }
        assertEquals("vad.bin", used)
        assertEquals(1, calls)
    }

    @Test
    fun `another failing code is not retried`() {
        var calls = 0
        try {
            WhisperResult.runPass("vad.bin", { false }, 1, 1, {}) { calls++; -6 }
            fail("must throw")
        } catch (e: WhisperResult.PassFailedException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `a stop with minus one is neither retried nor thrown`() {
        var calls = 0
        WhisperResult.runPass("vad.bin", { true }, 1, 1, {}) { calls++; -1 }
        assertEquals(1, calls)
    }
}
