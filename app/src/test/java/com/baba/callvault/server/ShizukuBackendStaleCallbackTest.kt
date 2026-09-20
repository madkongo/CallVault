/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.Shizuku

/**
 * A binder that arrives for a binding that has since been stopped must be ignored.
 *
 * Shizuku's bind is asynchronous: the answer is posted to the main thread, and nothing stops a
 * [ShizukuBackend.stop] from landing between the bind and its answer. Measured on the OP9 on
 * 2026-09-20, after an install-over in Shizuku mode: app start bound the surviving pre-update service,
 * the post-update recovery removed it 13 ms later, and 4 ms after THAT the first bind's answer arrived
 * and put the stale process's binder into [RecorderConnection]. `ensureRunning` found "a recorder",
 * told it to clear the others, and the stale process killed the five fresh ones Shizuku started. The
 * next call recorded nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShizukuBackendStaleCallbackTest {

    private val liveBinder = mockk<IBinder>(relaxed = true) { every { pingBinder() } returns true }
    private val name = ComponentName("com.baba.callvault", "RecorderUserService")

    @Before
    fun setUp() {
        mockkStatic(Shizuku::class)
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.checkSelfPermission() } returns PackageManager.PERMISSION_GRANTED
        every { Shizuku.unbindUserService(any(), any(), any()) } returns Unit
        RecorderConnection.forceClear("test setup")
    }

    @After
    fun tearDown() {
        ShizukuBackend.stop(remove = true)
        RecorderConnection.forceClear("test teardown")
        unmockkAll()
    }

    private fun bindAndCapture(): ServiceConnection {
        val conn = slot<ServiceConnection>()
        every { Shizuku.bindUserService(any(), capture(conn)) } returns Unit
        assertTrue(ShizukuBackend.start())
        return conn.captured
    }

    @Test
    fun a_binder_answering_a_binding_that_was_stopped_is_not_accepted() {
        val stoppedBinding = bindAndCapture()
        ShizukuBackend.stop(remove = true)

        stoppedBinding.onServiceConnected(name, liveBinder)

        assertFalse(RecorderConnection.isConnected)
    }

    @Test
    fun a_binder_answering_the_current_binding_is_accepted() {
        val current = bindAndCapture()

        current.onServiceConnected(name, liveBinder)

        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun a_stopped_bindings_late_answer_does_not_displace_the_new_bindings_recorder() {
        val stoppedBinding = bindAndCapture()
        ShizukuBackend.stop(remove = true)
        val current = bindAndCapture()
        current.onServiceConnected(name, liveBinder)
        val fresh = RecorderConnection.service

        stoppedBinding.onServiceConnected(name, mockk<IBinder>(relaxed = true) { every { pingBinder() } returns true })

        assertTrue(RecorderConnection.service === fresh)
    }

    @Test
    fun a_stopped_bindings_disconnect_does_not_drop_the_new_bindings_recorder() {
        val stoppedBinding = bindAndCapture()
        ShizukuBackend.stop(remove = true)
        val current = bindAndCapture()
        current.onServiceConnected(name, liveBinder)

        stoppedBinding.onServiceDisconnected(name)

        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun a_bind_that_throws_does_not_leave_the_backend_believing_it_is_bound() {
        // The binding is claimed before the bind, so a failed bind has to give the claim back — or every
        // later start() answers "Already bound" about a bind that never happened.
        every { Shizuku.bindUserService(any(), any()) } throws IllegalStateException("binder is gone")
        assertFalse(ShizukuBackend.start())

        val retried = bindAndCapture()
        retried.onServiceConnected(name, liveBinder)

        assertTrue(RecorderConnection.isConnected)
    }
}
