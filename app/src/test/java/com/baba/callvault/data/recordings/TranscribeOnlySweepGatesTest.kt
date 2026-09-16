/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import androidx.core.net.toUri
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.merge.MergeCandidates
import com.baba.callvault.system.health.SyncHealthPolicy
import com.baba.callvault.system.storage.CloudCopyPolicy
import com.baba.callvault.system.storage.RetentionPolicy
import com.baba.callvault.system.storage.StorageCapPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every gate that can move or destroy a file in the recordings folder, asked about the NEW shape.
 *
 * Phase 1 taught all of these about `_import`, and the transcribe-only variant adds a token after
 * that marker rather than replacing it, precisely so none of them has to be taught anything. This
 * test is what makes that claim checkable: each gate here is a place where a wrong answer uploads
 * somebody's private voice note to their cloud, or deletes the only copy of it, silently.
 *
 * Kept together in one file, by hazard rather than by package, because the thing worth proving is
 * that the set is complete for this name — not that any one predicate works, which its own test
 * already covers.
 */
@RunWith(RobolectricTestRunner::class)
// Uri.parse needs a real implementation for the merge gate's row. Robolectric 4.14 tops out at
// SDK 35 while the app targets 36, same as MergeCandidatesTest beside it.
@Config(sdk = [35])
class TranscribeOnlySweepGatesTest {

    private val transcribeOnly = ImportedRecording.nameFor(
        importedAtMillis = 1_789_567_810_000L,
        label = "voice note",
        extension = ".opus",
        kind = ImportedRecording.Kind.TRANSCRIBE_ONLY,
    )

    private val call = "20260916_101010.123+0300_in_5551234.ogg"

    @Test
    fun `it is an import first, which is what every gate below actually asks`() {
        assertTrue(transcribeOnly, ImportedRecording.isImported(transcribeOnly))
    }

    @Test
    fun `it is never uploaded to Drive`() {
        assertFalse(CloudCopyPolicy.mayGoToCloud(transcribeOnly))
        assertTrue(CloudCopyPolicy.mayGoToCloud(call))
    }

    @Test
    fun `it is never deleted for being old`() {
        assertFalse(RetentionPolicy.agesOut(transcribeOnly))
        assertFalse(RetentionPolicy.isEligible(transcribeOnly, startedAtMillis = 1L))
        assertTrue(RetentionPolicy.isEligible(call, startedAtMillis = 1L))
    }

    @Test
    fun `it is never evicted by the storage cap`() {
        // The cap deletes device copies oldest-first, which for a call costs nothing that matters —
        // the Drive copy survives and the row keeps its transcript. An import has no Drive copy, so
        // the same eviction is permanent deletion of the only copy.
        val kept = StorageCapPolicy.Candidate(
            displayName = transcribeOnly,
            sizeBytes = 10L,
            lastModified = 1L,
            isFavourite = false,
            isImported = ImportedRecording.isImported(transcribeOnly),
        )
        val evictable = StorageCapPolicy.Candidate(
            displayName = call,
            sizeBytes = 10L,
            lastModified = 1L,
            isFavourite = false,
            isImported = ImportedRecording.isImported(call),
        )

        val evicted = StorageCapPolicy.selectForEviction(listOf(kept, evictable), capBytes = 1L)

        assertEquals(listOf(call), evicted)
    }

    @Test
    fun `it is never offered as part of a merge`() {
        assertFalse(MergeCandidates.canMerge(item(transcribeOnly)))
        assertTrue(MergeCandidates.canMerge(item(call)))
    }

    @Test
    fun `it never makes the Drive health check cry wolf`() {
        // It has a device copy and no Drive copy for ever, by design. Counted, it would eventually
        // tell the user their backup had stopped — the false positive two users hit on 2.2.0.
        assertFalse(
            SyncHealthPolicy.countsAsUnsynced(transcribeOnly, hasLocalCopy = true, hasDriveCopy = false)
        )
        assertTrue(
            SyncHealthPolicy.countsAsUnsynced(call, hasLocalCopy = true, hasDriveCopy = false)
        )
    }

    @Test
    fun `it survives a catalog re-seed in every storage mode`() {
        // In DRIVE mode the re-seed enumerates the Drive folder alone, and an import is never there.
        // Without this exemption a re-seed would drop it from the app while the file sat in the
        // folder — and for a transcribe-only file, which is not in Recordings either, that is a file
        // in no list at all with nothing to bring it back.
        StorageTarget.entries.forEach { target ->
            assertTrue(
                "target=$target",
                RecordingsRepository.seedsFromDevice(target, transcribeOnly),
            )
        }
    }

    private fun item(displayName: String) = RecordingsRepository.RecordingItem(
        uri = "content://x/$displayName".toUri(),
        displayName = displayName,
        sizeBytes = 1L,
        lastModified = 1L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = null,
    )
}
