/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

/**
 * The "All calls" list: every call in the system call log, lined up with the recordings CallVault made.
 *
 * Android logs phone calls only, so an app call (WhatsApp, Telegram) appears here only as the recording
 * CallVault made of it; an unrecorded app call cannot be listed. A recording with no call-log entry (an
 * app call, or a phone call older than the log keeps) is still listed, as recorded.
 */
object CallHistory {

    /** One row of the list: a recorded call (with its recording) or a call that was not recorded. */
    sealed interface Entry {
        val at: Long

        /** A call CallVault recorded. [call] is its call-log entry, when there is one. */
        data class Recorded<R>(val recording: R, override val at: Long, val call: PhoneCall?) : Entry

        /** A call in the log with no recording: answered but not recorded, missed, or declined. */
        data class NotRecorded(val call: PhoneCall) : Entry {
            override val at: Long get() = call.startedAt
        }
    }

    /** What the filter chips show. */
    enum class Filter { ALL, RECORDED, NOT_RECORDED, MISSED }

    /**
     * Builds the list, newest first.
     *
     * @param recordings  every recording, with its start time (null when unknown) and whether it is a phone
     *   call (only phone calls can match a call-log entry).
     * @param startOf     a recording's start time, or null when it has none.
     * @param isPhoneCall whether a recording is of a phone call rather than an app call or an import.
     */
    fun <R> build(
        calls: List<PhoneCall>,
        recordings: List<R>,
        startOf: (R) -> Long?,
        isPhoneCall: (R) -> Boolean,
    ): List<Entry> {
        val unmatched = recordings.filter { isPhoneCall(it) && startOf(it) != null }.toMutableList()
        val entries = ArrayList<Entry>(calls.size + recordings.size)
        // Newest call first; each recording can stand for one call only.
        for (call in calls.sortedByDescending { it.startedAt }) {
            val answered = call.kind == CallKind.INCOMING || call.kind == CallKind.OUTGOING
            val match = if (!answered) null else unmatched
                .map { it to kotlin.math.abs(startOf(it)!! - call.startedAt) }
                .filter { it.second <= PhoneCallMatch.TOLERANCE_MS }
                .minByOrNull { it.second }?.first
            if (match != null) {
                unmatched.remove(match)
                entries += Entry.Recorded(match, startOf(match)!!, call)
            } else {
                entries += Entry.NotRecorded(call)
            }
        }
        val matched = entries.filterIsInstance<Entry.Recorded<*>>().map { it.recording }.toSet()
        recordings.filter { it !in matched }.forEach { rec ->
            entries += Entry.Recorded(rec, startOf(rec) ?: 0L, null)
        }
        return entries.sortedByDescending { it.at }
    }

    /** Applies a filter chip. */
    fun filter(entries: List<Entry>, filter: Filter): List<Entry> = when (filter) {
        Filter.ALL -> entries
        Filter.RECORDED -> entries.filter { it is Entry.Recorded<*> }
        Filter.NOT_RECORDED -> entries.filter {
            it is Entry.NotRecorded && (it.call.kind == CallKind.INCOMING || it.call.kind == CallKind.OUTGOING)
        }
        Filter.MISSED -> entries.filter {
            it is Entry.NotRecorded && (it.call.kind == CallKind.MISSED || it.call.kind == CallKind.REJECTED)
        }
    }
}
