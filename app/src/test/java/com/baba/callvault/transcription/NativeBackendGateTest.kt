/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C83: the two native libraries must never register ggml's backends at the same time. */
class NativeBackendGateTest {

    @Test
    fun `two engines loading at once never overlap`() {
        val gate = NativeBackendGate()
        val inside = AtomicInteger(0)
        val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val load = {
            if (inside.incrementAndGet() > 1) overlapped.set(true)
            Thread.sleep(100)
            inside.decrementAndGet()
            Unit
        }
        val a = pool.submit { start.await(); gate.ensureLoaded("whisper", load) }
        val b = pool.submit { start.await(); gate.ensureLoaded("llama", load) }
        start.countDown()
        a.get(5, TimeUnit.SECONDS)
        b.get(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertFalse("both engines were inside the backend load together", overlapped.get())
    }

    @Test
    fun `an engine's load runs once`() {
        val gate = NativeBackendGate()
        var calls = 0
        repeat(3) { gate.ensureLoaded("whisper") { calls++ } }
        assertEquals(1, calls)
    }

    @Test
    fun `a failed load is tried again next time`() {
        val gate = NativeBackendGate()
        var calls = 0
        val first = runCatching { gate.ensureLoaded("llama") { calls++; error("no lib") } }
        assertTrue(first.isFailure)
        gate.ensureLoaded("llama") { calls++ }
        assertEquals(2, calls)
    }
}
