/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Review N5: three Telegram calls were refused because the recording folder had been deleted, and nothing was
 * saved. A call whose folder is not usable is now recorded into CallVault's own storage ("waiting") and saved to
 * the folder once one is usable again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaitingRecordingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARecordings".toUri()

    @Before
    fun setUp() = clean()

    @After
    fun tearDown() = clean()

    private fun clean() {
        File(context.filesDir, WaitingRecordings.DIR_NAME).deleteRecursively()
        File(context.filesDir, "staging").deleteRecursively()
        LiveStagingFiles.clearForTest()
    }

    // ---- the folder's own path stays exactly as it was --------------------------------------------------------

    @Test
    fun `a usable folder takes the folder's own output and never touches waiting storage`() {
        val own = Any()
        var waitingAsked = false
        val chosen = WaitingRecordings.chooseOutput(folderUsable = true, inFolder = { own }, waiting = { waitingAsked = true; Any() })
        assertSame(own, chosen)
        assertFalse(waitingAsked)
    }

    @Test
    fun `an unusable folder never asks the folder, and goes to waiting storage`() {
        var folderAsked = false
        val waiting = Any()
        val chosen = WaitingRecordings.chooseOutput(folderUsable = false, inFolder = { folderAsked = true; Any() }, waiting = { waiting })
        assertSame(waiting, chosen)
        assertFalse(folderAsked)
    }

    @Test
    fun `a usable folder whose file could not be created still keeps the call`() {
        val waiting = Any()
        assertSame(waiting, WaitingRecordings.chooseOutput(folderUsable = true, inFolder = { null }, waiting = { waiting }))
    }

    // ---- creating and finishing ----------------------------------------------------------------------------------

    @Test
    fun `a waiting recording is a named, private, live file outside the cut-off rescue's folder`() {
        val result = WaitingRecordings.create(context, folder, "20261119_124300.000+0200_voip-Telegram.ogg", "audio/ogg", "test", "app call")
        assertNotNull(result)
        result!!
        assertTrue(result.waiting)
        assertNull(result.folderUri)
        assertNull(result.uri)
        val file = result.stagingFile!!
        assertEquals(File(context.filesDir, WaitingRecordings.DIR_NAME), file.parentFile)
        assertTrue(WaitingRecordings.isWaitingRecording(file))
        assertFalse("the cut-off rescue must not take it", SafHelper.isStagedRecording(file))
        assertTrue(LiveStagingFiles.isLive(file))
        val note = WaitingNote.read(file)!!
        assertEquals("20261119_124300.000+0200_voip-Telegram.ogg", note.note.fileName)
        assertEquals("audio/ogg", note.note.mimeType)
        assertNull(note.finishedAt)
        // The orphan check reads the start time from the same note format the staging area uses.
        assertNotNull(StagingNote.decode(StagingNote.fileFor(file).readText()))
        result.descriptor.close()
    }

    @Test
    fun `finishing keeps the audio under its final name and lets the rescue rules see it is done`() {
        val result = WaitingRecordings.create(context, null, "start.ogg", "audio/ogg", "test", "app call")!!
        result.descriptor.close()
        val file = result.stagingFile!!
        file.writeBytes(ByteArray(1_000))
        assertTrue(WaitingRecordings.finish(context, file, "final_name.ogg", "audio/ogg", "app call"))
        val note = WaitingNote.read(file)!!
        assertEquals("final_name.ogg", note.note.fileName)
        assertNotNull(note.finishedAt)
        assertFalse(LiveStagingFiles.isLive(file))
        assertEquals(1, WaitingRecordings.count(context))
        assertEquals(1_000L, WaitingRecordings.totalBytes(context))
    }

    @Test
    fun `an empty waiting recording is removed, not kept`() {
        val result = WaitingRecordings.create(context, null, "start.ogg", "audio/ogg", "test", "phone call")!!
        result.descriptor.close()
        val file = result.stagingFile!!
        assertFalse(WaitingRecordings.finish(context, file, "start.ogg", "audio/ogg", "phone call"))
        assertFalse(file.exists())
        assertFalse(StagingNote.fileFor(file).exists())
        assertEquals(0, WaitingRecordings.count(context))
    }

    @Test
    fun `a full waiting area refuses a new call instead of filling the phone`() {
        val dir = File(context.filesDir, WaitingRecordings.DIR_NAME).apply { mkdirs() }
        java.io.RandomAccessFile(File(dir, "rec_wait_big.tmp"), "rw").use { it.setLength(WaitingPolicy.CAP_BYTES) }
        assertNull(WaitingRecordings.create(context, folder, "x.ogg", "audio/ogg", "test", "app call"))
        assertFalse(WaitingRecordings.canAccept(context))
    }

    // ---- the pure rules -------------------------------------------------------------------------------------------

    @Test
    fun `admission - room, cap, and the phone's own free space`() {
        assertEquals(WaitingPolicy.Admission.OK, WaitingPolicy.admit(totalBytes = 0L, freeBytes = 10L * 1024 * 1024 * 1024))
        assertEquals(WaitingPolicy.Admission.FULL, WaitingPolicy.admit(WaitingPolicy.CAP_BYTES, 10L * 1024 * 1024 * 1024))
        assertEquals(WaitingPolicy.Admission.NO_SPACE, WaitingPolicy.admit(0L, WaitingPolicy.MIN_FREE_BYTES - 1))
    }

    @Test
    fun `the early warning comes before the cap`() {
        assertEquals(WaitingPolicy.Level.NORMAL, WaitingPolicy.level(0L))
        assertEquals(WaitingPolicy.Level.NEARLY_FULL, WaitingPolicy.level(WaitingPolicy.NEARLY_FULL_BYTES))
        assertEquals(WaitingPolicy.Level.FULL, WaitingPolicy.level(WaitingPolicy.CAP_BYTES))
        assertTrue(WaitingPolicy.NEARLY_FULL_BYTES < WaitingPolicy.CAP_BYTES)
    }

    @Test
    fun `publishing - a finished recording goes now, a cut-off one only once quiet with no call`() {
        fun d(finished: Boolean, age: Long, call: Boolean, live: Boolean = false, size: Long = 10L, note: Boolean = true) =
            WaitingPolicy.publishDecision(hasNote = note, sizeBytes = size, finished = finished, ageMs = age, callActive = call, live = live)
        assertEquals(WaitingPolicy.Publish.NOW, d(finished = true, age = 0L, call = true))
        assertEquals(WaitingPolicy.Publish.WAIT, d(finished = true, age = 0L, call = false, live = true))
        assertEquals(WaitingPolicy.Publish.WAIT, d(finished = false, age = CutOffRescuePolicy.QUIET_MS, call = true))
        assertEquals(WaitingPolicy.Publish.WAIT, d(finished = false, age = CutOffRescuePolicy.QUIET_MS - 1, call = false))
        assertEquals(WaitingPolicy.Publish.NOW, d(finished = false, age = CutOffRescuePolicy.QUIET_MS, call = false))
        assertEquals(WaitingPolicy.Publish.DISCARD_EMPTY, d(finished = true, age = 0L, call = false, size = 0L))
        assertEquals(WaitingPolicy.Publish.LEAVE, d(finished = true, age = 0L, call = false, note = false))
    }

    // ---- review 2026-11-20: handled like normal calls; folder lost mid-call -------------------------------------

    @Test
    fun `the end of a call keeps the audio owned and unfinished until the after-call steps are done`() {
        val result = WaitingRecordings.create(context, null, "start.ogg", "audio/ogg", "test", "phone call")!!
        result.descriptor.close()
        val file = result.stagingFile!!
        file.writeBytes(ByteArray(100))
        assertTrue(WaitingRecordings.end(context, file, "start.ogg", "audio/ogg", "phone call"))
        assertTrue("still owned: the publisher must not take it before its final name", LiveStagingFiles.isLive(file))
        assertNull(WaitingNote.read(file)!!.finishedAt)
        assertTrue(WaitingRecordings.rename(file, "renamed_from_call_log.ogg"))
        WaitingRecordings.ready(context, file, "audio/ogg", "phone call", WaitingNote.Extras(isVoip = false, appCallMix = true, direction = null))
        val note = WaitingNote.read(file)!!
        assertEquals("renamed_from_call_log.ogg", note.note.fileName)
        assertNotNull(note.finishedAt)
        assertEquals(WaitingNote.Extras(isVoip = false, appCallMix = true, direction = null), note.extras)
        assertFalse(LiveStagingFiles.isLive(file))
    }

    @Test
    fun `a staged recording whose save failed mid-call moves into waiting storage, out of the rescue's reach`() {
        val staging = File(context.filesDir, "staging").apply { mkdirs() }
        val staged = File(staging, "rec_stage_1.tmp").apply { writeBytes(ByteArray(300)) }
        StagingNote.fileFor(staged).writeText(StagingNote("content://gone", "call.ogg", "audio/ogg", 1_000L).encode())
        val moved = WaitingRecordings.adopt(context, staged, "call.ogg", "audio/ogg", "phone call", finished = false)!!
        assertFalse(staged.exists())
        assertFalse(StagingNote.fileFor(staged).exists())
        assertTrue(WaitingRecordings.isWaitingRecording(moved))
        assertEquals(300L, moved.length())
        val note = WaitingNote.read(moved)!!
        assertEquals("call.ogg", note.note.fileName)
        assertEquals(1_000L, note.note.startedAtMillis)
        assertNull(note.finishedAt)
        assertEquals(1, WaitingRecordings.count(context))
    }

    @Test
    fun `a cut-off recording the rescue could not save is adopted ready to go`() {
        val staging = File(context.filesDir, "staging").apply { mkdirs() }
        val staged = File(staging, "rec_stage_2.tmp").apply { writeBytes(ByteArray(300)) }
        val moved = WaitingRecordings.adopt(context, staged, "cut.ogg", "audio/ogg", "cut-off recording", finished = true)!!
        assertNotNull(WaitingNote.read(moved)!!.finishedAt)
    }

    @Test
    fun `the note's extras survive being written and read`() {
        val n = WaitingNote(StagingNote("none", "a.ogg", "audio/ogg", 5L), 9L, WaitingNote.Extras(isVoip = true, appCallMix = false, direction = "in"))
        assertEquals(n, WaitingNote.decode(n.encode()))
        val plain = WaitingNote(StagingNote("none", "a.ogg", "audio/ogg", 5L), null)
        assertEquals(plain, WaitingNote.decode(plain.encode()))
    }
}
