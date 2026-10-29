/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.cloud.CloudError
import com.baba.callvault.transcription.cloud.TranscriptionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the runner and the scheduler treat a cloud engine (issue #48). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudRunTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runBlocking {
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
        AppPreferences(context).setTranscriptionService(TranscriptionService.ON_DEVICE)
        AppPreferences(context).setTranscriptionCloudMobileData(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    // ── Runner ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the engine id is stored on the transcript so it shows which engine wrote it`() = runBlocking {
        catalogued("a.ogg")
        val runner = TranscriptionRunner(context) { _, _, _, _, _, _, _ -> listOf(TranscriptSegment(0, 1000, "hello")) }

        runner.runBatch(CLOUD_ID, "", "he", listOf("a.ogg"))

        val stored = transcript("a.ogg")!!.transcript
        assertEquals(TranscriptState.DONE, stored.state)
        assertEquals(CLOUD_ID, stored.modelId)
    }

    @Test
    fun `a cloud run gets no contact-name prompt and no wrong-script retry`() = runBlocking {
        catalogued("b.ogg")
        var calls = 0
        var promptSeen: String? = "unset"
        // English words for a Hebrew pin would make the on-device path decode again.
        val runner = TranscriptionRunner(context, audioDurationMs = { 60_000L }) { _, _, _, _, prompt, _, _ ->
            calls++
            promptSeen = prompt
            listOf(TranscriptSegment(0, 1000, "this is plainly english text"))
        }

        runner.runBatch(CLOUD_ID, "", "he", listOf("b.ogg"))

        assertEquals(1, calls)
        assertEquals(null, promptSeen)
    }

    @Test
    fun `a passing cloud failure leaves the call queued and asks for a retry`() = runBlocking {
        catalogued("c.ogg")
        catalogued("d.ogg")
        var calls = 0
        val runner = TranscriptionRunner(context) { _, _, _, _, _, _, _ ->
            calls++
            throw CloudError.forHttpStatus(429, "Groq")
        }

        runner.runBatch(CLOUD_ID, "", "he", listOf("c.ogg", "d.ogg"), canRetryLater = true)

        assertTrue(runner.retryRequested)
        assertEquals("the batch stops at the wall", 1, calls)
        assertEquals(TranscriptState.QUEUED, transcript("c.ogg")!!.transcript.state)
    }

    @Test
    fun `out of retries, a passing failure is a failure`() = runBlocking {
        catalogued("e.ogg")
        val runner = TranscriptionRunner(context) { _, _, _, _, _, _, _ -> throw CloudError.forHttpStatus(503, "Groq") }

        runner.runBatch(CLOUD_ID, "", "he", listOf("e.ogg"), canRetryLater = false)

        assertFalse(runner.retryRequested)
        assertEquals(TranscriptState.FAILED, transcript("e.ogg")!!.transcript.state)
    }

    @Test
    fun `a refused key fails at once, with a message saying what to fix`() = runBlocking {
        catalogued("f.ogg")
        val runner = TranscriptionRunner(context) { _, _, _, _, _, _, _ -> throw CloudError.forHttpStatus(401, "Groq") }

        runner.runBatch(CLOUD_ID, "", "he", listOf("f.ogg"), canRetryLater = true)

        assertFalse(runner.retryRequested)
        val stored = transcript("f.ogg")!!.transcript
        assertEquals(TranscriptState.FAILED, stored.state)
        assertTrue(stored.errorMessage!!.contains("key"))
    }

    @Test
    fun `a cloud service skips the keeps-your-phone-busy confirmation, on this phone keeps it`() {
        val prefs = AppPreferences(context)
        assertFalse(TranscriptionRouting.skipsRunConfirmation(prefs))
        prefs.setTranscriptionService(TranscriptionService.GROQ)
        assertTrue(TranscriptionRouting.skipsRunConfirmation(prefs))
    }

    // ── Scheduler ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tapped cloud run waits for wifi by default`() {
        AppPreferences(context).setTranscriptionService(TranscriptionService.GROQ)
        TranscriptionScheduler.runNow(context, "x.ogg", userRequested = true)
        assertEquals(NetworkType.UNMETERED, manualNetworkType())
    }

    @Test
    fun `a tapped cloud run uses any network once mobile data is allowed`() {
        AppPreferences(context).setTranscriptionService(TranscriptionService.GROQ)
        AppPreferences(context).setTranscriptionCloudMobileData(true)
        TranscriptionScheduler.runNow(context, "x.ogg", userRequested = true)
        assertEquals(NetworkType.CONNECTED, manualNetworkType())
    }

    @Test
    fun `a tapped on-device run needs no network`() {
        TranscriptionScheduler.runNow(context, "x.ogg", userRequested = true)
        assertEquals(NetworkType.NOT_REQUIRED, manualNetworkType())
    }

    private fun manualNetworkType(): NetworkType =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscriptionScheduler.MANUAL_WORK_NAME).get()
            .last().constraints.requiredNetworkType

    private suspend fun catalogued(name: String) {
        RecordingCatalog.recordLocal(context, name, "content://local/$name".toUri(), 10L, 100L)
    }

    private suspend fun transcript(name: String) =
        TranscriptDatabase.get(context).transcriptDao().observe(name).first()

    private companion object {
        const val CLOUD_ID = "cloud:groq/whisper-large-v3-turbo"
    }
}
