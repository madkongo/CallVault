/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.AppPreferences.AfterCallUnanswered
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The 24-hour check, end to end against the real store, catalog and transcripts database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallUnansweredWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"
    private val uri = "content://test/tree/doc/$name".toUri()
    private val prefs = AppPreferences(context)

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
        if (TranscriptDatabase.exists(context)) TranscriptDatabase.get(context).favouriteDao().deleteFor(name)
        prefs.setAfterCallUnanswered(AfterCallUnanswered.DELETE_AFTER_24H)
    }

    @After
    fun tearDown() = prefs.setAfterCallUnanswered(AfterCallUnanswered.KEEP)

    @Test
    fun `an untouched recording is deleted under delete after 24 hours`() = runBlocking {
        RecordingCatalog.recordLocal(context, name, uri, 1_000L, 1L)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        runCheck()

        assertNull(AfterCallDecisions.peek(context, name))
        assertFalse(catalogued())
    }

    @Test
    fun `a starred recording is kept even under delete after 24 hours`() = runBlocking {
        RecordingCatalog.recordLocal(context, name, uri, 1_000L, 1L)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        TranscriptDatabase.get(context).favouriteDao().add(name)

        runCheck()

        assertNull("kept: the wait is over", AfterCallDecisions.peek(context, name))
        assertTrue("and the recording is still there", catalogued())
    }

    @Test
    fun `a recording a merge took out of the catalog is only released`() = runBlocking {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        runCheck()

        assertNull(AfterCallDecisions.peek(context, name))
    }

    @Test
    fun `an untouched recording is not engaged`() = runBlocking {
        TranscriptDatabase.get(context) // exists, but holds nothing for this name
        assertFalse(AfterCallEngagement.isEngaged(context, "nobody-touched-this.ogg"))
    }

    private suspend fun runCheck() {
        TestListenableWorkerBuilder<AfterCallUnansweredWorker>(context)
            .setInputData(workDataOf(AfterCallWork.KEY_NAME to name))
            .build()
            .doWork()
    }

    private suspend fun catalogued(): Boolean = RecordingCatalog.contains(context, name) == true
}
