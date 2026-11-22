/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

/** What kind of call the system call log says it was. */
enum class CallKind { INCOMING, OUTGOING, MISSED, REJECTED, OTHER }

/**
 * One call from the system call log, with the fields the call history and the SIM label need.
 *
 * Android types are left out so it stays testable. The account fields name the SIM the call went
 * through (the call log's PHONE_ACCOUNT_COMPONENT_NAME and PHONE_ACCOUNT_ID); null when the log has none.
 */
data class PhoneCall(
    val startedAt: Long,
    val durationSeconds: Long,
    val kind: CallKind,
    val number: String? = null,
    val cachedName: String? = null,
    val accountComponent: String? = null,
    val accountId: String? = null,
)

/** Matches a recording's start time to the call it recorded. */
object PhoneCallMatch {

    /** How far a recording's start may sit from the call log's time and still be that call. */
    const val TOLERANCE_MS = 120_000L

    /**
     * How close a call-log start must be to count as the call that just finished.
     *
     * Tighter than [TOLERANCE_MS] on purpose. Two calls a minute apart are both inside two minutes,
     * so [nearest] will claim the previous call while this call's row is not in the log yet. A
     * keep-or-delete decision must not do that.
     */
    const val JUST_ENDED_START_SLOP_MS = 25_000L

    /** The nearest answered call within [TOLERANCE_MS] of [startedAt], or null. */
    fun nearest(calls: List<PhoneCall>, startedAt: Long): PhoneCall? =
        closest(calls, startedAt, TOLERANCE_MS)

    /**
     * The answered call that belongs to a recording which has just ended, or null.
     *
     * Only a start within [JUST_ENDED_START_SLOP_MS] qualifies. A previous call a minute earlier
     * is inside [nearest]'s window and is ignored here, so a missing new row stays "unknown"
     * instead of inheriting the previous call's SIM.
     */
    fun justEnded(calls: List<PhoneCall>, recordingStartedAt: Long): PhoneCall? =
        closest(calls, recordingStartedAt, JUST_ENDED_START_SLOP_MS)

    private fun closest(calls: List<PhoneCall>, startedAt: Long, slopMs: Long): PhoneCall? =
        calls.asSequence()
            .filter { it.kind == CallKind.INCOMING || it.kind == CallKind.OUTGOING }
            .map { it to kotlin.math.abs(it.startedAt - startedAt) }
            .filter { it.second <= slopMs }
            .minByOrNull { it.second }
            ?.first
}
