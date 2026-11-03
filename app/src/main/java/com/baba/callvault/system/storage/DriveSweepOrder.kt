/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

/**
 * The order the scheduled Drive copy uploads in: newest first.
 *
 * It used to follow the folder listing, which is name order. With a contact-first file name (a Honor phone,
 * 2026-11-03) that put a backlog of old calls ahead of today's, a run cut off part-way never reached the
 * recent ones, and the "not reaching Drive" warning fired about exactly those. Newest first backs up what
 * the user is most likely to need, and whatever a run does not reach is picked up by the next one, together
 * with any new calls: every run lists the folder afresh and skips what Drive already holds.
 */
object DriveSweepOrder {

    /** A recording still to be uploaded. [lastModified] is 0 when the provider does not know it. */
    data class Pending<T>(val name: String, val lastModified: Long, val item: T)

    /** Newest first; an undated file (0) goes last, and equal stamps keep a stable name order. */
    fun <T> newestFirst(pending: List<Pending<T>>): List<Pending<T>> =
        pending.sortedWith(compareByDescending<Pending<T>> { it.lastModified }.thenBy { it.name })
}
