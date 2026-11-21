/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.system.storage.SyncScheduler
import com.baba.callvault.system.updates.UpdateScheduler
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R25: no network work that may use mobile data runs while the phone is roaming. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoamingConstraintTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        AppPreferences(context).apply {
            setStorageTarget(StorageTarget.BOTH)
            setSyncScheduleMode(SyncScheduleMode.DAILY)
        }
    }

    @Test
    fun `the Drive sweep does not run while roaming`() {
        SyncScheduler.apply(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.NOT_ROAMING, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `the update install does not run while roaming`() {
        UpdateScheduler.enqueueInstallNow(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UpdateScheduler.INSTALL_WORK_NAME).get()
        assertEquals(NetworkType.NOT_ROAMING, infos.single().constraints.requiredNetworkType)
    }

    @Test
    fun `the periodic update check does not run while roaming`() {
        AppPreferences(context).setUpdateCheckEnabled(true)
        UpdateScheduler.apply(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("cv_update_check").get()
        assertEquals(NetworkType.NOT_ROAMING, infos.single().constraints.requiredNetworkType)
    }
}
