/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.RecordingWaveformEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The background draw for issue #63: when it may run, and that an open page sees what it saves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class WaveformDrawTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261028_100000.000+0200_in_Long call.ogg"

    @Test
    fun draws_only_when_no_call_and_no_transcription_is_running() {
        assertEquals(WaveformDrawPolicy.Decision.DRAW, WaveformDrawPolicy.decide(callInProgress = false, transcribing = false))
        assertEquals(WaveformDrawPolicy.Decision.WAIT, WaveformDrawPolicy.decide(callInProgress = true, transcribing = false))
        assertEquals(WaveformDrawPolicy.Decision.WAIT, WaveformDrawPolicy.decide(callInProgress = false, transcribing = true))
        assertEquals(WaveformDrawPolicy.Decision.WAIT, WaveformDrawPolicy.decide(callInProgress = true, transcribing = true))
    }

    @Test
    fun an_undrawn_recording_is_watched_as_empty() = runBlocking {
        // Its own name: the database outlives a single test, so a shared name would see another's bars.
        val undrawn = "20261028_110000.000+0200_in_Never drawn.ogg"
        assertEquals(0, RecordingExtrasRepository.observeWaveform(context, undrawn).first().size)
    }

    @Test
    fun the_page_sees_the_bars_a_background_draw_saved() = runBlocking {
        val peaks = floatArrayOf(0.1f, 0.5f, 1f, 0.25f)
        TranscriptDatabase.get(context).noteDao().upsertWaveform(
            RecordingWaveformEntry(displayName = name, peaks = Waveform.encode(peaks), updatedAt = 1L)
        )

        val seen = RecordingExtrasRepository.observeWaveform(context, name).first()

        assertArrayEquals(Waveform.decode(Waveform.encode(peaks)), seen, 0f)
    }

    @Test
    fun a_recording_is_only_queued_for_drawing_when_it_has_no_cached_shape() = runBlocking {
        val drawn = "20261028_120000.000+0200_in_Already drawn.ogg"
        val undrawn = "20261028_120100.000+0200_in_Not drawn.ogg"
        TranscriptDatabase.get(context).noteDao().upsertWaveform(
            RecordingWaveformEntry(displayName = drawn, peaks = Waveform.encode(floatArrayOf(0.2f, 0.4f)), updatedAt = 1L)
        )

        assertEquals(true, RecordingExtrasRepository.hasWaveform(context, drawn))
        assertEquals(false, RecordingExtrasRepository.hasWaveform(context, undrawn))
    }

    @Test
    fun a_draw_that_keeps_waiting_gives_up_and_leaves_it_to_the_next_visit() {
        // R40: audio mode stuck on "in a call" (Honor #53, POCO #68) used to retry every 30 s for ever.
        assertEquals(false, WaveformDrawPolicy.shouldGiveUp(runAttemptCount = 0))
        assertEquals(false, WaveformDrawPolicy.shouldGiveUp(runAttemptCount = WaveformDrawPolicy.MAX_WAIT_ATTEMPTS - 1))
        assertEquals(true, WaveformDrawPolicy.shouldGiveUp(runAttemptCount = WaveformDrawPolicy.MAX_WAIT_ATTEMPTS))
    }
}
