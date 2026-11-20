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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dumps read the whole output before waiting with a timeout, so the timeout never fired: a wedged logd held
 * the recorder's binder thread for good. These run real processes on the host's /bin/sh.
 */
class BoundedProcessTest {

    private fun text(stream: java.io.InputStream) = stream.bufferedReader().readText()

    @Test
    fun `a process that never ends is stopped at the timeout and returns what it said so far`() {
        val started = System.nanoTime()
        val outcome = BoundedProcess.run(arrayOf("/bin/sh", "-c", "echo one; exec sleep 30"), timeoutMs = 1_000, read = ::text)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 5_000)
        assertTrue(outcome.timedOut)
        assertEquals("one\n", outcome.value)
        assertTrue(outcome.partial!!.contains("timed out"))
    }

    @Test
    fun `a reader stuck on a pipe a grandchild still holds does not hold the caller`() {
        // Killing the shell leaves `sleep` holding the pipe; the caller must come back anyway.
        val started = System.nanoTime()
        val outcome = BoundedProcess.run(arrayOf("/bin/sh", "-c", "echo one; sleep 30; echo two"), timeoutMs = 1_000, read = ::text)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 5_000)
        assertTrue(outcome.timedOut)
        // The JVM ends the read when the shell exits; a runtime that does not leaves the reader behind and gives
        // no value. Either way the caller is back on time, which is the point.
        assertTrue(outcome.value == null || outcome.value == "one\n")
    }

    @Test
    fun `an early exit is reported with its status`() {
        val outcome = BoundedProcess.run(arrayOf("/bin/sh", "-c", "echo a; exit 3"), timeoutMs = 5_000, read = ::text)
        assertFalse(outcome.timedOut)
        assertEquals("a\n", outcome.value)
        assertEquals(3, outcome.exitCode)
        assertTrue(outcome.partial!!.contains("3"))
    }

    @Test
    fun `a clean run is not partial`() {
        val outcome = BoundedProcess.run(arrayOf("/bin/sh", "-c", "echo ok"), timeoutMs = 5_000, read = ::text)
        assertEquals("ok\n", outcome.value)
        assertEquals(0, outcome.exitCode)
        assertNull(outcome.partial)
    }
}
