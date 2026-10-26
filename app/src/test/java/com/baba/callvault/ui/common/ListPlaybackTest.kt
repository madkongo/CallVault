/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.ui.common.ListPlayback.DiscAction
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playing a recording in place on the Recordings list: what each tap does, and where a drag lands. */
class ListPlaybackTest {

    @Test
    fun `the play button on a row that is not playing starts that row`() {
        Phase.entries.forEach { phase ->
            assertEquals(DiscAction.PLAY, ListPlayback.discAction(isRowActive = false, phase = phase))
        }
    }

    @Test
    fun `the play button on the playing row pauses it, and resumes it when paused`() {
        assertEquals(DiscAction.PAUSE, ListPlayback.discAction(isRowActive = true, phase = Phase.PLAYING))
        assertEquals(DiscAction.RESUME, ListPlayback.discAction(isRowActive = true, phase = Phase.PAUSED))
    }

    @Test
    fun `a tap while the row is still loading does nothing, and after an error it tries again`() {
        assertEquals(DiscAction.NONE, ListPlayback.discAction(isRowActive = true, phase = Phase.LOADING))
        assertEquals(DiscAction.PLAY, ListPlayback.discAction(isRowActive = true, phase = Phase.ERROR))
        assertEquals(DiscAction.PLAY, ListPlayback.discAction(isRowActive = true, phase = Phase.IDLE))
    }

    @Test
    fun `only the playing or paused row shows the seek bar`() {
        assertTrue(ListPlayback.showsSeekBar(isRowActive = true, phase = Phase.PLAYING))
        assertTrue(ListPlayback.showsSeekBar(isRowActive = true, phase = Phase.PAUSED))
        assertFalse(ListPlayback.showsSeekBar(isRowActive = true, phase = Phase.LOADING))
        assertFalse(ListPlayback.showsSeekBar(isRowActive = true, phase = Phase.ERROR))
        assertFalse(ListPlayback.showsSeekBar(isRowActive = false, phase = Phase.PLAYING))
    }

    @Test
    fun `progress is a fraction of the length, and zero while the length is unknown`() {
        assertEquals(0.25f, ListPlayback.fraction(positionMs = 1_000, durationMs = 4_000), 0.0001f)
        assertEquals(0f, ListPlayback.fraction(positionMs = 1_000, durationMs = 0), 0f)
        assertEquals(1f, ListPlayback.fraction(positionMs = 5_000, durationMs = 4_000), 0f)
        assertEquals(0f, ListPlayback.fraction(positionMs = -10, durationMs = 4_000), 0f)
    }

    @Test
    fun `a touch along the bar maps to that moment, clamped to the recording`() {
        assertEquals(2_000, ListPlayback.positionAt(x = 100f, widthPx = 200, durationMs = 4_000, rtl = false))
        assertEquals(0, ListPlayback.positionAt(x = -30f, widthPx = 200, durationMs = 4_000, rtl = false))
        assertEquals(4_000, ListPlayback.positionAt(x = 260f, widthPx = 200, durationMs = 4_000, rtl = false))
    }

    @Test
    fun `in a right-to-left language the bar fills from the right`() {
        // Arabic and Persian ship with the app: the start of the recording is the right-hand end.
        assertEquals(3_000, ListPlayback.positionAt(x = 50f, widthPx = 200, durationMs = 4_000, rtl = true))
        assertEquals(4_000, ListPlayback.positionAt(x = 0f, widthPx = 200, durationMs = 4_000, rtl = true))
    }

    @Test
    fun `a touch before the bar is measured or the recording is loaded lands at zero`() {
        assertEquals(0, ListPlayback.positionAt(x = 50f, widthPx = 0, durationMs = 4_000, rtl = false))
        assertEquals(0, ListPlayback.positionAt(x = 50f, widthPx = 200, durationMs = 0, rtl = false))
    }

    @Test
    fun `leaving the list stops the audio unless it is leaving for that recording's own page`() {
        assertFalse(ListPlayback.stopsOnLeavingList(listShowing = true, openRecording = null))
        assertTrue(ListPlayback.stopsOnLeavingList(listShowing = false, openRecording = null))
        assertFalse(ListPlayback.stopsOnLeavingList(listShowing = false, openRecording = "call.ogg"))
    }

    @Test
    fun `opening another recording's page stops the one playing in the list`() {
        assertFalse(ListPlayback.stopsOnOpening(isRowActive = true))
        assertTrue(ListPlayback.stopsOnOpening(isRowActive = false))
    }
}
