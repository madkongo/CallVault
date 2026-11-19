/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C21: a "Stop for now" press belongs to the run whose notification it was on. The next run must clear
 * it before its first copier call, and the Drive LIST (made before the Stop button is on screen) must not obey it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveCopyStopNextRunTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = DriveCopyStop.reset()

    @Test
    fun `a press from an earlier run is cleared when the next run starts`() = runBlocking {
        AppPreferences(context).setStorageTarget(StorageTarget.LOCAL)
        DriveCopyStop.request()

        TestListenableWorkerBuilder<SyncSweepWorker>(context).build().doWork()

        assertFalse(DriveCopyStop.isRequested())
    }

    @Test
    fun `the Drive listing never obeys Stop, a copy does when asked to`() {
        assertFalse(DriveCopyClient.obeysStop(honorStop = true, what = DriveCopyProtocol.MSG_LIST))
        assertTrue(DriveCopyClient.obeysStop(honorStop = true, what = DriveCopyProtocol.MSG_COPY))
        assertFalse(DriveCopyClient.obeysStop(honorStop = false, what = DriveCopyProtocol.MSG_COPY))
    }
}
