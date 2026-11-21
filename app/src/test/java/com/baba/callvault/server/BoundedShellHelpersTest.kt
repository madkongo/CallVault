/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C67: the VoIP-start shell helpers read a child's output to the end before waiting with their timeout, so a stalled
 * `dumpsys` held the call-start thread for as long as it stalled. A stand-in shell that prints a line and then
 * never ends proves each helper now comes back at its bound.
 */
class BoundedShellHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A "shell" that ignores its arguments, says one line and then hangs. */
    private fun hangingShell(): String {
        val script = tmp.newFile("hang-sh")
        script.writeText("#!/bin/sh\necho partial\nexec sleep 30\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    private fun quickShell(): String {
        val script = tmp.newFile("quick-sh")
        script.writeText("#!/bin/sh\necho hello\n")
        script.setExecutable(true)
        return script.absolutePath
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - started) / 1_000_000
    }

    @Test(timeout = 20_000)
    fun `caller-name shell command returns at its bound when the child never ends`() {
        val (value, tookMs) = timed { VoipCallerName.shell("whatever", hangingShell(), timeoutMs = 700) }
        assertTrue("took $tookMs ms", tookMs < 6_000)
        assertNull("a cut-short notification dump is not used", value)
    }

    @Test(timeout = 20_000)
    fun `audio dump read returns at its bound when the child never ends`() {
        val (value, tookMs) = timed { VoipAppIdentity.readAudioDump(hangingShell(), timeoutMs = 700) }
        assertTrue("took $tookMs ms", tookMs < 6_000)
        assertNull("a cut-short audio dump could cut a uid in half, so it is not used", value)
    }

    @Test(timeout = 20_000)
    fun `both helpers still return a complete answer from a command that ends`() {
        val shell = quickShell()
        assertEquals("hello\n", VoipCallerName.shell("x", shell, timeoutMs = 5_000))
        assertEquals("hello\n", VoipAppIdentity.readAudioDump(shell, timeoutMs = 5_000))
    }

    @Test
    fun `runText gives null for a timeout and the text for a clean end`() {
        assertNull(BoundedProcess.runText(arrayOf(hangingShell()), 700, "t"))
        assertEquals("hello\n", BoundedProcess.runText(arrayOf(quickShell()), 5_000, "t"))
    }

    @Test
    fun `runText keeps the text of a command that exits non-zero`() {
        val script = tmp.newFile("fail-sh")
        script.writeText("#!/bin/sh\necho out\nexit 2\n")
        script.setExecutable(true)
        // dumpsys often prints its answer and exits non-zero; the answer is still the answer.
        assertEquals("out\n", BoundedProcess.runText(arrayOf(script.absolutePath), 5_000, "t"))
        assertTrue(File(script.absolutePath).exists())
    }
}
