/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The two ways libadb 3.1.1 ends a read badly: an exception that drops data, and a wait that never ends. */
class ShellOutputReadTest {

    /** Delivers [chunks] one per read, then [ending]. */
    private class FakeStream(private val chunks: List<String>, private val ending: () -> Int) : InputStream() {
        private var i = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i >= chunks.size) return ending()
            val bytes = chunks[i++].toByteArray()
            System.arraycopy(bytes, 0, b, off, bytes.size)
            return bytes.size
        }
    }

    private val token = "cv_shell_ok"

    @Test
    fun `token followed by Stream closed still counts as seen`() {
        val sink = StringBuffer()
        val stream = FakeStream(listOf("$token\n")) { throw IOException("Stream closed.") }

        val stopped = ShellOutputRead.readInto(stream, sink) { it.contains(token) }

        assertTrue(stopped)
        assertEquals("$token\n", sink.toString())
    }

    @Test
    fun `token followed by a read that never ends returns without waiting for the end`() {
        val sink = StringBuffer()
        val never = CountDownLatch(1)
        val stream = FakeStream(listOf("$token\n")) { never.await(); -1 }
        var result: Boolean? = null
        val t = Thread { result = ShellOutputRead.readInto(stream, sink) { it.contains(token) } }
        t.isDaemon = true
        t.start()
        t.join(2_000)

        val finished = !t.isAlive
        never.countDown()
        assertTrue("the read must stop at the token, not wait for end-of-file", finished)
        assertEquals(true, result)
    }

    @Test
    fun `token split across two reads is found`() {
        val sink = StringBuffer()
        val stream = FakeStream(listOf("cv_sh", "ell_ok\n")) { -1 }

        assertTrue(ShellOutputRead.readInto(stream, sink) { it.contains(token) })
    }

    @Test
    fun `a dead shell that never echoes the token is not ready`() {
        val sink = StringBuffer()
        val stream = FakeStream(listOf("some banner\n")) { -1 }

        assertFalse(ShellOutputRead.readInto(stream, sink) { it.contains(token) })
    }

    @Test
    fun `an exception with no data is not ready`() {
        val sink = StringBuffer()
        val stream = FakeStream(emptyList()) { throw IOException("Stream closed.") }

        assertFalse(ShellOutputRead.readInto(stream, sink) { it.contains(token) })
        assertEquals("", sink.toString())
    }

    @Test
    fun `refusal text delivered before Stream closed is kept for the grant read`() {
        val sink = StringBuffer()
        val stream = FakeStream(listOf("java.lang.SecurityException: grant not allowed\n")) { throw IOException("Stream closed.") }

        ShellOutputRead.readInto(stream, sink)

        assertTrue(sink.toString().contains("SecurityException"))
    }

    @Test
    fun `plain end of file returns everything read`() {
        val sink = StringBuffer()
        val stream = FakeStream(listOf("a", "b")) { -1 }

        assertFalse(ShellOutputRead.readInto(stream, sink))
        assertEquals("ab", sink.toString())
        // Latch unused here; keeps the import honest for the timing tests above.
        assertTrue(CountDownLatch(0).await(1, TimeUnit.MILLISECONDS))
    }
}
