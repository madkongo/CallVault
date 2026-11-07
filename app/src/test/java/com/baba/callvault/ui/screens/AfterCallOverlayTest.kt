/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import android.os.Looper
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.services.recording.aftercall.AfterCallDecisions
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The pop-up window's way in and out: it goes away by itself when nothing is waiting any more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AfterCallOverlayTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"

    @Before
    fun setUp() {
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
    }

    @After
    fun tearDown() {
        AfterCallOverlay.close()
        idle()
    }

    @Test
    fun `it opens over a waiting recording and closes once that is answered`() {
        AfterCallDecisions.hold(context, name, "content://test/$name".toUri(), "audio/ogg", isVoip = false)

        AfterCallOverlay.show(context)
        idle()
        assertTrue(AfterCallOverlay.isShowing)

        AfterCallDecisions.release(context, name)
        waitUntil { !AfterCallOverlay.isShowing }
        assertFalse(AfterCallOverlay.isShowing)
    }

    @Test
    fun `it does not stay up when nothing is waiting`() {
        AfterCallOverlay.show(context)
        waitUntil { !AfterCallOverlay.isShowing }
        assertFalse(AfterCallOverlay.isShowing)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The refresh reads on Dispatchers.IO and lands on the main looper; give it a bounded moment. */
    private fun waitUntil(condition: () -> Boolean) {
        repeat(50) {
            idle()
            if (condition()) return
            Thread.sleep(20)
        }
    }
}
