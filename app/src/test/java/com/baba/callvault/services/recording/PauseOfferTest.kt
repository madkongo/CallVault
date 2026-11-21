/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C44: the notification offers Pause only where pausing really stops the audio. In the daemon and the
 * resilient (handoff) modes the recorder writes straight into the file and Pause changed nothing but the wording, so a
 * user who paused to keep something out of the recording had it recorded anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseOfferTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val meta = RecordingMetadata("+15550001111", RecordingDirection.INCOMING)

    private fun actionTitles(engine: AudioRecordingEngine, paused: Boolean = false): List<String> {
        val n = RecordingNotificationHelper(app).getNotification(RecordingServiceState.Active(engine, paused, meta))
        return n.actions.orEmpty().map { it.title.toString() }
    }

    private val pause get() = app.getString(com.baba.callvault.R.string.general_pause)
    private val stop get() = app.getString(com.baba.callvault.R.string.general_stop)

    @Test
    fun `daemon mode offers no pause`() {
        val engine = AudioRecordingEngine().apply { setCaptureModesForTest(daemon = true, handoff = false) }
        assertFalse(engine.supportsPause)
        assertFalse(actionTitles(engine).contains(pause))
        assertTrue(actionTitles(engine).contains(stop))
    }

    @Test
    fun `handoff mode offers no pause`() {
        val engine = AudioRecordingEngine().apply { setCaptureModesForTest(daemon = false, handoff = true) }
        assertFalse(actionTitles(engine).contains(pause))
    }

    @Test
    fun `local capture, which does drop the audio while paused, still offers pause`() {
        val engine = AudioRecordingEngine().apply { setCaptureModesForTest(daemon = false, handoff = false) }
        assertTrue(engine.supportsPause)
        assertTrue(actionTitles(engine).contains(pause))
    }

    @Test
    fun `a pause request where it cannot be honoured is not taken`() {
        val daemon = AudioRecordingEngine().apply { setCaptureModesForTest(daemon = true, handoff = false) }
        assertEquals(false, PausePolicy.accepts(daemon.supportsPause))
        assertEquals(true, PausePolicy.accepts(true))
    }
}
