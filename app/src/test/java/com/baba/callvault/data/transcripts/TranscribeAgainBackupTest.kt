/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The kept row of a "transcribe again" (#72) must come back exactly as it was, nulls included. */
class TranscribeAgainBackupTest {

    @Test
    fun `a finished row comes back as it was`() {
        val row = TranscriptEntry("call.ogg", TranscriptState.DONE, modelId = "ggml-small", language = "ru", updatedAt = 1_234L)
        assertEquals(row, TranscribeAgainBackup.decode("call.ogg", TranscribeAgainBackup.encode(row)))
    }

    @Test
    fun `missing model and language stay missing, not the text null`() {
        val row = TranscriptEntry("call.ogg", TranscriptState.DONE, modelId = null, language = null, updatedAt = 5L)
        val back = TranscribeAgainBackup.decode("call.ogg", TranscribeAgainBackup.encode(row))!!
        assertNull(back.modelId)
        assertNull(back.language)
    }

    @Test
    fun `the restored row is always a finished one`() {
        val row = TranscriptEntry("call.ogg", TranscriptState.DONE, modelId = "m", language = "he", updatedAt = 9L)
        assertEquals(TranscriptState.DONE, TranscribeAgainBackup.decode("call.ogg", TranscribeAgainBackup.encode(row))!!.state)
    }

    @Test
    fun `a damaged value is ignored rather than restoring garbage`() {
        assertNull(TranscribeAgainBackup.decode("call.ogg", "nonsense"))
    }
}
