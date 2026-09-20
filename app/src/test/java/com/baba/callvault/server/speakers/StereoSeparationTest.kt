/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether a two-channel recording really holds two different parties.
 *
 * This is the gate in front of offline speaker labelling (issue #38). Shizuku recordings are captured
 * by scrcpy as `VOICE_CALL` in `CHANNEL_IN_STEREO` — verified in scrcpy's own bytecode — so the two
 * parties *should* be on separate channels. But they are then encoded at 24 kbps **total**, about
 * 12 kbps a channel, and low-bit-rate stereo encoders commonly collapse channel differences to save
 * bits. If that happens the split dies in the encode.
 *
 * So nothing trusts the channels until this says they are real. A wrong label is worse than no label:
 * no label is a missing feature, a wrong one is the app lying about who said what.
 */
class StereoSeparationTest {

    /** Two genuinely different parties: mid and side carry comparable energy. */
    @Test
    fun `two different speakers read as separated`() {
        // Measured shape from the 2026-09-12 probe: sum − diff = 0.0 dB on a carrier call.
        assertEquals(StereoSeparation.SEPARATED, StereoSeparation.of(sumDb = -20.0, diffDb = -20.0))
        assertEquals(StereoSeparation.SEPARATED, StereoSeparation.of(sumDb = -18.0, diffDb = -23.0))
    }

    /** The same signal on both channels: L−R cancels, so the side channel is far quieter. */
    @Test
    fun `a mono signal copied to both channels reads as mixed`() {
        // This is what a collapsing encoder leaves behind, and what a genuinely mono source looks like.
        assertEquals(StereoSeparation.MIXED, StereoSeparation.of(sumDb = -20.0, diffDb = -60.0))
        assertEquals(StereoSeparation.MIXED, StereoSeparation.of(sumDb = -20.0, diffDb = -27.0))
    }

    @Test
    fun `the boundary is six decibels, and it is inclusive`() {
        assertEquals(StereoSeparation.SEPARATED, StereoSeparation.of(sumDb = -20.0, diffDb = -26.0))
        assertEquals(StereoSeparation.MIXED, StereoSeparation.of(sumDb = -20.0, diffDb = -26.01))
    }

    /** A side channel LOUDER than the mid is still two sources — it means they rarely overlap. */
    @Test
    fun `a louder side channel is separated, not suspicious`() {
        assertEquals(StereoSeparation.SEPARATED, StereoSeparation.of(sumDb = -25.0, diffDb = -20.0))
    }

    /** Silence proves nothing either way, and must not be read as "mixed". */
    @Test
    fun `a recording silent in BOTH channels is unknown, not mixed`() {
        // Calling this MIXED would be the same mistake as reading an unreadable setting as "off".
        assertEquals(StereoSeparation.UNKNOWN, StereoSeparation.of(sumDb = -95.0, diffDb = -99.0))
        assertEquals(
            StereoSeparation.UNKNOWN,
            StereoSeparation.of(sumDb = Double.NEGATIVE_INFINITY, diffDb = Double.NEGATIVE_INFINITY),
        )
    }

    @Test
    fun `a silent mid with a loud side is the MOST separated signal there is`() {
        // ⚠️ An earlier version of the gate checked silence against the mid alone, so two perfectly
        // anti-phase channels — which cancel in the mid — came out UNKNOWN. That is backwards: a silent
        // mid with energy in the side means the two channels share nothing at all.
        assertEquals(
            StereoSeparation.SEPARATED,
            StereoSeparation.of(sumDb = Double.NEGATIVE_INFINITY, diffDb = -6.0),
        )
    }

    @Test
    fun `only a positive SEPARATED lets labelling run`() {
        assertEquals(true, StereoSeparation.SEPARATED.mayLabelSpeakers)
        assertEquals(false, StereoSeparation.MIXED.mayLabelSpeakers)
        assertEquals(false, StereoSeparation.UNKNOWN.mayLabelSpeakers)
    }

    // ---- The decibel helper, because getting this wrong silently inverts the whole gate ----

    @Test
    fun `rms to decibels is full-scale relative`() {
        assertEquals(0.0, StereoSeparation.dbOf(1.0), 0.001)
        assertEquals(-20.0, StereoSeparation.dbOf(0.1), 0.001)
        assertEquals(-40.0, StereoSeparation.dbOf(0.01), 0.001)
    }

    @Test
    fun `digital silence is negative infinity, not an exception or zero`() {
        assertEquals(Double.NEGATIVE_INFINITY, StereoSeparation.dbOf(0.0), 0.0)
    }
}
