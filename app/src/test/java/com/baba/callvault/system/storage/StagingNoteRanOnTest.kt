package com.baba.callvault.system.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingNoteRanOnTest {
    private val base = StagingNote("content://f", "n.ogg", "audio/ogg", 5L)

    @Test
    fun `run-on seconds and the app-call flag round-trip`() {
        val back = StagingNote.decode(base.copy(orphanStop = OrphanStop.CALL_ENDED, ranOnSeconds = 125, appCall = true).encode())!!
        assertEquals(125, back.ranOnSeconds)
        assertTrue(back.appCall)
    }

    @Test
    fun `an old note without them decodes with none`() {
        val back = StagingNote.decode(base.encode())!!
        assertEquals(0, back.ranOnSeconds)
        assertFalse(back.appCall)
    }

    @Test
    fun `marking a stopped orphan writes both facts beside the file`() {
        val dir = java.nio.file.Files.createTempDirectory("note").toFile()
        val staged = File(dir, "rec.tmp").apply { writeText("x") }
        StagingNote.fileFor(staged).writeText(base.encode())
        assertTrue(StagingNote.markOrphanStop(staged, OrphanStop.CALL_ENDED, ranOnSeconds = 90, appCall = true))
        val back = StagingNote.decode(StagingNote.fileFor(staged).readText())!!
        assertEquals(OrphanStop.CALL_ENDED, back.orphanStop)
        assertEquals(90, back.ranOnSeconds)
        assertTrue(back.appCall)
        dir.deleteRecursively()
    }
}
