/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * Which recordings to delete to bring the on-device library back under a size cap.
 *
 * The age-based retention beside this answers "how long is a recording worth keeping"; this answers
 * "how much of this phone is CallVault allowed to fill". They are independent, and both run in the
 * same daily sweep — whichever condition trips first takes a recording. They are NOT stacked, so a
 * recording is never given the age period *and then* the cap before it goes.
 *
 * Device copies only. A Drive copy costs nothing on this phone, has its own quota, and deleting one
 * to free space on a device it is not stored on would be indefensible.
 *
 * Pure, and deliberately so: this decides which of the user's recordings are destroyed, and that
 * decision has to be provable at a desk rather than discovered on a phone.
 */
object StorageCapPolicy {

    /** The presets offered in Settings, in bytes. 0 is "no cap". */
    val PRESET_BYTES = listOf(0L, 1L shl 30, 2L shl 30, 5L * (1L shl 30), 10L * (1L shl 30))

    /**
     * One recording the cap may consider.
     *
     * @param sizeBytes    The device copy's size. A recording with no device copy is not a candidate
     *                     and should not be passed in at all.
     * @param lastModified Used only for ordering — oldest goes first.
     * @param isFavourite  Starred by the user, and therefore never deleted by this policy.
     * @param isImported   A file the user brought in rather than a call; never deleted by this
     *                     policy. See [selectForEviction] rule 3 for why.
     */
    data class Candidate(
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val isFavourite: Boolean,
        val isImported: Boolean = false
    )

    /**
     * The names to delete, oldest first, to get the total at or under [capBytes].
     *
     * Rules, in the order they matter:
     *
     * 1. **A cap of zero or less is off.** Nothing is ever selected.
     * 2. **A starred recording is never selected**, even when that means the cap cannot be met. The
     *    star is the user saying "keep this"; a cap is the user saying "keep less". When the two
     *    disagree the explicit instruction about a specific recording wins over the general one, and
     *    the sweep goes over its cap rather than destroying something the user protected.
     * 3. **An imported file is never selected either.** For a call, evicting the device copy is a
     *    reasonable thing to do: the Drive copy survives, the row keeps its transcript, and nothing
     *    is destroyed — which is what makes the cap a safe setting to offer at all. An import has no
     *    Drive copy and never will (`CloudCopyPolicy` refuses it by design), so evicting one is not
     *    freeing space, it is destroying the only copy of something the user handed us, and taking
     *    its transcript with it. The star cannot be relied on to cover this: it protects what
     *    somebody remembered to protect, and nobody stars a file on the assumption that the app
     *    would otherwise delete the original.
     * 4. **Protected recordings still count toward the total.** They occupy the phone, and
     *    pretending they do not would let a library of starred or imported files sit far over the
     *    cap while the sweep deleted ordinary recordings that were not the problem.
     * 5. **Oldest first, and it stops the moment it is under.** Not one recording more.
     * 6. **A recording of unknown or zero size is skipped.** Deleting it cannot be shown to free
     *    anything, so the sweep would take it *and* carry on to the next one — destroying more than
     *    the cap ever asked for. In practice the sweep does not pass these in at all; the rule is
     *    here so the policy cannot be talked into an unaccountable delete.
     */
    fun selectForEviction(candidates: List<Candidate>, capBytes: Long): List<String> {
        if (capBytes <= 0L) return emptyList()

        var total = candidates.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        if (total <= capBytes) return emptyList()

        val evicted = mutableListOf<String>()
        // Oldest first; ties broken by name so the same library always yields the same decision —
        // a sweep that deleted a different recording on each run would be untestable and unexplainable.
        val order = candidates
            .filterNot { it.isFavourite || it.isImported }
            .filter { it.sizeBytes > 0L }
            .sortedWith(compareBy({ it.lastModified }, { it.displayName }))

        for (candidate in order) {
            if (total <= capBytes) break
            evicted += candidate.displayName
            total -= candidate.sizeBytes.coerceAtLeast(0L)
        }
        return evicted
    }
}
