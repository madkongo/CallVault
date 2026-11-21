/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C107: our own ADB daemon is the only thing that delivers through this provider (Shizuku's recorder is bound
 * by [ShizukuBackend]). One that lands while the user is in Shizuku mode is a late standalone launch, and must not
 * become the recorder, nor stay running to clear the Shizuku one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderBinderProviderModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(app)
    private val provider = Robolectric.buildContentProvider(RecorderBinderProvider::class.java).create().get()

    private lateinit var daemon: IRecorderService.Stub

    @Before
    fun setUp() {
        RecorderConnection.forceClear("test setup")
        daemon = mockk(relaxed = true)
        every { daemon.queryLocalInterface(any()) } returns daemon
        every { daemon.pingBinder() } returns true
        every { daemon.linkToDeath(any(), any()) } just runs
    }

    @After
    fun tearDown() {
        RecorderConnection.forceClear("test teardown")
        unmockkAll()
    }

    private fun deliver() {
        val extras = Bundle().apply { putParcelable(RecorderBinderProvider.EXTRA_BINDER, BinderContainer(daemon)) }
        provider.call(RecorderBinderProvider.METHOD_SEND_BINDER, null, extras)
    }

    @Test
    fun `a standalone daemon arriving in Shizuku mode is refused and told to exit`() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)

        deliver()

        assertFalse(RecorderConnection.isConnected)
        verify(exactly = 1) { daemon.destroy() }
    }

    @Test
    fun `a standalone daemon arriving in standalone mode is taken as before`() {
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)

        deliver()

        assertTrue(RecorderConnection.isConnected)
        verify(exactly = 0) { daemon.destroy() }
    }
}
