/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R7 step 3: the recorder grows the log buffer once per boot, not on every start. */
class LogcatGrowMarkerTest {

    private class Fs(var content: String? = null, var failWrite: Boolean = false, var failRead: Boolean = false) {
        fun read(): String? { if (failRead) error("read denied"); return content }
        fun write(s: String) { if (failWrite) error("write denied"); content = s }
    }

    private fun marker(fs: Fs, boot: String?) = LogcatGrowMarker(bootId = { boot }, read = fs::read, write = fs::write)

    @Test
    fun `the first start of a boot grows the buffer and leaves the marker`() {
        val fs = Fs()
        val m = marker(fs, "boot-1")
        assertTrue(m.shouldGrow())
        m.markGrown()
        assertEquals("boot-1", fs.content)
    }

    @Test
    fun `a later start in the same boot skips the grow`() {
        val fs = Fs(content = "boot-1")
        assertFalse(marker(fs, "boot-1").shouldGrow())
    }

    @Test
    fun `a new boot grows again`() {
        val fs = Fs(content = "boot-1")
        assertTrue(marker(fs, "boot-2").shouldGrow())
    }

    @Test
    fun `an unreadable boot id falls back to growing every time`() {
        val fs = Fs(content = "boot-1")
        val m = marker(fs, null)
        assertTrue(m.shouldGrow())
        m.markGrown()
        assertEquals("boot-1", fs.content)
    }

    @Test
    fun `a marker that cannot be read or written never stops the grow`() {
        assertTrue(marker(Fs(failRead = true), "b").shouldGrow())
        marker(Fs(failWrite = true), "b").markGrown() // must not throw
    }
}
