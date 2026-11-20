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
import org.junit.Assert.assertNull
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
        if (withNote) WaitingNote.write(
            f,
            WaitingNote(
                StagingNote("none", name, "audio/ogg", now - 60_000L),
                if (finished) now - ageMs else null,
                if (finished) WaitingNote.Extras(isVoip = false, appCallMix = false, direction = null) else null,
            ),
        )
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
        var hold = false
        val held = mutableListOf<Boolean>()
        var beforeCatalogCalls = 0
        override suspend fun beforeCatalog(name: String, uri: Uri, mimeType: String, note: WaitingNote): Boolean {
            beforeCatalogCalls++; return hold
        }
        override suspend fun catalog(name: String, uri: Uri, bytes: Long, startedAt: Long): Throwable? {
            catalogued += Triple(name, bytes, startedAt); return null
        }
        override fun afterPublish(name: String, uri: Uri, mimeType: String, held: Boolean) { after += name; this.held += held }
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

    // ---- second review 2026-11-20: never delete or trust an existing document -----------------------------------

    @Test
    fun `an existing file counts as our earlier save only when it has the same non-zero size`() {
        assertEquals(WaitingPublisher.Existing.NONE, WaitingPublisher.existing(existingSize = null, ourSize = 500L))
        assertEquals(WaitingPublisher.Existing.ALREADY_SAVED, WaitingPublisher.existing(existingSize = 500L, ourSize = 500L))
        // A 0-byte or unmeasurable document (a failed earlier write, a provider that will not say) is not our audio.
        assertEquals(WaitingPublisher.Existing.OTHER_NAME, WaitingPublisher.existing(existingSize = 0L, ourSize = 500L))
        assertEquals(WaitingPublisher.Existing.OTHER_NAME, WaitingPublisher.existing(existingSize = -1L, ourSize = 500L))
        // Someone else's file of that name is never replaced.
        assertEquals(WaitingPublisher.Existing.OTHER_NAME, WaitingPublisher.existing(existingSize = 900L, ourSize = 500L))
    }

    @Test
    fun `a name already taken gets the next free numbered name, like the provider would`() {
        val taken = setOf("a.ogg", "a (1).ogg")
        assertEquals("a (2).ogg", WaitingPublisher.freeName("a.ogg") { it in taken })
        assertEquals("b.ogg", WaitingPublisher.freeName("b.ogg") { it in taken })
        assertEquals("noext (1)", WaitingPublisher.freeName("noext") { it == "noext" })
    }

    @Test
    fun `a call held for the keep-or-delete answer is handed on as held, so nothing uploads or transcribes it yet`() = runBlocking {
        val f = waiting("h.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), now = now).apply { hold = true }
        WaitingPublisher.run(env)
        assertEquals(listOf(true), env.held)
    }

    @Test
    fun `a retry finds its own earlier numbered save instead of making another copy`() {
        // "a.ogg" is someone else's (900 bytes); our earlier save went to "a (1).ogg" and was killed before cleanup.
        val sizes = mapOf("a.ogg" to 900L, "a (1).ogg" to 500L)
        assertEquals("a (1).ogg" to true, WaitingPublisher.choose("a.ogg", 500L) { sizes[it] })
        assertEquals("a (2).ogg" to false, WaitingPublisher.choose("a.ogg", 700L) { sizes[it] })
    }

    @Test
    fun `after enough retries a call state that never ends no longer blocks a finished save`() = runBlocking {
        val f = waiting("a.ogg", 500, finished = true)
        val env = FakeEnv(listOf(f), call = true, now = now)
        assertEquals(1, WaitingPublisher.run(env, ignoreCallState = true).saved)
    }

    @Test
    fun `a cut-off with no call identity is saved like the rescue saves one - no details file, no question`() = runBlocking {
        val f = waiting("c.ogg", 500, finished = true)
        WaitingNote.write(f, WaitingNote(StagingNote("none", "c.ogg", "audio/ogg", now), now, extras = null))
        val env = FakeEnv(listOf(f), now = now).apply { hold = true }
        assertEquals(1, WaitingPublisher.run(env).saved)
        assertEquals(0, env.beforeCatalogCalls)
        assertEquals(listOf(false), env.held)
    }
}
