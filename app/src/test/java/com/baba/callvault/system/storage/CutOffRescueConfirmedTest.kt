/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.system.health.SilentFailureNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The cut-off rescue for a recording the orphan check stopped and marked ([OrphanStop]): published only after the short
 * quiet guard (the recorder answers "not recording" before the muxer trailer is written), once, labelled by the mark
 * that travels with the file, and without disturbing other staged files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CutOffRescueConfirmedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val published = CopyOnWriteArrayList<String>()
    private lateinit var dir: File
    private val guardMs = CutOffRescuePolicy.CONFIRMED_QUIET_MS
    private val cutOffId = 4723
    private val savedInFullId = 4735

    @Before
    fun setUp() {
        dir = File(context.filesDir, "staging").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        published.clear()
        CutOffRescueWorker.handOffs = { _, _, _, _ -> }
        CutOffRescueWorker.publishStaged = { _, _, name, _, _ ->
            published += name
            Thread.sleep(50) // a slow folder write: a second run must not slip in meanwhile
            Uri.parse("content://x/tree/doc/$name")
        }
        (context.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_NORMAL
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        CutOffRescueWorker.handOffs = CutOffRescueWorker.defaultHandOffs
        CutOffRescueWorker.publishStaged = CutOffRescueWorker.defaultPublishStaged
        LiveStagingFiles.clearForTest()
    }

    private fun stage(id: String, ageMs: Long, stop: OrphanStop, name: String = "$id.ogg"): File {
        val f = File(dir, "rec_stage_$id.tmp").apply { writeBytes(ByteArray(2_000) { 1 }); setLastModified(System.currentTimeMillis() - ageMs) }
        StagingNote.fileFor(f).writeText(StagingNote("content://folder", name, "audio/ogg", 1_000L, stop).encode())
        return f
    }

    private fun run(path: String? = null) = runBlocking {
        val builder = TestListenableWorkerBuilder<CutOffRescueWorker>(context)
        if (path != null) builder.setInputData(androidx.work.Data.Builder().putString("confirmed_file", path).build())
        builder.build().doWork()
    }

    private fun posted(id: Int) = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(id)

    @Test
    fun `a stopped file still being written is not published until it has been quiet for the guard`() {
        val f = stage("a", ageMs = 1_000L, stop = OrphanStop.CALL_ENDED) // the recorder said "stopped" while the trailer is still written

        run(f.absolutePath)
        assertTrue("fresh file must wait", published.isEmpty())
        assertTrue(f.exists())

        f.setLastModified(System.currentTimeMillis() - guardMs - 1_000L)
        run(f.absolutePath)
        assertEquals(listOf("a.ogg"), published.toList())
        assertFalse(f.exists())
    }

    @Test
    fun `another fresh staged file still waits for quiet while a stopped one is published`() {
        val stopped = stage("stopped", ageMs = 2 * guardMs, stop = OrphanStop.CALL_ENDED)
        val other = stage("other", ageMs = 10_000L, stop = OrphanStop.NONE)

        run()

        assertEquals(listOf("stopped.ogg"), published.toList())
        assertFalse(stopped.exists())
        assertTrue("an unmarked file that is not quiet yet is untouched", other.exists())
    }

    @Test
    fun `no marked file is published, handed off or announced while a call is up`() {
        val handOffs = mutableListOf<String>()
        CutOffRescueWorker.handOffs = { _, name, _, _ -> handOffs += name }
        val full = stage("full2", ageMs = 2 * guardMs, stop = OrphanStop.CALL_ENDED)
        (context.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_IN_CALL

        run()

        assertTrue(published.isEmpty() && handOffs.isEmpty())
        assertTrue(full.exists())
    }

    private fun workStates(name: String) =
        androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().map { it.state }

    private fun markedWaitingFile() = stage("busy", ageMs = 2 * guardMs, stop = OrphanStop.CALL_ENDED).also {
        (context.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_IN_CALL
    }

    @Test
    fun `an ordinary quiet-wait run never restarts the 15 s chain for a marked file that still waits`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        markedWaitingFile()

        run() // no attempt in its input: the ordinary request

        assertTrue("no confirmed chain", workStates("cut_off_rescue_confirmed").isEmpty())
        assertEquals(1, workStates("cut_off_rescue").size)
    }

    @Test
    fun `after the cap hands over, the following ordinary run still makes no confirmed request`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        markedWaitingFile()
        runBlocking {
            TestListenableWorkerBuilder<CutOffRescueWorker>(context)
                .setInputData(androidx.work.Data.Builder().putInt("attempt", CutOffRescuePolicy.CONFIRMED_MAX_RECHECKS - 1).build())
                .build().doWork()
        }
        assertTrue(workStates("cut_off_rescue_confirmed").isEmpty())

        run() // the ordinary request that took over

        assertTrue("still no confirmed request", workStates("cut_off_rescue_confirmed").isEmpty())
        assertEquals(1, workStates("cut_off_rescue").size)
    }

    @Test
    fun `files waiting for a call for over an hour back the ordinary request off, and a call end resets it`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        markedWaitingFile()
        context.getSharedPreferences("cut_off_rescue_state", Context.MODE_PRIVATE).edit()
            .putLong("busy_since", System.currentTimeMillis() - 2 * 60 * 60_000L).commit()

        run()
        assertTrue(CutOffRescueWorker.isBackedOff(context))

        CutOffRescueWorker.schedule(context) // a call ended
        assertFalse(CutOffRescueWorker.isBackedOff(context))
    }

    @Test
    fun `the call-up deferral reason is logged once when it first applies after attempt 0`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        markedWaitingFile()
        val attempt1 = androidx.work.Data.Builder().putInt("attempt", 1).build()

        repeat(2) { runBlocking { TestListenableWorkerBuilder<CutOffRescueWorker>(context).setInputData(attempt1).build().doWork() } }

        assertTrue(context.getSharedPreferences("cut_off_rescue_state", Context.MODE_PRIVATE).getBoolean("call_reason_logged", false))
    }

    @Test
    fun `a confirmed run re-queues itself, and after the cap hands over to the ordinary request once`() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        markedWaitingFile()
        val attempt = { n: Int -> androidx.work.Data.Builder().putInt("attempt", n).build() }

        runBlocking { TestListenableWorkerBuilder<CutOffRescueWorker>(context).setInputData(attempt(0)).build().doWork() }
        assertEquals(1, workStates("cut_off_rescue_confirmed").size)
        assertTrue(workStates("cut_off_rescue").isEmpty())

        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        runBlocking {
            TestListenableWorkerBuilder<CutOffRescueWorker>(context)
                .setInputData(attempt(CutOffRescuePolicy.CONFIRMED_MAX_RECHECKS - 1)).build().doWork()
        }
        assertTrue("the chain ends at the cap", workStates("cut_off_rescue_confirmed").isEmpty())
        assertEquals(1, workStates("cut_off_rescue").size)
    }

    @Test
    fun `a file stopped at the next call's start is not published, nor handed to Whisper, while that call is up`() {
        val handOffs = mutableListOf<String>()
        CutOffRescueWorker.handOffs = { _, name, _, _ -> handOffs += name }
        val f = stage("n4", ageMs = 2 * guardMs, stop = OrphanStop.STOPPED)
        (context.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_IN_CALL

        run()
        assertTrue(published.isEmpty())
        assertTrue(handOffs.isEmpty())
        assertTrue(f.exists())

        (context.getSystemService(AudioManager::class.java)).mode = AudioManager.MODE_NORMAL
        run()
        assertEquals(listOf("n4.ogg"), published.toList())
        assertEquals(listOf("n4.ogg"), handOffs)
    }

    @Test
    fun `a stopped file a live recording here owns is left alone`() {
        val f = stage("live", ageMs = 10 * guardMs, stop = OrphanStop.STOPPED)
        LiveStagingFiles.register(f)

        run(f.absolutePath)

        assertTrue(published.isEmpty())
        assertTrue(f.exists())
    }

    @Test
    fun `two runs at once never publish the same file twice`() {
        stage("twice", ageMs = 2 * guardMs, stop = OrphanStop.CALL_ENDED)

        runBlocking { (1..2).map { async(Dispatchers.Default) { TestListenableWorkerBuilder<CutOffRescueWorker>(context).build().doWork() } }.awaitAll() }

        assertEquals(listOf("twice.ogg"), published.toList())
    }

    @Test
    fun `a quiet-wait run that finds a call-ended file says the call was saved in full`() {
        stage("full", ageMs = CutOffRescuePolicy.QUIET_MS + 30_000L, stop = OrphanStop.CALL_ENDED)

        run() // no path: the ordinary quiet-wait run

        assertNotNull("the saved-in-full notice", posted(savedInFullId))
        assertNull("no false cut-off notice", posted(cutOffId))
    }

    @Test
    fun `a stopped file whose call end is not proven keeps the cut-off wording`() {
        stage("part", ageMs = 2 * guardMs, stop = OrphanStop.STOPPED)

        run()

        assertNotNull(posted(cutOffId))
        assertNull(posted(savedInFullId))
    }

    @Test
    fun `the saved-in-full notice has an id of its own`() {
        assertTrue(SilentFailureNotifier.ALL_IDS.contains(savedInFullId))
        assertTrue(SilentFailureNotifier.ALL_IDS.contains(cutOffId))
    }

    @Test
    fun `the stop mark survives the note's round trip and a move into waiting storage`() {
        val f = stage("mark", ageMs = 0, stop = OrphanStop.NONE)
        assertTrue(StagingNote.markOrphanStop(f, OrphanStop.CALL_ENDED))

        val note = StagingNote.decode(StagingNote.fileFor(f).readText())!!
        assertEquals(OrphanStop.CALL_ENDED, note.orphanStop)
        assertEquals(OrphanStop.CALL_ENDED, WaitingNote.decode(WaitingNote(note, null).encode())!!.note.orphanStop)
        assertEquals(OrphanStop.NONE, StagingNote.decode(StagingNote("f", "n", "m", 1L).encode())!!.orphanStop)
    }

    @Test
    fun `a file with no note cannot be marked`() {
        val f = File(dir, "rec_stage_nonote.tmp").apply { writeBytes(ByteArray(10)) }
        assertFalse(StagingNote.markOrphanStop(f, OrphanStop.STOPPED))
    }
}
