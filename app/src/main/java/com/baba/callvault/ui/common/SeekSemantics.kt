/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

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
}
