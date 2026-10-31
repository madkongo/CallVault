/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

/**
 * The call before and after this one, for the reader's Previous / Next cards (#27).
 *
 * @property displayName which recording to open.
 * @property label the name the list shows for it.
 * @property date its date, or null when the catalog has no row for it.
 */
data class TranscriptNeighbour(
    val displayName: String,
    val label: String,
    val date: String?,
)

/**
 * Where a transcript sits in the list it was opened from.
 *
 * The order is the list as the user saw it when they tapped — its sort, filters and search — taken
 * once at that moment, so reading does not reshuffle under them while a transcription finishes elsewhere.
 */
object TranscriptNeighbours {

    /**
     * The names either side of [current] in [order], or nulls at the ends. A [current] that is not in
     * [order] (opened from somewhere with no list, or since deleted) has no neighbours.
     */
    fun around(order: List<String>, current: String): Pair<String?, String?> {
        val i = order.indexOf(current)
        if (i < 0) return null to null
        return order.getOrNull(i - 1) to order.getOrNull(i + 1)
    }
}
