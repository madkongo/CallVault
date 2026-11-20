/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Review R35: a downloaded update that was never installed must not sit in the cache for ever. */
class UpdateDownloadPruneTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L

    private fun apk(dir: File, tag: String, ageMs: Long) =
        File(dir, "CallVault-$tag.apk").also { it.writeText("x"); it.setLastModified(now - ageMs) }

    @Test
    fun a_download_that_is_not_newer_than_the_installed_version_is_removed() {
        val dir = tmp.newFolder("updates")
        val same = apk(dir, "v2.5.2", 0)
        val older = apk(dir, "v2.5.1", 0)
        val removed = UpdateManager.pruneStaleDownloads(dir, "2.5.2", now)
        assertEquals(2, removed)
        assertFalse(same.exists()); assertFalse(older.exists())
    }

    @Test
    fun a_recent_newer_download_is_kept() {
        val dir = tmp.newFolder("updates")
        val newer = apk(dir, "v2.5.3", 2 * day)
        assertEquals(0, UpdateManager.pruneStaleDownloads(dir, "2.5.2", now))
        assertTrue(newer.exists())
    }

    @Test
    fun a_newer_download_untouched_for_a_week_is_removed() {
        val dir = tmp.newFolder("updates")
        val old = apk(dir, "v2.5.3", 8 * day)
        assertEquals(1, UpdateManager.pruneStaleDownloads(dir, "2.5.2", now))
        assertFalse(old.exists())
    }

    @Test
    fun an_unreadable_name_is_left_alone_and_a_missing_folder_is_fine() {
        val dir = tmp.newFolder("updates")
        val odd = File(dir, "something.bin").also { it.writeText("x"); it.setLastModified(now) }
        assertEquals(0, UpdateManager.pruneStaleDownloads(dir, "2.5.2", now))
        assertTrue(odd.exists())
        assertEquals(0, UpdateManager.pruneStaleDownloads(File(dir, "nope"), "2.5.2", now))
    }
}
