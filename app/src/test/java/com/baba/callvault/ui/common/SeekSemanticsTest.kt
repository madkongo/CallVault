/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where TalkBack's set-progress lands on the seek bars (#61). */
class SeekSemanticsTest {

    @Test
    fun a_target_inside_the_recording_is_rounded_to_a_millisecond() {
        assertEquals(72_000, SeekSemantics.positionFor(71_999.6f, durationMs = 263_000))
    }

    @Test
    fun a_target_past_either_end_is_kept_inside_the_recording() {
        assertEquals(0, SeekSemantics.positionFor(-5_000f, durationMs = 263_000))
        assertEquals(263_000, SeekSemantics.positionFor(300_000f, durationMs = 263_000))
    }

    @Test
    fun an_unloaded_recording_seeks_to_the_start() {
        assertEquals(0, SeekSemantics.positionFor(1_000f, durationMs = 0))
        assertEquals(0, SeekSemantics.positionFor(Float.NaN, durationMs = 263_000))
    }

    @Test
    fun the_range_is_never_empty() {
        assertEquals(1f, SeekSemantics.rangeEndMs(0), 0f)
        assertEquals(263_000f, SeekSemantics.rangeEndMs(263_000), 0f)
    }

    @Test
    fun a_fraction_is_clamped_to_the_bar() {
        assertEquals(0f, SeekSemantics.fractionFor(-0.2f), 0f)
        assertEquals(0.4f, SeekSemantics.fractionFor(0.4f), 0f)
        assertEquals(1f, SeekSemantics.fractionFor(1.3f), 0f)
        assertEquals(0f, SeekSemantics.fractionFor(Float.NaN), 0f)
    }
}
