/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review N5: the user lost three calls and was never told. What the folder alert says, for each state. The alert is
 * posted directly, never through the missed-call (voicemail) gate.
 */
class FolderNoticeTest {

    @Test
    fun `the folder is gone and nothing waits yet - says so, and that calls will be kept`() {
        val n = FolderNotice.decide(folderGone = true, waitingCount = 0, waitingBytes = 0L, refused = null) as FolderNotice.Post
        assertEquals(R.string.recording_folder_gone_title, n.titleRes)
        assertEquals(R.string.notif_folder_gone_text, n.textRes)
    }

    @Test
    fun `calls are waiting - the count, and that they are lost on uninstall`() {
        val n = FolderNotice.decide(folderGone = true, waitingCount = 3, waitingBytes = 1_000L, refused = null) as FolderNotice.Post
        assertEquals(R.plurals.waiting_calls_title, n.titlePlural)
        assertEquals(3, n.count)
        assertEquals(R.string.waiting_calls_text, n.textRes)
    }

    @Test
    fun `nearing the cap - the early warning`() {
        val n = FolderNotice.decide(true, 9, WaitingPolicy.NEARLY_FULL_BYTES, null) as FolderNotice.Post
        assertEquals(R.string.waiting_calls_nearly_full_text, n.textRes)
    }

    @Test
    fun `a call that could not be kept - says new calls are not recorded`() {
        val n = FolderNotice.decide(true, 40, WaitingPolicy.CAP_BYTES, WaitingPolicy.Admission.FULL) as FolderNotice.Post
        assertEquals(R.string.waiting_calls_full_text, n.textRes)
        val noSpace = FolderNotice.decide(true, 0, 0L, WaitingPolicy.Admission.NO_SPACE) as FolderNotice.Post
        assertEquals(R.string.waiting_calls_full_text, noSpace.textRes)
    }

    @Test
    fun `calls still waiting while the folder is back - still counted until saved`() {
        assertTrue(FolderNotice.decide(folderGone = false, waitingCount = 2, waitingBytes = 10L, refused = null) is FolderNotice.Post)
    }

    @Test
    fun `nothing gone and nothing waiting - the notice goes away`() {
        assertEquals(FolderNotice.Clear, FolderNotice.decide(folderGone = false, waitingCount = 0, waitingBytes = 0L, refused = null))
    }
}
