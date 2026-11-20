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
import com.baba.callvault.system.storage.FolderWatchPlan
import com.baba.callvault.system.storage.RecordingFolderCheck
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OnePlus 9 Pro, 2026-11-20: bringing an open Home back to the front never looked at the recording folder again; only
 * a pull-to-refresh did. Home coming into view now looks, but only when the stored answer is stale, so a phone whose
 * folder works does no more than a preference read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeFolderLookTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARecordings".toUri()

    @Before
    fun setUp() {
        // The app's own "app start" check runs on the same thread when Robolectric starts the app; let it finish so
        // it cannot store its answer over what a test arranges.
        RecordingFolderCheck.awaitIdleForTest()
        RecordingFolderCheck.clearForTest(context)
        AppPreferences(context).setRecordingFolderUri(folder)
    }

    @After
    fun tearDown() {
        RecordingFolderCheck.clearForTest(context)
        AppPreferences(context).setRecordingFolderUri(null)
    }

    private fun storeGood(measuredAt: Long) = RecordingFolderCheck.storeForTest(
        context,
        FolderCheckState(folder.toString(), FolderReading.Kind.OK, measuredAt, firstBadAt = 0L, confirmedUnusable = false, alerted = false),
    )

    @Test
    fun `Home coming into view with a fresh answer does not look again`() {
        val measuredAt = System.currentTimeMillis()
        storeGood(measuredAt)
        var measured = false
        assertFalse(HomeFolderLook.onVisible(context) { measured = true })
        assertFalse(measured)
        assertEquals(measuredAt, RecordingFolderCheck.cached(context)?.measuredAt)
    }

    @Test
    fun `Home coming into view with a stale answer looks again and reports back`() {
        val oldAt = System.currentTimeMillis() - FolderWatchPlan.HOME_MAX_AGE_MS - 1_000L
        storeGood(oldAt)
        val done = CountDownLatch(1)
        assertTrue(HomeFolderLook.onVisible(context) { done.countDown() })
        assertTrue("the status should be re-read when the look ends", done.await(10, TimeUnit.SECONDS))
        val stored = RecordingFolderCheck.cached(context)
        assertNotNull(stored)
        assertTrue(stored!!.measuredAt > oldAt)
    }

    @Test
    fun `Home coming into view with no answer at all looks`() {
        val done = CountDownLatch(1)
        assertTrue(HomeFolderLook.onVisible(context) { done.countDown() })
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNotNull(RecordingFolderCheck.cached(context))
    }
}
