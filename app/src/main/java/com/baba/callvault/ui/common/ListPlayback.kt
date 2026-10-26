/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.ui.viewmodels.RecordingPlaybackController.Phase
import kotlin.math.roundToInt

/**
 * The rules for playing a recording in place on the Recordings list.
 *
 * Asked for so a run of calls can be listened through without opening each one. The row's play
 * button plays in the row; tapping the rest of the row still opens the recording's page WITHOUT
 * playing, because a tap meant to look at a private call must not start it out loud.
 */
object ListPlayback {

    /** What the play button on a row does when tapped. */
    enum class DiscAction { PLAY, PAUSE, RESUME, NONE }

    fun discAction(isRowActive: Boolean, phase: Phase): DiscAction = when {
        !isRowActive -> DiscAction.PLAY
        phase == Phase.PLAYING -> DiscAction.PAUSE
        phase == Phase.PAUSED -> DiscAction.RESUME
        // A second tap while the file is still preparing would restart the preparation.
        phase == Phase.LOADING -> DiscAction.NONE
        else -> DiscAction.PLAY
    }

    /** Only the playing or paused row carries a seek bar; every other row looks as it always did. */
    fun showsSeekBar(isRowActive: Boolean, phase: Phase): Boolean =
        isRowActive && (phase == Phase.PLAYING || phase == Phase.PAUSED)

    fun fraction(positionMs: Int, durationMs: Int): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * The moment a touch at [x] along a bar [widthPx] wide points at.
     *
     * [rtl]: in Arabic and Persian the recording starts at the right-hand end, so the distance is
     * measured from there.
     */
    fun positionAt(x: Float, widthPx: Int, durationMs: Int, rtl: Boolean): Int {
        if (widthPx <= 0 || durationMs <= 0) return 0
        val along = (x / widthPx).coerceIn(0f, 1f)
        val fromStart = if (rtl) 1f - along else along
        return (fromStart * durationMs).roundToInt()
    }

    /**
     * Whether audio started from the list stops when the list is no longer on screen.
     *
     * It stops, as it does when leaving a recording's page, except when the list was left for the
     * page of the recording that is playing: that page picks it up from the same spot.
     */
    fun stopsOnLeavingList(listShowing: Boolean, openRecording: String?): Boolean =
        !listShowing && openRecording == null

    /** Opening one recording's page while another plays stops the other: nothing on screen would own it. */
    fun stopsOnOpening(isRowActive: Boolean): Boolean = !isRowActive
}
