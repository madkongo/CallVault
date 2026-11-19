/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test that decides whether a stored Drive reference is dead. Answering yes wrongly detaches a
 * recording the app can still reach; answering no wrongly leaves it undeletable for ever. The strings
 * below are the real ones from the maintainer's device on 2026-08-04, where Drive renumbered the account
 * slot from 1 to 4 and invalidated every URI the catalog held.
 */
class DriveCatalogRepairTest {

    private val liveFolder =
        "content://com.google.android.apps.docs.storage/tree/acc%3D4%3Bdoc%3Dencoded%3DjJiXSZaxhTcgKgw1_bLT1uQW2uDvkbHzSzzkKua3Ynj7XUZSG2_i9Q%3D%3D"

    private val staleDocument =
        "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3D5UbRHSbKSkBJgrzG5czePxhhBp7ITr-w9on569eHE2Bkn1gZAw2F/document/acc%3D1%3Bdoc%3Dencoded%3DvRjSJ1EK_hKA1OuwiNvqYo18WWEJk2AgWt2Jgm5KvXk7gMSfa8Zz"

    private val liveDocument =
        "content://com.google.android.apps.docs.storage/tree/acc%3D4%3Bdoc%3Dencoded%3DjJiXSZaxhTcgKgw1_bLT1uQW2uDvkbHzSzzkKua3Ynj7XUZSG2_i9Q%3D%3D/document/acc%3D4%3Bdoc%3Dencoded%3DsomeFileId"

    @Test
    fun `a document from the previous account slot is stale`() {
        assertTrue(DriveCatalogRepair.isStaleReference(staleDocument, liveFolder))
    }

    @Test
    fun `a document under the live grant is not stale`() {
        assertFalse(DriveCatalogRepair.isStaleReference(liveDocument, liveFolder))
    }

    @Test
    fun `the folder uri itself is not stale against itself`() {
        assertFalse(DriveCatalogRepair.isStaleReference(liveFolder, liveFolder))
    }

    @Test
    fun `a document from a different provider entirely is stale`() {
        val local = "content://com.android.externalstorage.documents/tree/primary%3ACallRecording/document/primary%3ACallRecording%2Fa.ogg"
        assertTrue(DriveCatalogRepair.isStaleReference(local, liveFolder))
    }

    @Test
    fun `a uri with no tree segment is left unjudged`() {
        // Not every stored URI is tree-scoped. Absent evidence, the safe answer is "not stale" — the cost
        // of a wrong yes is detaching a reachable recording.
        assertFalse(DriveCatalogRepair.isStaleReference("content://some.provider/document/42", liveFolder))
        assertFalse(DriveCatalogRepair.isStaleReference(staleDocument, "content://some.provider/document/42"))
    }

    @Test
    fun `nonsense input is left unjudged rather than treated as stale`() {
        assertFalse(DriveCatalogRepair.isStaleReference("", liveFolder))
        assertFalse(DriveCatalogRepair.isStaleReference(staleDocument, ""))
    }

    // ---- repairFor (review C8)

    private val row = com.baba.callvault.data.recordings.db.RecordingEntry("a.ogg", driveUri = staleDocument)

    @Test
    fun `a stale reference found by name is re-pointed`() {
        assertEquals(DriveCatalogRepair.Repair.REPOINT, DriveCatalogRepair.repairFor(row, setOf("a.ogg")))
    }

    @Test
    fun `a Drive-only row absent from the current folder is kept`() {
        assertEquals(DriveCatalogRepair.Repair.KEEP, DriveCatalogRepair.repairFor(row, setOf("b.ogg")))
    }

    @Test
    fun `a row with its device copy loses only the stale reference`() {
        val withLocal = row.copy(localUri = "content://dev/a.ogg")
        assertEquals(DriveCatalogRepair.Repair.DROP_REFERENCE, DriveCatalogRepair.repairFor(withLocal, setOf("b.ogg")))
    }

    @Test
    fun `an empty listing drops nothing`() {
        assertEquals(DriveCatalogRepair.Repair.KEEP, DriveCatalogRepair.repairFor(row.copy(localUri = "content://dev/a.ogg"), emptySet()))
    }
}
