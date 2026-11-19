/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Review N17 second pass: with the start toast deferred to the hold's release, a user who pressed Resume while the
 * call was held for the answer cancelled the hold loop, and "Recording started" was never shown. Resume now says it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoipHoldUserResumeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun set(name: String, value: Any?) {
        VoipRecordingCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private fun drainMainLooper() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        AppPreferences(context).setShowToastsEnabled(true)
    }

    @After
    fun tearDown() {
        set("recording", false)
        set("heldForAnswer", false)
        set("answerHoldJob", null)
        set("paused", false)
    }

    @Test
    fun `resume pressed while held for the answer says recording started`() {
        set("recording", true)
        set("heldForAnswer", true)
        set("answerHoldJob", Job())

        VoipRecordingCoordinator.setPaused(context, false)
        drainMainLooper()

        assertEquals(context.getString(R.string.recording_started), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `resume pressed when nothing was held says nothing new`() {
        set("recording", true)
        set("heldForAnswer", false)
        set("answerHoldJob", null)

        VoipRecordingCoordinator.setPaused(context, false)
        drainMainLooper()

        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
