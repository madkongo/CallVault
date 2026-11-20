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
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** C77: Delete on the question needs the phone unlocked; Keep never does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallDeleteLockedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"
    private val uri = "content://test/tree/doc/$name".toUri()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppPreferences(context).setAfterCallMode(AppPreferences.AfterCallMode.ASK)
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)
    }

    private fun lock(isLocked: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(isLocked)

    @Test
    fun `delete on a locked phone is refused and the question stays`() {
        lock(true)

        AfterCallDecisionReceiver.applyAction(context, AfterCallDecisionReceiver.ACTION_DELETE, name)

        val held = AfterCallDecisions.peek(context, name)
        assertNotNull(held)
        assertFalse("no delete was requested", held!!.deleteRequested)
    }

    @Test
    fun `delete on an unlocked phone works as before`() {
        lock(false)

        AfterCallDecisionReceiver.applyAction(context, AfterCallDecisionReceiver.ACTION_DELETE, name)

        assertTrue(AfterCallDecisions.peek(context, name)!!.deleteRequested)
    }

    @Test
    fun `keep on a locked phone still works`() {
        lock(true)

        AfterCallDecisionReceiver.applyAction(context, AfterCallDecisionReceiver.ACTION_KEEP, name)

        assertTrue(AfterCallDecisions.peek(context, name) == null)
    }

    @Test
    fun `the delete button on the posted question asks for the unlock`() {
        val held = AfterCallDecisions.peek(context, name)!!

        AfterCallNotifier.postQuestion(context, held)

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val delete = posted.actions.single { it.title == context.getString(com.baba.callvault.R.string.after_call_delete) }
        assertTrue(delete.isAuthenticationRequired)
        val keep = posted.actions.single { it.title == context.getString(com.baba.callvault.R.string.after_call_keep) }
        assertFalse(keep.isAuthenticationRequired)
    }
}
