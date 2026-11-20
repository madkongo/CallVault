/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Review N5: calls kept while the folder was gone are saved, named and catalogued, once a folder is usable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaitingPublisherTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        dir = tmp.newFolder("waiting")
        LiveStagingFiles.clearForTest()
    }

    @After
    fun tearDown() = LiveStagingFiles.clearForTest()

    private fun waiting(name: String, bytes: Int, finished: Boolean, ageMs: Long = 0L, withNote: Boolean = true): File {
        val f = File(dir, "rec_wait_${name.hashCode()}.tmp")
        f.writeBytes(ByteArray(bytes))
        f.setLastModified(now - ageMs)
        if (withNote) WaitingNote.write(f, WaitingNote(StagingNote("none", name, "audio/ogg", now - 60_000L), if (finished) now - ageMs else null))
        return f
    }

    private class FakeEnv(
        override val files: List<File>,
        val problem: String? = null,
        val failPublish: Boolean = false,
        val call: Boolean = false,
        override val now: Long,
    ) : WaitingPublisher.Env {
        val published = mutableListOf<String>()
        val catalogued = mutableListOf<Triple<String, Long, Long>>()
        val after = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var folderAsked = 0
        override fun folderProblem(): String? { folderAsked++; return problem }
        override fun callActive(): Boolean = call
        override fun publish(file: File, name: String, mimeType: String): Pair<Uri, String>? {
            if (failPublish) return null
            published += name
            return "content://x/document/$name".toUri() to name
        }
        override suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable? {
            catalogued += Triple(name, bytes, startedAt); return null
        }
        override fun afterPublish(name: String, uri: Uri, mimeType: String) { after += name }
        override fun record(line: String) { lines += line }
    }

    @Test
    fun `a finished waiting call is saved under its name, catalogued, handed on, and removed from waiting`() = runBlocking {
        val f = waiting("20261119_125700.000+0200_voip-Telegram.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), now = now)
        val result = WaitingPublisher.run(env)
        assertEquals(1, result.saved)
        assertEquals(listOf("20261119_125700.000+0200_voip-Telegram.ogg"), env.published)
        assertEquals(Triple("20261119_125700.000+0200_voip-Telegram.ogg", 500L, now - 60_000L), env.catalogued.single())
        assertEquals(1, env.after.size)
        assertFalse(f.exists())
        assertFalse(StagingNote.fileFor(f).exists())
    }

    @Test
    fun `nothing is touched while the folder is still not usable`() = runBlocking {
        val f = waiting("a.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), problem = "the folder does not exist", now = now)
        val result = WaitingPublisher.run(env)
        assertEquals(0, result.saved)
        assertTrue(env.published.isEmpty())
        assertTrue(f.exists())
    }

    @Test
    fun `a failed save keeps the audio for the next try`() = runBlocking {
        val f = waiting("a.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), failPublish = true, now = now)
        val result = WaitingPublisher.run(env)
        assertEquals(0, result.saved)
        assertTrue(result.retryLater)
        assertTrue(f.exists())
        assertTrue(env.lines.any { it.contains("could not save") })
    }

    @Test
    fun `a call still being recorded is left alone`() = runBlocking {
        val f = waiting("a.ogg", 500, finished = false)
        LiveStagingFiles.register(f)
        val env = FakeEnv(listOf(f), now = now)
        val result = WaitingPublisher.run(env)
        assertEquals(0, result.saved)
        assertTrue(result.retryLater)
        assertTrue(f.exists())
    }

    @Test
    fun `a cut-off waiting call is saved once quiet and no call is up`() = runBlocking {
        val f = waiting("a.ogg", 500, finished = false, ageMs = CutOffRescuePolicy.QUIET_MS)
        assertEquals(0, WaitingPublisher.run(FakeEnv(listOf(f), call = true, now = now)).saved)
        assertEquals(1, WaitingPublisher.run(FakeEnv(listOf(f), call = false, now = now)).saved)
    }

    @Test
    fun `an empty one is removed and a note with no recording is cleaned up`() = runBlocking {
        val empty = waiting("e.ogg", 0, finished = true)
        val orphanNote = File(dir, "rec_wait_gone.tmp.note").apply { writeText("v=1") }
        val env = FakeEnv(listOf(empty), now = now)
        WaitingPublisher.run(env, notes = listOf(orphanNote))
        assertFalse(empty.exists())
        assertFalse(orphanNote.exists())
        assertTrue(env.published.isEmpty())
    }

    @Test
    fun `a recording with no note is never deleted`() = runBlocking {
        val f = waiting("n.ogg", 500, finished = false, ageMs = CutOffRescuePolicy.QUIET_MS, withNote = false)
        val env = FakeEnv(listOf(f), now = now)
        WaitingPublisher.run(env)
        assertTrue(f.exists())
        assertTrue(env.published.isEmpty())
    }

    @Test
    fun `nothing asks the folder's provider while a call is up`() = runBlocking {
        // Review 2026-11-20: no provider query at call time; a process started for a call must not run one.
        val f = waiting("a.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), call = true, now = now)
        val result = WaitingPublisher.run(env)
        assertEquals(0, env.folderAsked)
        assertEquals(0, result.saved)
        assertTrue(result.retryLater)
    }
}
