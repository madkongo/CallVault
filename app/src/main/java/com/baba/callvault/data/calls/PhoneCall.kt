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
     * How much later than the recording's start a call-log start may be and still be the call that just finished.
     */
    const val JUST_ENDED_START_SLOP_MS = 25_000L

    /**
     * How much EARLIER than the recording's start the call-log start may be. The call log's DATE is when the call
     * began ringing or was dialled; the recording can start only at the answer (an incoming call, or
     * record-from-answer, which waits up to two minutes for the far end). So the early side allows ring time.
     */
    const val JUST_ENDED_EARLY_RING_MS = 150_000L

    /**
     * A row whose logged end (DATE + DURATION) is more than this before the recording started is an earlier call,
     * not this one. DURATION counts from the answer, so for this call DATE + DURATION falls short of its real end by
     * the ring time; a call whose ring was much longer than its talk is left unknown (and so transcribed).
     */
    const val JUST_ENDED_END_SLOP_MS = 40_000L

    /** The nearest answered call within [TOLERANCE_MS] of [startedAt], or null. */
    fun nearest(calls: List<PhoneCall>, startedAt: Long): PhoneCall? =
        closest(calls, startedAt, TOLERANCE_MS)

    /**
     * The answered call that belongs to a recording which has just ended, or null.
     *
     * Its start may be up to [JUST_ENDED_START_SLOP_MS] after the recording's start, and before it by the same
     * amount, or by up to [JUST_ENDED_EARLY_RING_MS] when [allowRingTime] (incoming answer, record-from-answer).
     * Its logged end may not be more than [JUST_ENDED_END_SLOP_MS] before the recording started. A row with a newer
     * answered row after it (up to the latest start this call's own row could have) is an earlier call, never this
     * one. Callers ask without ring time first: Telecom writes this call's row late, and until it is there an older
     * row inside the ring-time window may be a previous call on another SIM (re-review #A).
     */
    fun justEnded(calls: List<PhoneCall>, recordingStartedAt: Long, allowRingTime: Boolean = true): PhoneCall? {
        val latestStart = recordingStartedAt + JUST_ENDED_START_SLOP_MS
        val earliestStart = recordingStartedAt - if (allowRingTime) JUST_ENDED_EARLY_RING_MS else JUST_ENDED_START_SLOP_MS
        val answered = calls.filter { it.kind == CallKind.INCOMING || it.kind == CallKind.OUTGOING }
        return answered.asSequence()
            .filter { it.startedAt in earliestStart..latestStart }
            .filter { it.startedAt + it.durationSeconds * 1_000 >= recordingStartedAt - JUST_ENDED_END_SLOP_MS }
            .filter { row -> answered.none { it !== row && it.startedAt > row.startedAt && it.startedAt <= latestStart } }
            .minByOrNull { kotlin.math.abs(it.startedAt - recordingStartedAt) }
    }

    private fun closest(calls: List<PhoneCall>, startedAt: Long, slopMs: Long): PhoneCall? =
        calls.asSequence()
            .filter { it.kind == CallKind.INCOMING || it.kind == CallKind.OUTGOING }
            .map { it to kotlin.math.abs(it.startedAt - startedAt) }
            .filter { it.second <= slopMs }
            .minByOrNull { it.second }
            ?.first
}
