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

class SpokenPositionTest {

    @Test
    fun playback_ticking_on_keeps_the_spoken_position() {
        // 0:10 being read, playback moves 0:20 -> 0:21: nothing new to say.
        assertEquals(10_000, SeekSemantics.spokenPositionAfter(10_000, 20_000, 21_000))
    }

    @Test
    fun thirty_seconds_of_playback_refreshes_it() {
        assertEquals(40_000, SeekSemantics.spokenPositionAfter(10_000, 39_000, 40_000))
    }

    @Test
    fun a_forward_seek_is_read_at_once() {
        assertEquals(25_000, SeekSemantics.spokenPositionAfter(10_000, 11_000, 25_000))
    }

    @Test
    fun a_backward_seek_is_read_at_once() {
        assertEquals(2_000, SeekSemantics.spokenPositionAfter(10_000, 15_000, 2_000))
    }

    @Test
    fun a_normal_tick_is_not_mistaken_for_a_seek() {
        val tick = SeekSemantics.SPOKEN_JUMP_MS
        assertEquals(0, SeekSemantics.spokenPositionAfter(0, 5_000, 5_000 + tick))
    }
}
