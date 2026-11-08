/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stereo bit-rate floor (#74). When the user turns on stereo output, each side must keep at least
 * what a mono file gets today, and never fall below the perceptual floor where the codec collapses the
 * stereo image (libopus narrows below ~32 kbps, FDK AAC starves per channel). The rule is
 * `max(codec floor, 2 x the mono rate)`; [EncoderLimits] still clamps to the device encoder afterwards.
 */
class StereoBitRateTest {

    @Test
    fun `opus default doubles to the floor`() {
        // 24 kbps default -> 2x = 48 kbps, which is exactly the Opus floor.
        assertEquals(48_000, StereoBitRate.floorFor(ScrcpyAudioCodec.OPUS, 24_000))
    }

    @Test
    fun `opus below the floor is lifted, not merely doubled`() {
        // 16 kbps -> 2x = 32 kbps, the edge where libopus narrows to mono; the floor lifts it to 48.
        assertEquals(48_000, StereoBitRate.floorFor(ScrcpyAudioCodec.OPUS, 16_000))
        assertEquals(48_000, StereoBitRate.floorFor(ScrcpyAudioCodec.OPUS, 8_000))
    }

    @Test
    fun `opus above the floor keeps the user's intent doubled`() {
        // A quality-conscious user at 64 kbps mono keeps per-channel quality: 128, not clamped to 48.
        assertEquals(128_000, StereoBitRate.floorFor(ScrcpyAudioCodec.OPUS, 64_000))
    }

    @Test
    fun `aac default doubles to the floor`() {
        // 32 kbps default -> 2x = 64 kbps, the AAC floor.
        assertEquals(64_000, StereoBitRate.floorFor(ScrcpyAudioCodec.AAC, 32_000))
    }

    @Test
    fun `aac below the floor is lifted`() {
        assertEquals(64_000, StereoBitRate.floorFor(ScrcpyAudioCodec.AAC, 16_000))
    }

    @Test
    fun `aac above the floor keeps the user's intent doubled`() {
        assertEquals(256_000, StereoBitRate.floorFor(ScrcpyAudioCodec.AAC, 128_000))
        assertEquals(128_000, StereoBitRate.floorFor(ScrcpyAudioCodec.AAC, 64_000))
    }
}
