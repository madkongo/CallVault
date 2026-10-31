/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic behind the seek bars' accessibility semantics, kept out of the composables so it
 * can be tested on the JVM.
 *
 * TalkBack adjusts a seek bar through set-progress, and the value it sends is whatever its own step
 * produced — it can overshoot either end. Every bar clamps the same way here.
 */
object SeekSemantics {

    /** The end of a millisecond range. At least 1, because a 0..0 range is not a valid progress range. */
    fun rangeEndMs(durationMs: Int): Float = durationMs.coerceAtLeast(1).toFloat()

    /** Where a set-progress in milliseconds lands: rounded, and kept inside the recording. */
    fun positionFor(targetMs: Float, durationMs: Int): Int {
        if (targetMs.isNaN()) return 0
        return targetMs.roundToInt().coerceIn(0, durationMs.coerceAtLeast(0))
    }

    /** Where a set-progress on a 0..1 bar lands. */
    fun fractionFor(target: Float): Float = if (target.isNaN()) 0f else target.coerceIn(0f, 1f)

    /**
     * How far playback may run before the position TalkBack reads is refreshed.
     *
     * TalkBack speaks a focused bar's position every time it changes, so a bar that updated every
     * second talked over the call it was playing (OP9, TalkBack 17.1, 2026-10-31).
     */
    const val SPOKEN_STEP_MS = 30_000

    /** A move bigger than this between two updates is a seek, not playback, and is read at once. */
    const val SPOKEN_JUMP_MS = 2_500

    /** How long the position must stand still (paused) before the exact time is read. */
    const val SPOKEN_SETTLE_MS = 1_500L

    /**
     * The position to read next, given the one being read now, the previous raw position and the new
     * one: unchanged while playback runs on, refreshed after a seek or after [SPOKEN_STEP_MS].
     */
    fun spokenPositionAfter(spokenMs: Int, previousMs: Int, positionMs: Int): Int = when {
        abs(positionMs - previousMs) > SPOKEN_JUMP_MS -> positionMs
        abs(positionMs - spokenMs) >= SPOKEN_STEP_MS -> positionMs
        else -> spokenMs
    }
}
