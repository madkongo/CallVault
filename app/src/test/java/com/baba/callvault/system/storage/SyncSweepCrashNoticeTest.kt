/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Review C11 follow-up: a sweep that crashes on its last attempt tells the user too, not only one that cannot list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSweepCrashNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DriveBackupNotice.onDriveReached(context, "test reset")
        nm.cancelAll()
        Robolectric.setupContentProvider(SyncSweepExactNameTest.SourceProvider::class.java, SyncSweepExactNameTest.SOURCE)
        AppPreferences(context).apply {
            setStorageTarget(StorageTarget.BOTH)
            setRecordingFolderUri("content://${SyncSweepExactNameTest.SOURCE}/tree/root".toUri())
            setDriveFolderUri("content://com.google.android.apps.docs.storage/tree/driveroot".toUri())
        }
        SyncSweepWorker.copierFactory = { throw IllegalStateException("test: the copier could not be made") }
    }

    @After
    fun tearDown() {
        SyncSweepWorker.copierFactory = null
    }

    private fun sweep(attempt: Int) = runBlocking {
        TestListenableWorkerBuilder<SyncSweepWorker>(context).setRunAttemptCount(attempt).build().doWork()
    }

    @Test
    fun `a crash on the last attempt posts the Drive backup notice with the error's name`() {
        sweep(CloudCopyPolicy.LIST_FAILURE_MAX_ATTEMPTS - 1)

        val shown = shadowOf(nm).allNotifications
        assertEquals(1, shown.size)
        val text = shadowOf(shown.single()).contentText.toString()
        assertTrue(text, text.contains("IllegalStateException"))
    }

    @Test
    fun `a crash with retries left does not tell the user yet`() {
        sweep(0)

        assertEquals(0, shadowOf(nm).allNotifications.size)
    }
}
