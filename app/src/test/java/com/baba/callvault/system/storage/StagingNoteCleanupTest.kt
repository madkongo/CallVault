/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Review N19 (OP9, rf4): after app calls that saved fine, `rec_stage_*.tmp.note` files stayed in files/staging with
 * no `.tmp` beside them until the next rescue sweep. A staged recording's note goes with it.
 */
class StagingNoteCleanupTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `deleting a staged recording deletes its note too`() {
        val staged = File(tmp.root, "rec_stage_1.tmp").apply { writeText("audio") }
        val note = StagingNote.fileFor(staged).apply { writeText("v=1") }
        assertTrue(StagingNote.deleteWithNote(staged))
        assertFalse(staged.exists())
        assertFalse(note.exists())
    }

    @Test
    fun `a staged recording without a note is still deleted`() {
        val staged = File(tmp.root, "rec_stage_2.tmp").apply { writeText("audio") }
        assertTrue(StagingNote.deleteWithNote(staged))
        assertFalse(staged.exists())
    }

    @Test
    fun `a staged recording that cannot be deleted keeps its note, so the rescue still knows its name`() {
        // A non-empty directory under the staged name: File.delete() fails on it, as a real failed delete would.
        val staged = File(tmp.root, "rec_stage_5.tmp").apply { mkdir(); File(this, "held").writeText("x") }
        val note = StagingNote.fileFor(staged).apply { writeText("v=1") }
        assertFalse(StagingNote.deleteWithNote(staged))
        assertTrue(staged.exists())
        assertTrue(note.exists())
    }

    @Test
    fun `the app-call coordinator deletes staged recordings only with their note`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val source = File(root, "com/baba/callvault/services/recording/VoipRecordingCoordinator.kt").readText()
        assertFalse(source.contains("staging.delete()"))
        assertFalse(source.contains("stagingFile?.delete()"))
        assertEquals(1, Regex("""StagingNote\.deleteWithNote\(""").findAll(source).count())
    }
}
