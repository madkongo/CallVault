/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec

/**
 * The bit rate to encode with when the user turns on stereo output (#74).
 *
 * Everywhere else a bit rate is the total across all channels, so encoding the already-captured stereo
 * at the mono rate would split it in half per side — the v1.4.4 regression that starved the far party
 * ("calls are mono content"). Stereo is only worth having if each side keeps at least what a mono file
 * gets today, and never drops below the floor where the codec collapses the stereo image:
 *
 * - **Opus**: libopus narrows the stereo image below ~32 kbps and drops to a single coupled mono stream
 *   below ~19 kbps. Floor 48 kbps keeps a genuine two-channel image.
 * - **AAC**: FDK/hardware AAC bandwidth is per channel; below ~32 kbps/channel each side is starved.
 *   Floor 64 kbps (= 32/channel).
 *
 * So the rule is `max(codec floor, 2 x the mono rate)`:
 * - the default 24 kbps Opus user lands at 48 (what #74 asks for);
 * - a 16 kbps user is lifted to 48, not left at a narrowed 32;
 * - a 64 kbps user keeps intent at 128.
 *
 * This is the *requested* rate. [EncoderLimits.resolveBitRate] still clamps it to the device encoder's
 * hardware range afterwards (rare: a few encoders cap AAC below the floor — that is the best the device
 * can do and is acceptable).
 */
object StereoBitRate {

    /** Opus keeps a real stereo image at and above this total rate. */
    const val OPUS_FLOOR = 48_000

    /** AAC gives each of two channels ~32 kbps at and above this total rate. */
    const val AAC_FLOOR = 64_000

    /**
     * The total stereo bit rate for [codec] given the user's stored mono [monoRate]:
     * `max(codec floor, 2 x monoRate)`. Still subject to [EncoderLimits] clamping at encode time.
     */
    fun floorFor(codec: ScrcpyAudioCodec, monoRate: Int): Int {
        val floor = when (codec) {
            ScrcpyAudioCodec.OPUS -> OPUS_FLOOR
            ScrcpyAudioCodec.AAC -> AAC_FLOOR
        }
        return maxOf(floor, monoRate * 2)
    }
}
