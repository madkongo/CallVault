/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.app.Application
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.integrations.adb.AdbShell
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
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
import java.time.Duration

/**
 * Review batch B2: the recorder bring-up must not undo itself (C39), report a recorder it just dropped (C66), or let
 * a launch started in one mode serve another (C107). Each test is the sequence the finding describes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderBringUpGuardsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs = AppPreferences(app)

    private fun aliveBinder(): IBinder = mockk(relaxed = true) { every { isBinderAlive } returns true }

    private fun fakeRecorder(binder: IBinder = aliveBinder()): IRecorderService =
        mockk(relaxed = true) { every { asBinder() } returns binder }

    @Before
    fun setUp() {
        RecorderConnection.forceClear("test setup")
        mockkObject(AdbShell)
        mockkObject(ShizukuBackend)
        every { AdbShell.releaseWirelessDebugging(any(), any()) } just runs
        every { ShizukuBackend.isRunning() } returns false
        every { ShizukuBackend.hasPermission() } returns false
        every { ShizukuBackend.isInstalled(any()) } returns true
    }

    @After
    fun tearDown() {
        RecorderConnection.forceClear("test teardown")
        unmockkAll()
    }

    // ---- C39 ----

    @Test
    fun `a daemon that delivers its binder while the launch is getting ready is not killed by that launch`() {
        // Past the "recently booted" window, so the stale-daemon scan would normally run.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        assertTrue(SystemClock.elapsedRealtime() > 90_000L)
        every { AdbShell.ensureConnected(any()) } returns true
        // The binder of an earlier attempt's daemon lands while we wait for the shell.
        every { AdbShell.waitForShellReady(any(), any()) } answers {
            RecorderConnection.onBinderReceived(fakeRecorder())
            true
        }
        every { AdbShell.openShell(any(), any()) } throws IllegalStateException("no shell may be opened: the daemon is already up")

        val method = RecorderServerLauncher::class.java.getDeclaredMethod(
            "launchWithAttempts", android.content.Context::class.java, String::class.java, Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val ok = method.invoke(RecorderServerLauncher, app, "/data/app/base.apk", 1_000L) as Boolean

        assertTrue(ok)
        verify(exactly = 0) { AdbShell.openShell(any(), any()) }
    }

    // ---- C66 ----

    @Test
    fun `a Shizuku recorder whose binder died is not reported as running`() {
        prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
        val deadBinder = mockk<IBinder>(relaxed = true) { every { isBinderAlive } returns false }
        RecorderConnection.onBinderReceived(fakeRecorder(deadBinder))

        val up = RecorderBackend.ensureRunning(app)

        assertFalse("the dead recorder was dropped, so none is running", up)
        assertFalse(RecorderConnection.isConnected)
    }

    // ---- C107 ----

    @Test
    fun `a standalone launch that finishes after a switch to Shizuku does not clear the Shizuku recorder`() {
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)
        val shizukuRecorder = fakeRecorder()
        mockkObject(RecorderServerLauncher)
        every { RecorderServerLauncher.ensureServerRunning(any(), any()) } answers {
            // The user switches modes while the launch is still running; the Shizuku recorder is what is held.
            prefs.setPrivilegedMode(PrivilegedMode.SHIZUKU)
            RecorderConnection.onBinderReceived(shizukuRecorder)
            true
        }

        val up = RecorderBackend.ensureRunning(app)

        assertFalse("this launch served the mode the user left", up)
        verify(exactly = 0) { shizukuRecorder.killStaleRecorders() }
    }

    @Test
    fun `an ordinary standalone launch still clears leftovers and reports success`() {
        prefs.setPrivilegedMode(PrivilegedMode.STANDALONE)
        val recorder = fakeRecorder()
        mockkObject(RecorderServerLauncher)
        every { RecorderServerLauncher.ensureServerRunning(any(), any()) } answers {
            RecorderConnection.onBinderReceived(recorder)
            true
        }

        val up = RecorderBackend.ensureRunning(app)

        assertTrue(up)
        verify(exactly = 1) { recorder.killStaleRecorders() }
    }
}
