/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.system.storage.StorageCapPolicy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StorageCapPolicy.selectForEviction] decides which of the user's recordings are destroyed, so every
 * rule it has is pinned here — most importantly the ones that say *not* to delete.
 */
class StorageCapPolicyTest {

    @Test
    fun `selects nothing when the cap is off`() {
        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(library(), capBytes = 0L))
    }

    @Test
    fun `selects nothing when the library is already under the cap`() {
        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(library(), capBytes = 1_000L))
    }

    @Test
    fun `selects nothing when the library is exactly at the cap`() {
        // 100 + 100 + 100 = 300. At the cap is under it; "no more than 300" must not delete at 300.
        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(library(), capBytes = 300L))
    }

    @Test
    fun `evicts the oldest first`() {
        // Needs to shed 100 bytes to reach 200. The oldest alone is enough.
        assertEquals(listOf("old.m4a"), StorageCapPolicy.selectForEviction(library(), capBytes = 200L))
    }

    @Test
    fun `stops as soon as it is under the cap`() {
        // Shedding the oldest two reaches 100. The newest must survive, even though continuing to
        // delete would put the library further under the cap.
        assertEquals(
            listOf("old.m4a", "mid.m4a"),
            StorageCapPolicy.selectForEviction(library(), capBytes = 100L)
        )
    }

    @Test
    fun `never evicts a starred recording`() {
        // The oldest is starred, so the eviction skips it and takes the next-oldest instead.
        val starred = library(favourites = setOf("old.m4a"))

        assertEquals(listOf("mid.m4a"), StorageCapPolicy.selectForEviction(starred, capBytes = 200L))
    }

    @Test
    fun `goes over the cap rather than deleting a starred recording`() {
        // Everything is starred and the library is far over. The correct answer is to delete nothing
        // and stay over the cap: a star is the user's explicit instruction about one recording, and
        // the cap is a general preference. The specific instruction wins.
        val allStarred = library(favourites = setOf("old.m4a", "mid.m4a", "new.m4a"))

        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(allStarred, capBytes = 50L))
    }

    @Test
    fun `counts starred recordings toward the total`() {
        // 'new' is starred and cannot go, but its 100 bytes still count. Reaching a 150 cap therefore
        // takes BOTH unstarred recordings, not just the oldest. If starred bytes were excluded the
        // policy would think 200 was already under 150 and delete nothing.
        val starredNewest = library(favourites = setOf("new.m4a"))

        assertEquals(
            listOf("old.m4a", "mid.m4a"),
            StorageCapPolicy.selectForEviction(starredNewest, capBytes = 150L)
        )
    }

    @Test
    fun `is stable when timestamps tie`() {
        val tied = listOf(
            Candidate("b.m4a", 100L, lastModified = 5L, isFavourite = false),
            Candidate("a.m4a", 100L, lastModified = 5L, isFavourite = false)
        )

        assertEquals(listOf("a.m4a"), StorageCapPolicy.selectForEviction(tied, capBytes = 100L))
    }

    @Test
    fun `never evicts a recording whose size is unknown`() {
        // SafHelper reports -1 for "size unknown". Deleting it frees nothing the policy can account
        // for, so it would be taken AND the sweep would carry on to the next recording — destroying
        // more than the cap asked for. Only the measurable one goes.
        val unknown = listOf(
            Candidate("unknown.m4a", -1L, lastModified = 1L, isFavourite = false),
            Candidate("big.m4a", 300L, lastModified = 2L, isFavourite = false)
        )

        assertEquals(listOf("big.m4a"), StorageCapPolicy.selectForEviction(unknown, capBytes = 100L))
    }

    @Test
    fun `a negative size never credits the total`() {
        // Summed raw, -1 would subtract from the total and could talk the policy out of a sweep that
        // was genuinely needed. 300 + (-1) must read as 300, not 299.
        val unknown = listOf(
            Candidate("unknown.m4a", -1L, lastModified = 1L, isFavourite = false),
            Candidate("big.m4a", 300L, lastModified = 2L, isFavourite = false)
        )

        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(unknown, capBytes = 300L))
    }

    @Test
    fun `never evicts an imported file`() {
        // For a call the cap deletes the device copy and the Drive copy survives, so nothing is
        // destroyed. An import has no Drive copy by design, so evicting it is not freeing space —
        // it is destroying the only copy of something the user handed us, and its transcript with
        // it. The star does not cover this: it protects what somebody remembered to protect.
        val withImport = listOf(
            Candidate("20260916_101010.123+0300_import_note.ogg", 100L, 1L, isFavourite = false, isImported = true),
            Candidate("mid.m4a", 100L, lastModified = 2L, isFavourite = false),
            Candidate("new.m4a", 100L, lastModified = 3L, isFavourite = false)
        )

        // It is the oldest, so without the exemption it would be the first thing taken.
        assertEquals(listOf("mid.m4a"), StorageCapPolicy.selectForEviction(withImport, capBytes = 200L))
    }

    @Test
    fun `goes over the cap rather than deleting an imported file`() {
        val onlyImports = listOf(
            Candidate("20260916_101010.123+0300_import_a.ogg", 100L, 1L, isFavourite = false, isImported = true),
            Candidate("20260916_101011.123+0300_import_b.ogg", 100L, 2L, isFavourite = false, isImported = true)
        )

        assertEquals(emptyList<String>(), StorageCapPolicy.selectForEviction(onlyImports, capBytes = 50L))
    }

    @Test
    fun `counts imported files toward the total`() {
        // They occupy the phone. Pretending they do not would let a library of imports sit far over
        // the cap while the sweep deleted calls that were not the problem — and would also stop the
        // sweep running at all once the total looked small enough.
        val withImport = listOf(
            Candidate("20260916_101010.123+0300_import_note.ogg", 250L, 1L, isFavourite = false, isImported = true),
            Candidate("new.m4a", 100L, lastModified = 3L, isFavourite = false)
        )

        assertEquals(listOf("new.m4a"), StorageCapPolicy.selectForEviction(withImport, capBytes = 300L))
    }

    private fun library(favourites: Set<String> = emptySet()) = listOf(
        Candidate("old.m4a", 100L, lastModified = 1L, isFavourite = "old.m4a" in favourites),
        Candidate("mid.m4a", 100L, lastModified = 2L, isFavourite = "mid.m4a" in favourites),
        Candidate("new.m4a", 100L, lastModified = 3L, isFavourite = "new.m4a" in favourites)
    )
}
