/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Review N21: Android 17 replaced `AudioRecord`'s private `mChannelCount` + `mChannelMask` with one
 * `mChannelMasks` (an `AudioFormat.ChannelMasks`), read from the emulator image's framework.jar (CP41.260831.007).
 * The constructor-free rebuild picks the layout by which fields the class really has, not by SDK level, and says
 * exactly what was missing when it has neither.
 */
class BypassedAudioRecordLayoutTest {

    @Suppress("unused")
    private class Android16Record {
        private var mChannelCount = 0
        private var mChannelMask = 0
    }

    @Suppress("unused")
    private class Android17Record {
        private var mChannelMasks: Any? = null
    }

    @Suppress("unused")
    private class Both {
        private var mChannelMasks: Any? = null
        private var mChannelCount = 0
        private var mChannelMask = 0
    }

    @Suppress("unused")
    private class Neither {
        private var mChannelCount = 0
    }

    @Test
    fun android_16_and_earlier_use_the_two_int_fields() {
        assertEquals(BypassedAudioRecord.ChannelLayout.COUNT_AND_MASK, BypassedAudioRecord.channelLayoutOf(Android16Record::class.java))
    }

    @Test
    fun android_17_uses_the_channel_masks_object() {
        assertEquals(BypassedAudioRecord.ChannelLayout.CHANNEL_MASKS, BypassedAudioRecord.channelLayoutOf(Android17Record::class.java))
    }

    @Test
    fun the_newer_field_wins_when_a_rom_kept_both() {
        assertEquals(BypassedAudioRecord.ChannelLayout.CHANNEL_MASKS, BypassedAudioRecord.channelLayoutOf(Both::class.java))
    }

    @Test
    fun a_class_with_neither_layout_fails_naming_every_field_it_looked_for() {
        try {
            BypassedAudioRecord.channelLayoutOf(Neither::class.java)
            fail("expected a failure")
        } catch (e: NoSuchFieldException) {
            val m = e.message.orEmpty()
            assertTrue(m, m.contains("mChannelMasks") && m.contains("mChannelMask") && m.contains("mChannelCount"))
            assertTrue(m, m.contains("missing: mChannelMasks, mChannelMask"))
        }
    }
}
