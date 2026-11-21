/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import com.baba.callvault.data.recordings.RecordingDirection

/**
 * The number of a call that arrived without one, read from the call log once the call has ended (review C59).
 * Only a call that began within [HonorCallStart.MAX_RING_MS] before the recording counts: without that, a late log row
 * made the newest OLDER call's number the name of this recording, labelling it as the wrong person. Pure.
 */
object LateNumberFromLog {

    data class Row(val date: Long, val number: String, val direction: RecordingDirection)

    /** The earliest call-log DATE worth reading for a recording that started at [recordingStartMillis]. */
    fun since(recordingStartMillis: Long): Long = recordingStartMillis - HonorCallStart.MAX_RING_MS

    /**
     * The newest row of [direction]; its number, which is blank when the log confirms the call was anonymous. Null when
     * no row belongs to this call, so the recording keeps the name it has.
     */
    fun pick(rows: List<Row>, direction: RecordingDirection, recordingStartMillis: Long): String? =
        rows.filter { it.direction == direction }
            .filter { it.date in since(recordingStartMillis)..(recordingStartMillis + HonorCallStart.CLOCK_SLACK_MS) }
            .maxByOrNull { it.date }
            ?.number
}
