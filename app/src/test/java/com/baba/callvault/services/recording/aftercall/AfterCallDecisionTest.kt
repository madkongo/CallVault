/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.AppPreferences.AfterCallAskStyle
import com.baba.callvault.data.AppPreferences.AfterCallMode
import com.baba.callvault.data.recordings.RecordingCatalog
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
import org.robolectric.shadows.ShadowSettings

/** What each answer does to the held entry and the delayed work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallDecisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"
    private val uri = "content://test/tree/doc/$name".toUri()
    private val prefs = AppPreferences(context)
    private var popUps = 0
    private var realOpener: (Context) -> Unit = {}

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        popUps = 0
        realOpener = AfterCallDecision.popUpOpener
        AfterCallDecision.popUpOpener = { popUps++ }
        // Asking is on unless a test says otherwise: startup keeps every waiting recording when it is off.
        prefs.setAfterCallMode(AfterCallMode.ASK)
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
        RecordingCatalog.all(context).forEach { RecordingCatalog.removeName(context, it.displayName) }
    }

    @After
    fun tearDown() {
        AfterCallDecision.popUpOpener = realOpener
        prefs.setAfterCallMode(AfterCallMode.KEEP)
        prefs.setAfterCallAskStyle(AfterCallAskStyle.NOTIFICATION)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `keep mode never holds`() {
        prefs.setAfterCallMode(AfterCallMode.KEEP)

        assertFalse(AfterCallDecision.tryHold(context, name, uri, "audio/ogg", isVoip = false))
        assertNull(AfterCallDecisions.peek(context, name))
    }

    @Test
    fun `holding schedules the 24 hour check at once`() {
        prefs.setAfterCallMode(AfterCallMode.ASK)

        assertTrue(AfterCallDecision.tryHold(context, name, uri, "audio/ogg", isVoip = false))

        assertNotNull(AfterCallDecisions.peek(context, name))
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_unanswered_$name"))
    }

    @Test
    fun `without notification permission the recording is kept, not held`() {
        prefs.setAfterCallMode(AfterCallMode.ASK)
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(AfterCallDecision.tryHold(context, name, uri, "audio/ogg", isVoip = false))
        assertNull(AfterCallDecisions.peek(context, name))
    }

    @Test
    fun `with the question channel blocked the recording is kept, not held`() {
        prefs.setAfterCallMode(AfterCallMode.ASK)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("after_call_question", "x", NotificationManager.IMPORTANCE_NONE))
        try {
            assertFalse(AfterCallDecision.tryHold(context, name, uri, "audio/ogg", isVoip = false))
        } finally {
            nm.deleteNotificationChannel("after_call_question")
        }
    }

    @Test
    fun `delete marks the recording and keeps it held during the undo window`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.delete(context, name)

        val held = AfterCallDecisions.peek(context, name)
        assertNotNull("sweeps must keep skipping it until it is really gone", held)
        assertTrue(held!!.deleteRequested)
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_delete_$name"))
    }

    @Test
    fun `undo cancels the delete, clears the mark and leaves it held`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.delete(context, name)

        AfterCallDecision.undo(context, name)

        assertEquals(WorkInfo.State.CANCELLED, state("after_call_delete_$name"))
        assertFalse(AfterCallDecisions.peek(context, name)!!.deleteRequested)
    }

    @Test
    fun `keep ends the wait, cancels the 24 hour check and hands off in a worker`() {
        prefs.setAfterCallMode(AfterCallMode.ASK)
        AfterCallDecision.tryHold(context, name, uri, "audio/ogg", isVoip = false)

        val kept = AfterCallDecision.keep(context, name)

        assertTrue(kept)
        assertNull(AfterCallDecisions.peek(context, name))
        assertEquals(WorkInfo.State.CANCELLED, state("after_call_unanswered_$name"))
        assertNotNull("the hand-offs are handed to a worker", state("after_call_keep_$name"))
    }

    @Test
    fun `a second answer finds nothing to act on`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.keep(context, name)

        assertFalse(AfterCallDecision.keep(context, name))
    }

    @Test
    fun `keep all releases every held recording`() {
        AfterCallDecisions.hold(context, "a.ogg", uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.hold(context, "b.m4a", uri, "audio/mp4a-latm", isVoip = true)

        val kept = AfterCallDecision.keepAll(context)

        assertEquals(2, kept)
        assertTrue(AfterCallDecisions.pendingNames(context).isEmpty())
    }

    @Test
    fun `the real delete removes the catalog row and ends the wait`() = runBlocking {
        RecordingCatalog.recordLocal(context, name, uri, 1_000L, 1L)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.deleteNow(context, name, fromUnansweredCheck = false)

        assertNull(AfterCallDecisions.peek(context, name))
        assertTrue(RecordingCatalog.all(context).none { it.displayName == name })
    }

    @Test
    fun `startup restores the 24 hour check and a tapped delete`() {
        AfterCallDecisions.hold(context, "asked.ogg", uri, "audio/ogg", isVoip = false, askedAt = System.currentTimeMillis())
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false, askedAt = System.currentTimeMillis())
        AfterCallDecisions.setDeleteRequested(context, name, true)

        AfterCallDecision.reconcile(context)

        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_unanswered_asked.ogg"))
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_unanswered_$name"))
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_delete_$name"))
        assertNull("nothing to delete for a question still open", state("after_call_delete_asked.ogg"))
    }

    @Test
    fun `restoring twice keeps the first schedule`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.reconcile(context)
        val first = WorkManager.getInstance(context).getWorkInfosForUniqueWork("after_call_unanswered_$name").get().single().id

        AfterCallDecision.reconcile(context)

        val after = WorkManager.getInstance(context).getWorkInfosForUniqueWork("after_call_unanswered_$name").get()
        assertEquals(listOf(first), after.map { it.id })
    }

    @Test
    fun `a swipe is remembered so startup does not ask again`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.dismissed(context, name)

        assertTrue(AfterCallDecisions.peek(context, name)!!.dismissed)
    }

    @Test
    fun `the call-end question opens the pop-up when chosen and allowed`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        prefs.setAfterCallAskStyle(AfterCallAskStyle.POPUP)
        ShadowSettings.setCanDrawOverlays(true)

        AfterCallDecision.ask(context, name)

        assertEquals(1, popUps)
        assertEquals("the pop-up asks alone", 0, postedQuestions())
    }

    @Test
    fun `a pop-up that cannot open leaves the question to the notification`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        prefs.setAfterCallAskStyle(AfterCallAskStyle.POPUP)
        ShadowSettings.setCanDrawOverlays(true)
        AfterCallDecision.popUpOpener = { error("window refused") }

        AfterCallDecision.ask(context, name)

        assertEquals(1, postedQuestions())
    }

    @Test
    fun `closing the pop-up unanswered asks every waiting call by notification`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        val other = "20261107_101500.000+0200_out_0501234567.ogg"
        AfterCallDecisions.hold(context, other, uri, "audio/ogg", isVoip = false)
        AfterCallDecision.delete(context, other)
        val deletedNotice = postedQuestions()

        AfterCallDecision.askByNotification(context)

        assertEquals("only the call still waiting is asked", deletedNotice + 1, postedQuestions())
    }

    @Test
    fun `without the overlay permission only the notification asks`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        prefs.setAfterCallAskStyle(AfterCallAskStyle.POPUP)
        ShadowSettings.setCanDrawOverlays(false)

        AfterCallDecision.ask(context, name)

        assertEquals(0, popUps)
        assertEquals(1, postedQuestions())
    }

    @Test
    fun `startup never opens the pop-up`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        prefs.setAfterCallAskStyle(AfterCallAskStyle.POPUP)
        ShadowSettings.setCanDrawOverlays(true)

        AfterCallDecision.reconcile(context)

        assertEquals(0, popUps)
    }

    @Test
    fun `switching back to keep keeps every waiting recording`() {
        AfterCallDecisions.hold(context, "a.ogg", uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.hold(context, "b.ogg", uri, "audio/ogg", isVoip = false)
        prefs.setAfterCallMode(AfterCallMode.ASK)

        AfterCallSettings.setMode(context, AfterCallMode.KEEP)
        val kept = AfterCallSettings.keepWaiting(context) // what setMode starts on its thread, run here to wait for it

        assertEquals(AfterCallMode.KEEP, prefs.getAfterCallMode())
        assertTrue(kept >= 0)
        assertTrue(AfterCallDecisions.pendingNames(context).isEmpty())
    }

    @Test
    fun `keep all leaves a recording whose delete was tapped to its delete`() {
        AfterCallDecisions.hold(context, "kept.ogg", uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.setDeleteRequested(context, name, true)

        assertEquals(1, AfterCallDecision.keepAll(context))

        assertEquals(setOf(name), AfterCallDecisions.pendingNames(context))
    }

    @Test
    fun `startup with asking off keeps leftovers but lets a tapped delete finish`() {
        prefs.setAfterCallMode(AfterCallMode.KEEP)
        AfterCallDecisions.hold(context, "leftover.ogg", uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.setDeleteRequested(context, name, true)

        AfterCallDecision.reconcile(context)

        assertEquals(setOf(name), AfterCallDecisions.pendingNames(context))
        assertEquals(WorkInfo.State.ENQUEUED, state("after_call_delete_$name"))
    }

    @Test
    fun `startup with asking on keeps waiting entries waiting`() {
        prefs.setAfterCallMode(AfterCallMode.ASK)
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecision.reconcile(context)

        assertNotNull(AfterCallDecisions.peek(context, name))
    }

    private fun state(uniqueName: String): WorkInfo.State? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().lastOrNull()?.state

    private fun postedQuestions(): Int =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications.size
}
