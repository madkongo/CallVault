/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.DeviceFolderRescan.Listed
import com.baba.callvault.data.recordings.db.RecordingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pull-to-refresh on Recordings: what the rescan adds and what it may forget. */
class DeviceFolderRescanTest {

    private val call = "20261106_101010.123+0200_in_0501234567.ogg"
    private val other = "20261105_090000.000+0200_out_0507654321.ogg"
    private val import = "20261106_101010.123+0200_import_voice-note.ogg"

    private fun listed(name: String) = Listed(name, "content://dev/$name", 100L, 1L)
    private fun local(name: String, at: Long = 0L) = RecordingEntry(name, localUri = "content://dev/$name", lastModified = at)
    private val now = 10_000_000L
    private val anywhere: (String) -> Boolean = { true }

    private fun plan(
        target: StorageTarget, audio: List<Listed>, names: Set<String>?, catalog: List<RecordingEntry>,
        inFolder: (String) -> Boolean = anywhere,
    ) = DeviceFolderRescan.plan(target, audio, names, catalog, inFolder, now)

    @Test
    fun `a file copied into the folder by hand is added`() {
        val plan = plan(StorageTarget.LOCAL, listOf(listed(call)), setOf(call), emptyList())
        assertEquals(listOf(call), plan.add.map { it.name })
    }

    @Test
    fun `a file already catalogued is not added again`() {
        val plan = plan(StorageTarget.LOCAL, listOf(listed(call)), setOf(call), listOf(local(call)))
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun `a Drive-only row gets its device copy when the file is in the folder`() {
        val driveOnly = RecordingEntry(call, driveUri = "content://drive/x")
        val plan = plan(StorageTarget.BOTH, listOf(listed(call)), setOf(call), listOf(driveOnly))
        assertEquals(listOf(call), plan.add.map { it.name })
    }

    @Test
    fun `in Drive mode a leftover call stays out but an import comes back`() {
        val plan = plan(
            StorageTarget.DRIVE, listOf(listed(call), listed(import)), setOf(call, import), emptyList(),
        )
        assertEquals(listOf(import), plan.add.map { it.name })
    }

    @Test
    fun `a device copy missing from the listing is a candidate to forget`() {
        val plan = plan(StorageTarget.LOCAL, emptyList(), setOf(call), listOf(local(call), local(other)))
        assertEquals(listOf("content://dev/$other"), plan.forgetCandidates)
    }

    @Test
    fun `a folder that could not be listed forgets nothing`() {
        val plan = plan(StorageTarget.LOCAL, emptyList(), null, listOf(local(call), local(other)))
        assertTrue(plan.forgetCandidates.isEmpty())
    }

    @Test
    fun `a Drive-only row is never a device candidate`() {
        val driveOnly = RecordingEntry(other, driveUri = "content://drive/x")
        val plan = plan(StorageTarget.BOTH, emptyList(), emptySet(), listOf(driveOnly))
        assertTrue(plan.forgetCandidates.isEmpty())
    }

    @Test
    fun `a copy outside the current folder is never a candidate`() {
        val plan = plan(StorageTarget.LOCAL, emptyList(), emptySet(), listOf(local(other)), inFolder = { false })
        assertTrue(plan.forgetCandidates.isEmpty())
    }

    @Test
    fun `a copy written moments ago is left alone`() {
        val plan = plan(StorageTarget.LOCAL, emptyList(), emptySet(), listOf(local(other, at = now - 30_000L)))
        assertTrue(plan.forgetCandidates.isEmpty())
    }

    @Test
    fun `Android's own missing-file report counts as gone`() {
        val e = IllegalArgumentException(
            "Failed to determine if primary:Recordings/x.ogg is child of primary:Recordings: " +
                "java.io.FileNotFoundException: Missing file for primary:Recordings/x.ogg",
        )
        assertTrue(DeviceFolderRescan.isMissingFileReport(e))
    }

    @Test
    fun `any other bad-argument error keeps the copy`() {
        assertEquals(false, DeviceFolderRescan.isMissingFileReport(IllegalArgumentException("Invalid URI")))
    }
}
