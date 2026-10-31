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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BoundedEntryLockTest {

    private val busy = "busy"

    @Test
    fun `runs the block when nobody holds the lock`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS)

        val result = lock.withLockOrElse(onBusy = { busy }) { "ran" }

        assertEquals("ran", result)
        assertFalse("released afterwards", lock.isHeldByCurrentThread)
    }

    @Test
    fun `is re-entrant for the thread already holding it`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS)

        val result = lock.withLockOrElse(onBusy = { busy }) {
            lock.withLockOrElse(onBusy = { busy }) { "inner" }
        }

        assertEquals("inner", result)
    }

    @Test
    fun `a caller behind a parked holder gives up after the entry wait instead of queueing forever`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS)
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            lock.withLockOrElse(onBusy = { busy }) { parked.countDown(); release.await(); "held" }
        }.apply { isDaemon = true; name = "cv-parked-holder"; start() }
        assertTrue(parked.await(SLACK_MS, TimeUnit.MILLISECONDS))
        val seenHolder = AtomicReference<String?>()

        val startedAt = System.nanoTime()
        val result = lock.withLockOrElse(onBusy = { seenHolder.set(it); busy }) { "ran" }
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals(busy, result)
        assertEquals("cv-parked-holder", seenHolder.get())
        assertTrue("waited the entry bound ($waitedMs ms)", waitedMs >= ENTRY_WAIT_MS - 5)
        assertTrue("did not wait much past it ($waitedMs ms)", waitedMs < ENTRY_WAIT_MS + SLACK_MS)
        release.countDown()
        holder.join(SLACK_MS)
    }

    @Test
    fun `a caller behind a holder that finishes in time waits and then runs, as a monitor would`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS * 10)
        val parked = CountDownLatch(1)
        val holder = Thread {
            lock.withLockOrElse(onBusy = { busy }) { parked.countDown(); Thread.sleep(ENTRY_WAIT_MS); "held" }
        }.apply { isDaemon = true; start() }
        assertTrue(parked.await(SLACK_MS, TimeUnit.MILLISECONDS))

        val result = lock.withLockOrElse(onBusy = { busy }) { "ran" }

        assertEquals("ran", result)
        holder.join(SLACK_MS)
    }

    @Test
    fun `an interrupted thread still takes a free lock, as it could take a monitor`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS)
        Thread.currentThread().interrupt()
        try {
            val result = lock.withLockOrElse(onBusy = { busy }) { "ran" }

            assertEquals("ran", result)
        } finally {
            Thread.interrupted() // clear the flag so it cannot leak into other tests
        }
    }

    @Test
    fun `an interrupt while waiting counts as busy and keeps the interrupt flag`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS * 100)
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        Thread {
            lock.withLockOrElse(onBusy = { busy }) { parked.countDown(); release.await(); "held" }
        }.apply { isDaemon = true; start() }
        assertTrue(parked.await(SLACK_MS, TimeUnit.MILLISECONDS))
        val result = AtomicReference<String>()
        val flagKept = AtomicReference<Boolean>()
        val waiter = Thread {
            result.set(lock.withLockOrElse(onBusy = { busy }) { "ran" })
            flagKept.set(Thread.currentThread().isInterrupted)
        }.apply { isDaemon = true; start() }

        Thread.sleep(50)
        waiter.interrupt()
        waiter.join(SLACK_MS)

        assertEquals(busy, result.get())
        assertEquals(true, flagKept.get())
        release.countDown()
    }

    @Test
    fun `the lock is released when the block throws`() {
        val lock = BoundedEntryLock(ENTRY_WAIT_MS)

        runCatching { lock.withLockOrElse<String>(onBusy = { busy }) { error("boom") } }
        val other = AtomicReference<String>()
        Thread { other.set(lock.withLockOrElse(onBusy = { busy }) { "ran" }) }.apply { start(); join(SLACK_MS) }

        assertEquals("ran", other.get())
    }

    private companion object {
        const val ENTRY_WAIT_MS = 200L
        const val SLACK_MS = 2_000L
    }
}
