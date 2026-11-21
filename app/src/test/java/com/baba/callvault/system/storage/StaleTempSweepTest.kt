/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R34: temps a killed merge leaves are removed; a staged call recording is never deleted by this sweep. */
class StaleTempSweepTest {

    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val old = now - StaleTempSweep.MAX_AGE_MS - 1_000
    private val fresh = now - 60_000L

    private fun file(dir: File, name: String, bytes: Int, modified: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes) { 1 }); setLastModified(modified) }

    @Test
    fun an_old_merge_temp_is_deleted_and_counted() {
        val staging = tmp.newFolder("staging")
        val m = file(staging, "merge_stage_1.tmp", 100, old)

        val r = StaleTempSweep.run(staging, null, now) { false }

        assertFalse(m.exists())
        assertEquals(1, r.mergeStageDeleted)
        assertEquals(100L, r.bytesFreed)
    }

    @Test
    fun a_fresh_merge_temp_is_kept_because_a_merge_may_be_running() {
        val staging = tmp.newFolder("staging")
        val m = file(staging, "merge_stage_1.tmp", 100, fresh)
        StaleTempSweep.run(staging, null, now) { false }
        assertTrue(m.exists())
    }

    @Test
    fun a_merge_temp_a_live_recording_owns_is_kept() {
        val staging = tmp.newFolder("staging")
        val m = file(staging, "merge_stage_1.tmp", 100, old)
        StaleTempSweep.run(staging, null, now) { true }
        assertTrue(m.exists())
    }

    @Test
    fun a_staged_call_recording_is_never_deleted_however_old_with_or_without_its_note() {
        val staging = tmp.newFolder("staging")
        val withNote = file(staging, "rec_stage_1.tmp", 500, old)
        val note = file(staging, "rec_stage_1.tmp" + StagingNote.SUFFIX, 20, old)
        val noteLess = file(staging, "rec_stage_2.tmp", 700, old)

        val r = StaleTempSweep.run(staging, null, now) { false }

        assertTrue(withNote.exists())
        assertTrue(note.exists())
        assertTrue(noteLess.exists())
        assertEquals(0, r.mergeStageDeleted)
        assertEquals(1, r.noteLessStagedCount)
        assertEquals(700L, r.noteLessStagedBytes)
    }

    @Test
    fun an_old_merge_download_in_cache_is_deleted_and_a_fresh_one_kept() {
        val cache = tmp.newFolder("merge-temp")
        val stale = file(cache, "1-a.m4a", 40, old)
        val live = file(cache, "2-b.m4a", 40, fresh)

        val r = StaleTempSweep.run(null, cache, now)

        assertFalse(stale.exists())
        assertTrue(live.exists())
        assertEquals(1, r.mergeTempDeleted)
    }

    @Test
    fun missing_folders_are_fine() {
        val r = StaleTempSweep.run(null, null, now)
        assertFalse(r.anythingToSay)
    }
}
