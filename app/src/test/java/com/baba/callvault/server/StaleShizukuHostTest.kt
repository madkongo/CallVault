/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.IBinder
import android.os.RemoteException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recorder the app is holding is asked which APK it is running from, and retired when that is not
 * the APK installed now.
 *
 * Two earlier fixes each closed one ORDER of events after an install-over, and a third order got past
 * both — measured on the OP9 on 2026-09-20 at 13:00: Shizuku delivers a binder by service name, not by
 * connection, so the pre-update process's binder arrived on the NEW binding 5 ms after the fresh process
 * was started, was accepted, and was then told to clear "the others". No amount of ordering logic can
 * win that. Asking the host who it is does not depend on the order at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StaleShizukuHostTest {

    private val installedApk = "/data/app/~~new==/com.baba.callvault-new==/base.apk"
    private val binder = mockk<IBinder>(relaxed = true) { every { isBinderAlive } returns true }

    private fun hostRunningFrom(apk: () -> String?) = mockk<IRecorderService>(relaxed = true) {
        every { asBinder() } returns binder
        every { hostApkPath() } answers { apk() }
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
    fun a_host_running_from_the_installed_apk_is_kept() {
        val host = hostRunningFrom { installedApk }
        RecorderConnection.onBinderReceived(host)

        assertFalse(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        verify(exactly = 0) { host.destroy() }
        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun a_host_running_from_an_apk_that_was_replaced_is_retired() {
        val host = hostRunningFrom { "/data/app/~~old==/com.baba.callvault-old==/base.apk" }
        RecorderConnection.onBinderReceived(host)

        assertTrue(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        verify { host.destroy() }
        assertFalse(RecorderConnection.isConnected)
    }

    @Test
    fun a_host_too_old_to_answer_the_question_is_retired() {
        // Every published build up to 2.4.0. It cannot say where it runs from, and a host that predates
        // the question predates the installed APK by definition.
        val host = hostRunningFrom { throw RemoteException("Method hostApkPath is unimplemented.") }
        RecorderConnection.onBinderReceived(host)

        assertTrue(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        assertFalse(RecorderConnection.isConnected)
    }

    @Test
    fun a_stale_host_that_is_recording_a_call_is_left_to_finish_it() {
        val host = hostRunningFrom { "/data/app/~~old==/com.baba.callvault-old==/base.apk" }
        every { host.isRecording } returns true
        RecorderConnection.onBinderReceived(host)

        assertFalse(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        verify(exactly = 0) { host.destroy() }
        assertTrue(RecorderConnection.isConnected)
    }

    @Test
    fun the_host_that_was_found_stale_is_the_one_destroyed_even_if_a_fresh_one_arrived_meanwhile() {
        // Nothing serialises this check against a bind callback. If the fresh recorder's binder lands
        // between the question and destroy(), destroying "whatever is current" kills the fresh process
        // and leaves the stale one alive — the very thing this check exists to stop.
        val fresh = mockk<IRecorderService>(relaxed = true) {
            every { asBinder() } returns mockk(relaxed = true) { every { isBinderAlive } returns true }
            every { hostApkPath() } returns installedApk
        }
        val stale = hostRunningFrom {
            RecorderConnection.onBinderReceived(fresh)
            "/data/app/~~old==/com.baba.callvault-old==/base.apk"
        }
        RecorderConnection.onBinderReceived(stale)

        assertTrue(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        verify { stale.destroy() }
        verify(exactly = 0) { fresh.destroy() }
    }

    @Test
    fun nothing_happens_when_no_recorder_is_held() {
        assertFalse(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))
    }

    @Test
    fun a_host_that_has_already_died_is_dropped_without_being_counted_as_stale() {
        // Seen on the OP9: the host retired a moment ago fails the question because it is dead, and was
        // logged as "a build too old to say" and retired a second time, spending the retry budget.
        every { binder.isBinderAlive } returns false
        val host = hostRunningFrom { throw RemoteException("dead") }
        RecorderConnection.onBinderReceived(host)

        assertFalse(RecorderBackend.retireIfStale(installedApk, timeoutMs = 0))

        verify(exactly = 0) { host.destroy() }
        assertFalse(RecorderConnection.isConnected)
    }
}
