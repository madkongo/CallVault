/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.storage.FolderCheckState
import com.baba.callvault.system.storage.FolderReading
import com.baba.callvault.system.storage.RecordingFolderCheck
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OnePlus 9 Pro, 2026-11-20 (rf10): the 20 s re-check confirmed the folder GONE and raised the alert, but Home kept
 * saying "Ready to record" until a pull-to-refresh. Home now follows the stored folder answer, whatever stored it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeFolderLiveTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARecordings".toUri()

    @Before
    fun setUp() {
        RecordingFolderCheck.awaitIdleForTest()
        RecordingFolderCheck.clearForTest(context)
        AppPreferences(context).setRecordingFolderUri(folder)
    }

    @After
    fun tearDown() {
        RecordingFolderCheck.clearForTest(context)
        AppPreferences(context).setRecordingFolderUri(null)
    }

    /** What Home shows, worked out the way HomeViewModel does it, from the stored folder answer alone here. */
    private fun status() =
        if (RecordingFolderCheck.isKnownUnusable(context, folder)) HomeViewModel.HomeStatus.FOLDER_UNAVAILABLE
        else HomeViewModel.HomeStatus.READY

    @Test
    fun `a check stored by anyone else turns Home red without any user action`() = runTest(UnconfinedTestDispatcher()) {
        var shown = status()
        val job = launch {
            ReadinessRecompute.triggers(flowOf(false), emptyFlow(), RecordingFolderCheck.changes).collect { shown = status() }
        }
        assertEquals(HomeViewModel.HomeStatus.READY, shown)

        // The re-check (or keep-alive, periodic job, call end): no grant held here, so the loss is confirmed at once.
        RecordingFolderCheck.checkNow(context, "re-check")
        assertEquals(HomeViewModel.HomeStatus.FOLDER_UNAVAILABLE, shown)

        // The folder is usable again (stored as a usable check stores it): Home turns green at once.
        RecordingFolderCheck.storeForTest(
            context,
            FolderCheckState(folder.toString(), FolderReading.Kind.OK, System.currentTimeMillis(), 0L, false, false),
        )
        assertEquals(HomeViewModel.HomeStatus.READY, shown)
        job.cancel()
    }

    @Test
    fun `the stored answer's current value is not a change`() = runTest(UnconfinedTestDispatcher()) {
        var recomputes = 0
        val job = launch {
            ReadinessRecompute.triggers(flowOf(false), emptyFlow(), RecordingFolderCheck.changes).collect { recomputes++ }
        }
        assertEquals(0, recomputes)
        RecordingFolderCheck.checkNow(context, "test")
        assertEquals(1, recomputes)
        job.cancel()
    }
}
