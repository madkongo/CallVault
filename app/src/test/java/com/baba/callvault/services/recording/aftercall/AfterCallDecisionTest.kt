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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.recordings.RecordingCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each answer does to the held entry and the delayed work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallDecisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"
    private val uri = "content://test/tree/doc/$name".toUri()

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
    }

    @Test
    fun `asking schedules the 24 hour check`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.ask(context, name)

        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_unanswered_$name"))
    }

    @Test
    fun `delete keeps the recording held during the undo window`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.delete(context, name)

        assertNotNull("sweeps must keep skipping it until it is really gone", AfterCallDecisions.peek(context, name))
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_delete_$name"))
    }

    @Test
    fun `undo cancels the delete and leaves it held`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.delete(context, name)

        AfterCallDecision.undo(context, name)

        assertEquals(WorkInfo.State.CANCELLED, state("after_call_delete_$name"))
        assertNotNull(AfterCallDecisions.peek(context, name))
    }

    @Test
    fun `keep ends the wait and cancels the 24 hour check`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.ask(context, name)

        val kept = AfterCallDecision.keep(context, name)

        assertTrue(kept)
        assertNull(AfterCallDecisions.peek(context, name))
        assertEquals(WorkInfo.State.CANCELLED, state("after_call_unanswered_$name"))
    }

    @Test
    fun `a second answer finds nothing to act on`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.keep(context, name)

        assertFalse(AfterCallDecision.keep(context, name))
    }

    @Test
    fun `the real delete removes the catalog row and ends the wait`() = runBlocking {
        RecordingCatalog.recordLocal(context, name, uri, 1_000L, 1L)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.deleteNow(context, name, fromUnansweredCheck = false)

        assertNull(AfterCallDecisions.peek(context, name))
        assertTrue(RecordingCatalog.all(context).none { it.displayName == name })
    }

    private fun state(uniqueName: String): WorkInfo.State? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().lastOrNull()?.state
}
