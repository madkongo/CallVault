package com.baba.callvault.services.recording

import com.baba.callvault.system.storage.OrphanStop
import com.baba.callvault.system.storage.StagingNote
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Review N16: a rescued app call is named as a normal finish would name it (direction, late app, late caller). */
class VoipStagingNoteNameTest {
    private lateinit var dir: File
    private lateinit var staged: File
    private val startName = "20261119_101010.000+0200_voip.ogg"

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("n16").toFile()
        staged = File(dir, "rec_stage_1.tmp").apply { writeText("audio") }
        StagingNote.fileFor(staged).writeText(StagingNote("content://f", startName, "audio/ogg", 7L).encode())
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun rescuedName() = StagingNote.decode(StagingNote.fileFor(staged).readText())!!.fileName

    @Test fun `an incoming call's note carries the name a normal finish gives`() {
        val final = VoipLateCaller.finalName(startName, "WhatsApp", "Feroza", "in")
        assertTrue(StagingNote.updateFileName(staged, final))
        assertEquals("20261119_101010.000+0200_in_voip-WhatsApp_Feroza.ogg", rescuedName())
    }

    @Test fun `an outgoing call's note carries the name a normal finish gives`() {
        StagingNote.updateFileName(staged, VoipLateCaller.finalName(startName, null, null, "out"))
        assertEquals("20261119_101010.000+0200_out_voip.ogg", rescuedName())
    }

    @Test fun `nothing learned leaves the name as it is`() {
        assertEquals(startName, VoipLateCaller.finalName(startName, null, null, null))
    }

    @Test fun `the other note fields survive the rename`() {
        StagingNote.markOrphanStop(staged, OrphanStop.STOPPED, 3, true)
        StagingNote.updateFileName(staged, "x_in_voip.ogg")
        val n = StagingNote.decode(StagingNote.fileFor(staged).readText())!!
        assertEquals(OrphanStop.STOPPED, n.orphanStop)
        assertTrue(n.appCall)
        assertEquals("content://f", n.folderUri)
        assertEquals(7L, n.startedAtMillis)
    }

    @Test fun `no note, a garbled note or no staged file changes nothing`() {
        StagingNote.fileFor(staged).delete()
        assertFalse(StagingNote.updateFileName(staged, "a.ogg"))
        assertFalse(StagingNote.fileFor(staged).exists())
        StagingNote.fileFor(staged).writeText("junk")
        assertFalse(StagingNote.updateFileName(staged, "a.ogg"))
        assertEquals("junk", StagingNote.fileFor(staged).readText())
        StagingNote.fileFor(staged).writeText(StagingNote("content://f", startName, "audio/ogg", 7L).encode())
        staged.delete()
        assertFalse(StagingNote.updateFileName(staged, "a.ogg"))
        assertEquals(startName, rescuedName())
    }

    @Test fun `a blank name is refused`() {
        assertFalse(StagingNote.updateFileName(staged, " "))
        assertEquals(startName, rescuedName())
    }

    @Test fun `the carrier note is untouched by anything here`() {
        val carrier = StagingNote("content://f", "2026_call.ogg", "audio/ogg", 1L)
        assertEquals(carrier, StagingNote.decode(carrier.encode()))
    }
}

class StagingNoteAtomicWriteTest {
    private lateinit var dir: File
    private lateinit var staged: File

    @Before fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("n16a").toFile()
        staged = File(dir, "rec_stage_1.tmp").apply { writeText("audio") }
        StagingNote.fileFor(staged).writeText(StagingNote("content://f", "a.ogg", "audio/ogg", 7L).encode())
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun `a rename writes through a temp that is gone afterwards`() {
        assertTrue(StagingNote.updateFileName(staged, "b.ogg"))
        assertEquals("b.ogg", StagingNote.decode(StagingNote.fileFor(staged).readText())!!.fileName)
        assertEquals(listOf("rec_stage_1.tmp", "rec_stage_1.tmp.note"), dir.list()!!.sorted())
        assertTrue(StagingNote.markOrphanStop(staged, OrphanStop.STOPPED))
        assertEquals(2, dir.list()!!.size)
    }

    @Test fun `a leftover temp is neither a note nor a staged recording`() {
        val stray = File(dir, "rec_stage_1.tmp.note.tmp").apply { writeText("v=1") }
        assertTrue(StagingNote.isStrayNoteTemp(stray))
        assertFalse(com.baba.callvault.system.storage.SafHelper.isStagedRecording(stray))
        assertFalse(StagingNote.isStrayNoteTemp(StagingNote.fileFor(staged)))
        assertEquals("a.ogg", StagingNote.decode(StagingNote.fileFor(staged).readText())!!.fileName)
        assertTrue(StagingNote.updateFileName(staged, "c.ogg"))
        assertEquals("c.ogg", StagingNote.decode(StagingNote.fileFor(staged).readText())!!.fileName)
    }

    @Test fun `a failed write keeps the old note and removes the temp`() {
        val note = StagingNote.fileFor(staged)
        assertFalse(StagingNote.writeAtomically(File(dir, "missing/x.note"), "z"))
        assertEquals("a.ogg", StagingNote.decode(note.readText())!!.fileName)
    }

    @Test fun `an update after the recording finished does not bring the note back`() {
        StagingNote.deleteWithNote(staged)
        assertFalse(StagingNote.updateFileName(staged, "late.ogg"))
        assertFalse(StagingNote.fileFor(staged).exists())
        assertEquals(0, dir.list()!!.size)
    }
}
