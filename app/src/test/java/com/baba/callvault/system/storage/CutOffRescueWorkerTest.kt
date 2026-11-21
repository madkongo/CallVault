/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Review C46 (a rescued recording must reach Drive and transcription like any other), R17 (the rescue job is not
 * queued on every app start) and N37 (the job logs the standby bucket it ran in).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CutOffRescueWorkerTest {

    @get:Rule val tmp = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    @After fun restoreSeam() {
        CutOffRescueWorker.handOffs = CutOffRescueWorker.defaultHandOffs
    }

    // ---- C46 ----

    @Test
    fun a_rescued_recording_gets_the_drive_copy_and_transcription_hand_offs() {
        val calls = mutableListOf<List<Any>>()
        CutOffRescueWorker.handOffs = { _, name, uri, mime -> calls += listOf(name, uri, mime) }
        val uri = Uri.parse("content://x/tree/doc/20260928_cut.ogg")

        runBlocking {
            CutOffRescueWorker.afterPublished(context, "20260928_cut.ogg", uri, "audio/ogg", 1234L, 1_790_000_000_000L, 1_790_000_100_000L)
        }

        assertEquals(listOf(listOf<Any>("20260928_cut.ogg", uri, "audio/ogg")), calls)
    }

    @Test
    fun a_failing_hand_off_does_not_undo_the_save() {
        CutOffRescueWorker.handOffs = { _, _, _, _ -> error("drive folder gone") }
        val uri = Uri.parse("content://x/tree/doc/a.ogg")

        // Must not throw: the recording is already in the library; the hand-off is retried by the sweeps.
        runBlocking { CutOffRescueWorker.afterPublished(context, "a.ogg", uri, "audio/ogg", 10L, null, 5L) }
    }

    // ---- R17 ----

    @Test
    fun nothing_is_queued_at_start_when_there_is_no_staging_folder() {
        assertFalse(CutOffRescueWorker.hasStagedWork(null))
    }

    @Test
    fun nothing_is_queued_at_start_when_the_staging_folder_is_empty() {
        assertFalse(CutOffRescueWorker.hasStagedWork(tmp.newFolder("staging")))
    }

    @Test
    fun a_staged_recording_is_queued_at_start() {
        val dir = tmp.newFolder("staging")
        File(dir, "rec_stage_123.tmp").writeText("audio")
        assertTrue(CutOffRescueWorker.hasStagedWork(dir))
    }

    @Test
    fun a_leftover_note_is_queued_at_start_so_it_still_gets_cleaned() {
        val dir = tmp.newFolder("staging")
        File(dir, "rec_stage_123.tmp" + StagingNote.SUFFIX).writeText("v=1")
        assertTrue(CutOffRescueWorker.hasStagedWork(dir))
    }

    @Test
    fun a_merge_temp_is_not_a_reason_to_queue() {
        val dir = tmp.newFolder("staging")
        File(dir, "merge_stage_9.tmp").writeText("x")
        assertFalse(CutOffRescueWorker.hasStagedWork(dir))
    }

    // ---- N37 ----

    @Test
    fun the_standby_bucket_is_named_for_the_log() {
        assertEquals("ACTIVE", CutOffRescueWorker.standbyBucketName(10))
        assertEquals("WORKING_SET", CutOffRescueWorker.standbyBucketName(20))
        assertEquals("FREQUENT", CutOffRescueWorker.standbyBucketName(30))
        assertEquals("RARE", CutOffRescueWorker.standbyBucketName(40))
        assertEquals("RESTRICTED", CutOffRescueWorker.standbyBucketName(45))
        assertEquals("NEVER", CutOffRescueWorker.standbyBucketName(50))
        assertEquals("unknown(99)", CutOffRescueWorker.standbyBucketName(99))
    }
}
