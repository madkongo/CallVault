/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R27: a server that answers every resume with a whole-file 200 would make each 10-minute stop throw
 * the banked bytes away and start again, for ever. Two such answers in a row end the download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelResumeRestartsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a whole-file reply to a resume counts as a restart`() {
        assertTrue(ModelDownloadPolicy.isRestartOfResume(ModelDownloadPolicy.Reply.START_OVER, resumeFrom = 100L))
    }

    @Test
    fun `a whole-file reply to a fresh download is not a restart`() {
        assertFalse(ModelDownloadPolicy.isRestartOfResume(ModelDownloadPolicy.Reply.START_OVER, resumeFrom = 0L))
    }

    @Test
    fun `a continuing reply is not a restart`() {
        assertFalse(ModelDownloadPolicy.isRestartOfResume(ModelDownloadPolicy.Reply.APPEND, resumeFrom = 100L))
    }

    @Test
    fun `gives up on the second restart in a row but not the first`() {
        assertFalse(ModelDownloadPolicy.shouldGiveUpOnResume(1))
        assertTrue(ModelDownloadPolicy.shouldGiveUpOnResume(2))
    }

    @Test
    fun `the count survives across attempts and resets`() {
        val id = "model-a"
        ModelResumeRestarts.reset(context, id)
        assertEquals(1, ModelResumeRestarts.recordRestart(context, id))
        assertEquals(2, ModelResumeRestarts.recordRestart(context, id))
        ModelResumeRestarts.reset(context, id)
        assertEquals(1, ModelResumeRestarts.recordRestart(context, id))
    }

    @Test
    fun `counts are kept per model`() {
        ModelResumeRestarts.reset(context, "m1")
        ModelResumeRestarts.reset(context, "m2")
        ModelResumeRestarts.recordRestart(context, "m1")
        assertEquals(1, ModelResumeRestarts.recordRestart(context, "m2"))
    }
}
