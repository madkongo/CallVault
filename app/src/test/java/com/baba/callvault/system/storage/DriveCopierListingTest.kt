/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C11: when the `:drive` copier cannot list, the sweep starts a fresh copier and asks again, a bounded
 * number of times, and never moves the Drive work into the app's own (recording) process.
 */
class DriveCopierListingTest {

    private class FakeCopier(val n: Int) : DriveCopier {
        var closed = false
        override val where = "fake copier #$n"
        override suspend fun call(what: Int, data: Bundle, timeoutMs: Long) = error("not used: tryList is injected")
        override fun close() { closed = true }
    }

    private val listing = DriveCopyProtocol.Listing(listOf("a.ogg"), emptyList(), listOf(1L))
    private val opened = ArrayList<FakeCopier>()
    private val journal = ArrayList<String>()
    private fun open(): DriveCopier = FakeCopier(opened.size + 1).also { opened += it }

    /** The `:drive` pid as the system reports it: a new process per copier unless a test says otherwise. */
    private var pidOf: () -> Int? = { opened.lastOrNull()?.takeIf { !it.closed }?.let { 1000 + it.n } }

    private var inCall = false

    private fun run(succeedOn: Int?) = runBlocking {
        DriveCopierListing.list(
            ::open, copierPid = { pidOf() }, inCall = { inCall }, exitWaitMs = 50L, pollMs = 5L, journal = { journal += it },
        ) { copier ->
            if ((copier as FakeCopier).n == succeedOn) listing to "" else null to "${copier.where} died"
        }
    }

    @Test
    fun `a copier that fails to list is closed and a fresh one is asked`() {
        val out = run(succeedOn = 3)

        assertSame(listing, out.listing)
        assertEquals(3, opened.size)
        assertSame(opened[2], out.copier) // the copies of this run go through the copier that listed
        assertTrue(opened[0].closed && opened[1].closed)
        assertEquals(listOf("fake copier #1 died", "fake copier #2 died"), out.failures)
    }

    @Test
    fun `a copier that never lists is given up on after a bounded number of fresh starts`() {
        val out = run(succeedOn = null)

        assertNull(out.listing)
        assertEquals(DriveCopierListing.ATTEMPTS_PER_RUN, opened.size)
        assertTrue(opened.all { it.closed })
        assertEquals(DriveCopierListing.ATTEMPTS_PER_RUN, journal.count { it.contains("FAILED") })
    }

    @Test
    fun `a copier that lists at once is the only one started`() {
        val out = run(succeedOn = 1)

        assertNotNull(out.listing)
        assertEquals(1, opened.size)
        assertTrue(out.failures.isEmpty())
    }

    @Test
    fun `a copier process another client still holds is not restarted, and the run stops asking`() {
        pidOf = { 4242 } // the same :drive process before and after letting go: the after-call copy holds it

        val out = run(succeedOn = 2)

        assertNull(out.listing)
        assertEquals("no pointless second attempt on the same wedged process", 1, opened.size)
        assertTrue(journal.joinToString("\n"), journal.any { it.contains("still running") && it.contains("4242") })
    }

    @Test
    fun `a copier process that has already gone counts as restarted`() {
        pidOf = { null } // no :drive process left after the close

        val out = run(succeedOn = 2)

        assertNotNull(out.listing)
        assertEquals(2, opened.size)
    }

    @Test
    fun `no copier is restarted while a call is in progress`() {
        inCall = true

        val out = run(succeedOn = 2)

        assertNull(out.listing)
        assertEquals("the first listing runs, the restarts wait for the call to end", 1, opened.size)
        assertTrue(journal.joinToString("\n"), journal.any { it.contains("DEFERRED") && it.contains("call") })
    }

    @Test
    fun `there is no in-app copier any more`() {
        val gone = runCatching { Class.forName("com.baba.callvault.system.storage.LocalDriveCopier") }
        assertTrue("LocalDriveCopier must not exist: Drive work never runs in the recording process", gone.isFailure)
    }
}
