/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.services.recording.VoipRingListenerAccess
import com.baba.callvault.services.recording.VoipToggleEffects
import kotlinx.coroutines.CompletableDeferred
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review C19: a settings import that changes "Record app calls" re-syncs the ring listener at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportSyncsRingListenerTest {

    private lateinit var app: Application
    private lateinit var viewModel: SettingsViewModel

    private fun file(voipOn: Boolean) =
        """{"settings":{"voip_recording_enabled":{"type":"bool","value":$voipOn}}}"""

    @Before
    fun setUp() {
        mockkObject(VoipRingListenerAccess)
        every { VoipRingListenerAccess.requestSync(any()) } just runs
        // The toggle's own effects (persist, ring listener sync, detection start, arm) are tested in
        // VoipToggleEffectsTest; here only that an import runs them (review C96, which replaced the bare sync).
        mockkObject(VoipToggleEffects)
        every { VoipToggleEffects.apply(any<android.content.Context>(), any()) } returns CompletableDeferred(true)
        app = ApplicationProvider.getApplicationContext()
        viewModel = SettingsViewModel(app)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `an import that turns app calls on re-syncs the listener`() {
        AppPreferences(app).setVoipRecordingEnabled(false)
        viewModel.importSettings(file(voipOn = true))
        verify(exactly = 1) { VoipToggleEffects.apply(any<android.content.Context>(), true) }
    }

    @Test
    fun `an import that leaves app calls as they were does not`() {
        AppPreferences(app).setVoipRecordingEnabled(false)
        viewModel.importSettings(file(voipOn = false))
        verify(exactly = 0) { VoipToggleEffects.apply(any<android.content.Context>(), any()) }
    }

    @Test
    fun `a mode switch that changes app calls re-syncs the listener, and one that does not, does not`() {
        VoipRingListenerAccess.syncIfAffected(app, setOf(ModeCapability.RESILIENT_RECORDING))
        verify(exactly = 0) { VoipRingListenerAccess.requestSync(any()) }
        VoipRingListenerAccess.syncIfAffected(app, setOf(ModeCapability.VOIP_RECORDING))
        verify(exactly = 1) { VoipRingListenerAccess.requestSync(any()) }
    }
}
