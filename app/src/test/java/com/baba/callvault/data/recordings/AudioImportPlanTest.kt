/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which sources are turned away before anything is copied, and what they are told.
 *
 * Every case here ends in a sentence shown to someone who has just picked a file and is waiting.
 * The reason has to be the one they can act on, which is why the order is asserted as well as the
 * outcomes: being told the app has no recordings folder when the real problem is that the file is a
 * PDF sends a person to Settings for no reason.
 */
class AudioImportPlanTest {

    private fun reasonFor(
        displayName: String? = "note.m4a",
        mimeType: String? = "audio/mp4",
        sizeBytes: Long = 1_024L,
        hasFolder: Boolean = true,
    ): AudioImport.Reason? =
        when (val plan = AudioImport.planFor(displayName, mimeType, sizeBytes, hasFolder)) {
            is AudioImport.Plan.Refuse -> plan.reason
            is AudioImport.Plan.Accept -> null
        }

    @Test
    fun `an ordinary voice note is accepted`() {
        assertEquals(null, reasonFor(displayName = "PTT-20260916-WA0003.opus", mimeType = "audio/ogg"))
    }

    @Test
    fun `a document is refused as not audio`() {
        assertEquals(
            AudioImport.Reason.NOT_AUDIO,
            reasonFor(displayName = "contract.pdf", mimeType = "application/pdf"),
        )
    }

    @Test
    fun `an empty source is refused`() {
        assertEquals(AudioImport.Reason.EMPTY, reasonFor(sizeBytes = 0L))
    }

    @Test
    fun `a size the provider did not report is not treated as empty`() {
        // Plenty of providers answer nothing for SIZE. Reading that as zero would refuse perfectly
        // good files with a sentence saying they are empty, which is both wrong and unarguable.
        assertEquals(null, reasonFor(sizeBytes = -1L))
    }

    @Test
    fun `with no recordings folder there is nowhere to put it`() {
        assertEquals(AudioImport.Reason.NO_FOLDER, reasonFor(hasFolder = false))
    }

    @Test
    fun `the format is judged before the folder`() {
        // Both are wrong here. Being told about the folder would send someone to Settings over a
        // file that was never going to be imported whatever the folder said.
        assertEquals(
            AudioImport.Reason.NOT_AUDIO,
            reasonFor(displayName = "clip.mkv", mimeType = "video/x-matroska", hasFolder = false),
        )
    }

    @Test
    fun `an empty file is reported as empty rather than as a missing folder`() {
        assertEquals(AudioImport.Reason.EMPTY, reasonFor(sizeBytes = 0L, hasFolder = false))
    }

    @Test
    fun `an accepted plan carries where the copy will be written`() {
        val plan = AudioImport.planFor("meeting.MP3", "application/octet-stream", 2_048L, true)
        assertEquals(
            ImportableAudio.StoredAs("mp3", "audio/mpeg"),
            (plan as AudioImport.Plan.Accept).storedAs,
        )
    }
}
