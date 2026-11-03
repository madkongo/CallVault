/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.system.storage.SyncScheduler
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Changing where recordings are saved re-applies the Drive schedule at once.
 *
 * Before, only the schedule and time controls did: switching to Drive with a daily schedule left no sweep queued
 * until the next app start or call (found on the OP9, 2026-11-03).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageTargetRescheduleTest {

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        mockkObject(SyncScheduler)
        every { SyncScheduler.apply(any()) } just runs
        viewModel = SettingsViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `choosing where to save re-applies the Drive schedule`() {
        viewModel.setStorageTarget(StorageTarget.BOTH)

        verify(atLeast = 1) { SyncScheduler.apply(any()) }
    }

    @Test
    fun `choosing the Drive folder re-applies the Drive schedule`() {
        viewModel.setDriveFolderUri(null)

        verify(atLeast = 1) { SyncScheduler.apply(any()) }
    }
}
