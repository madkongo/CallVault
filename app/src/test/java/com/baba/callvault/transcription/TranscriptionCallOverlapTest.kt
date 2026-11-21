/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.media.AudioManager
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R37: the always-on note that a transcription started during a call. */
class TranscriptionCallOverlapTest {

    @Test
    fun `a normal or unknown audio mode says nothing`() {
        assertNull(TranscriptionCallOverlap.lineFor("a.ogg", AudioManager.MODE_NORMAL))
        assertNull(TranscriptionCallOverlap.lineFor("a.ogg", null))
    }

    @Test
    fun `a call, a ring and an app call each name what the phone was doing`() {
        assertTrue(TranscriptionCallOverlap.lineFor("a.ogg", AudioManager.MODE_IN_CALL)!!.contains("in call"))
        assertTrue(TranscriptionCallOverlap.lineFor("a.ogg", AudioManager.MODE_RINGTONE)!!.contains("ringtone"))
        assertTrue(TranscriptionCallOverlap.lineFor("a.ogg", AudioManager.MODE_IN_COMMUNICATION)!!.contains("app call"))
    }
}
