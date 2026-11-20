/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which sidecars may go when the last copy of a recording is deleted (C41).
 *
 * Deleting a stranger's or a surviving recording's text is worse than leaving clutter, so the plan only ever names a
 * sidecar of an audio name we were told is gone, and keeps it while any audio file with the same base is still there.
 */
class RecordingSidecarsPlanTest {

    private val name = "20240101_101010.000+0200_in_0501111111.ogg"
    private val md = "20240101_101010.000+0200_in_0501111111.md"
    private val json = "20240101_101010.000+0200_in_0501111111.json"

    @Test
    fun `bulk delete names include the audio, the transcript sidecar and the details json`() {
        val names = RecordingSidecars.namesToDelete(listOf(name))

        assertEquals(setOf(name, md, json), names)
    }

    @Test
    fun `both sidecars of an orphaned recording are planned when the folder holds no audio of that name`() {
        val plan = RecordingSidecars.orphanedSidecarsIn(listOf(md, json, "other.ogg"), listOf(name))

        assertEquals(setOf(md, json), plan)
    }

    @Test
    fun `a sidecar stays while the same audio is still in that folder`() {
        val plan = RecordingSidecars.orphanedSidecarsIn(listOf(name, md, json), listOf(name))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a sidecar stays while a same-base audio of another extension is still there`() {
        val sibling = "20240101_101010.000+0200_in_0501111111.m4a"

        val plan = RecordingSidecars.orphanedSidecarsIn(listOf(sibling, md, json), listOf(name))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `a sidecar of a recording that was not deleted is never planned`() {
        val plan = RecordingSidecars.orphanedSidecarsIn(listOf("keep.md", "keep.json", md), listOf(name))

        assertEquals(setOf(md), plan)
    }

    @Test
    fun `nothing is planned for an empty request`() {
        assertTrue(RecordingSidecars.orphanedSidecarsIn(listOf(md, json), emptyList()).isEmpty())
    }
}
