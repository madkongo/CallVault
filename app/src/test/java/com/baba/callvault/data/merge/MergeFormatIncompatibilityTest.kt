/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.data.merge.MergeFormat.Shape
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeFormatIncompatibilityTest {

    private val opusMono = Shape("audio/opus", 48_000, 1)

    @Test
    fun `identical recordings can be joined`() {
        assertNull(MergeFormat.incompatibility(opusMono, opusMono.copy()))
    }

    @Test
    fun `a stereo and a mono call are refused in words about the setting, not codec internals (#74)`() {
        val reason = MergeFormat.incompatibility(opusMono, opusMono.copy(channels = 2))!!
        assertTrue(reason, reason.contains("own channel"))
        assertTrue("no raw format jargon reaches the user", !reason.contains("audio/") && !reason.contains("Hz"))
    }

    @Test
    fun `a codec change and a rate change each say what differs`() {
        assertTrue(MergeFormat.incompatibility(opusMono, opusMono.copy(mime = "audio/mp4a-latm"))!!.contains("formats"))
        assertTrue(MergeFormat.incompatibility(opusMono, opusMono.copy(sampleRate = 16_000))!!.contains("sample rates"))
    }
}
