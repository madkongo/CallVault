/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import androidx.core.net.toUri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A merge joins two recordings into one and deletes the parts it consumed, so every row offered here
 * is audio the user can lose. The case this pins is the imported file: it was never a call, it has no
 * second half, and splicing it onto a conversation would destroy it.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14 tops out at SDK 35 while the app targets 36, same as RecordingSelectionTest.
@Config(sdk = [35])
class MergeCandidatesTest {

    private fun call(name: String, number: String?, onDevice: Boolean = true) =
        RecordingItem(
            uri = "content://dev/$name".toUri(),
            displayName = name,
            sizeBytes = 1,
            lastModified = 0,
            direction = null,
            displayDate = null,
            startedAtMillis = null,
            number = number,
            source = if (onDevice) RecordingSource.LOCAL else RecordingSource.DRIVE,
            localUri = if (onDevice) "content://dev/$name".toUri() else null,
            driveUri = if (onDevice) null else "content://drv/$name".toUri(),
        )

    private val first = call("20260916_101010.123+0300_out_5551234.ogg", "5551234")
    private val redial = call("20260916_101500.123+0300_out_5551234.ogg", "5551234")
    private val other = call("20260916_102000.123+0300_in_5559999.ogg", "5559999")
    private val import = call("20260916_103000.123+0300_import_Standup.m4a", null)

    @Test
    fun `the redial of the same number is the candidate`() {
        assertEquals(
            listOf(redial.displayName),
            MergeCandidates.of(first, listOf(first, redial, other)).map { it.displayName }
        )
    }

    @Test
    fun `a copy only in Drive is not, because the join needs the audio here`() {
        val inDriveOnly = call("20260916_101500.123+0300_out_5551234.ogg", "5551234", onDevice = false)
        assertTrue(MergeCandidates.of(first, listOf(first, inDriveOnly)).isEmpty())
    }

    @Test
    fun `an import is never offered as something to join a call to`() {
        // Even were it to carry a matching number somehow: it is the user's own audio, not the second
        // half of a dropped call, and the merge would consume and delete it.
        val importWithNumber = call("20260916_103000.123+0300_import_Standup.m4a", "5551234")
        assertTrue(MergeCandidates.of(first, listOf(first, importWithNumber)).isEmpty())
    }

    @Test
    fun `an import has nothing to merge into it either`() {
        assertFalse(MergeCandidates.canMerge(import))
        assertTrue(MergeCandidates.of(import, listOf(import, first, redial)).isEmpty())
    }

    @Test
    fun `an ordinary call can still merge`() {
        assertTrue(MergeCandidates.canMerge(first))
        // Including one whose contact is called Important — the exemption reads the marker slot only.
        assertTrue(MergeCandidates.canMerge(call("20260916_101010.123+0300_in_Important.ogg", null)))
    }
}
