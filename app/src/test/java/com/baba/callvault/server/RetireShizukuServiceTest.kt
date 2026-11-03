/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retiring the Shizuku-hosted recorder must leave the app holding NO recorder, whatever Shizuku does.
 *
 * `ShizukuBackend.stop(remove = true)` only asks Shizuku to destroy the service, and on the OP9 it does
 * not: the process lives on, its binder stays alive, and [RecorderConnection.onBinderDied] — rightly,
 * for its own case — refuses to drop a live binder. Measured on 2026-09-20 after an install-over: the
 * post-update recovery "stopped" the stale pre-update service, `ensureRunning` answered "already
 * connected; reusing existing binder" 1 ms later about that very service, and it then killed the fresh
 * process Shizuku had started. The next call would have recorded nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetireShizukuServiceTest {

    /** A host whose process outlives everything asked of it, which is what the OP9's did. */
    private val stubbornBinder = mockk<IBinder>(relaxed = true) { every { isBinderAlive } returns true }
    private val stubbornService = mockk<IRecorderService>(relaxed = true) {
        every { asBinder() } returns stubbornBinder
    }

    @Before
    fun setUp() {
        mockkObject(ShizukuBackend)
        every { ShizukuBackend.stop(any()) } answers { RecorderConnection.onBinderDied() }
        RecorderConnection.forceClear("test setup")
    }

    @After
    fun tearDown() {
        RecorderConnection.forceClear("test teardown")
        unmockkAll()
    }

    @Test
    fun a_service_that_survives_the_stop_is_not_left_connected() {
        RecorderConnection.onBinderReceived(stubbornService)

        RecorderBackend.retireShizukuService("test", timeoutMs = 0)

        assertFalse(RecorderConnection.isConnected)
    }

    @Test
    fun the_service_is_asked_to_exit_itself_before_shizuku_is_asked_to_remove_it() {
        RecorderConnection.onBinderReceived(stubbornService)

        RecorderBackend.retireShizukuService("test", timeoutMs = 0)

        verifyOrder {
            stubbornService.destroy()
            ShizukuBackend.stop(true)
        }
    }

    @Test
    fun shizuku_is_still_asked_to_remove_a_service_this_process_never_connected_to() {
        RecorderBackend.retireShizukuService("test", timeoutMs = 0)

        verify { ShizukuBackend.stop(true) }
        assertFalse(RecorderConnection.isConnected)
    }

    @Test
    fun a_service_that_is_recording_a_call_is_left_alone() {
        // A daemon(true) service survives the install and may be mid-call when it lands. destroy()
        // stops the recording and exits the process; a stale host is a problem for the NEXT call, and
        // ending this one to fix it would trade a possible loss for a certain one.
        every { stubbornService.isRecording } returns true
        RecorderConnection.onBinderReceived(stubbornService)

        val retired = RecorderBackend.retireShizukuService("test", timeoutMs = 0)

        assertFalse(retired)
        verify(exactly = 0) { stubbornService.destroy() }
        verify(exactly = 0) { ShizukuBackend.stop(any()) }
        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun a_retire_that_waited_behind_another_leaves_the_fresh_recorder_alone() {
        // After an install-over the update receiver named the old host, then another caller replaced it.
        // Retiring "the old host" must not stop the binding that now holds the fresh one (OP9, 2026-11-03).
        val deadOld = mockk<IRecorderService>(relaxed = true) {
            every { asBinder() } returns mockk<IBinder>(relaxed = true) { every { isBinderAlive } returns false }
        }
        RecorderConnection.onBinderReceived(stubbornService)

        val retired = RecorderBackend.retireShizukuService("test", timeoutMs = 0, host = deadOld)

        assertTrue(retired)
        verify(exactly = 0) { ShizukuBackend.stop(any()) }
        verify(exactly = 0) { stubbornService.destroy() }
        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun two_retires_never_run_at_the_same_time() {
        // Overlapping retire-and-bind is what made Shizuku lose track of the recorder after an update.
        val inside = java.util.concurrent.atomic.AtomicInteger(0)
        val overlapped = java.util.concurrent.atomic.AtomicBoolean(false)
        every { ShizukuBackend.stop(any()) } answers {
            if (inside.incrementAndGet() > 1) overlapped.set(true)
            Thread.sleep(50)
            inside.decrementAndGet()
            RecorderConnection.onBinderDied()
        }
        val threads = List(4) { Thread { RecorderBackend.retireShizukuService("test", timeoutMs = 0, host = null) } }

        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertFalse(overlapped.get())
        verify(exactly = 4) { ShizukuBackend.stop(true) }
    }
}
