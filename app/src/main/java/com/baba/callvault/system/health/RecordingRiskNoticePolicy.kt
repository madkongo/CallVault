/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.system.health

/**
 * The rules for the one background notice about things that can stop calls being recorded (R5). Pure, so each rule
 * is tested without a phone. The Android side is [RecordingRiskNotice].
 *
 * - A check runs once a day on the keep-alive tick, and at boot.
 * - The notice is posted only when the set of reasons differs from the set last posted, and at most once a day.
 *   A set the user already saw is never posted again, even if they swiped it away.
 * - It is cancelled as soon as no reason holds, or when recording is switched off by the user.
 */
object RecordingRiskNoticePolicy {

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** What was last done: when it last checked, when it last posted, and the set it posted. */
    data class State(
        val lastCheckAt: Long = 0L,
        val lastPostedAt: Long = 0L,
        val lastPostedSet: Set<RiskReason> = emptySet(),
    )

    sealed interface Decision {
        /** Post, or [updating] the notice that is already up, with these reasons. */
        data class Post(val reasons: List<RiskReason>, val updating: Boolean) : Decision
        data class Cancel(val why: String) : Decision
        data class Skip(val why: String) : Decision
    }

    /** Whether a periodic check may run: the first ever, one a day on, after the clock went back, or at boot. */
    fun isCheckDue(state: State, now: Long, atBoot: Boolean): Boolean {
        if (atBoot || state.lastCheckAt == 0L) return true
        val elapsed = now - state.lastCheckAt
        return elapsed < 0 || elapsed >= DAY_MS
    }

    /** [reasons] are the notice-eligible ones, in priority order. */
    fun decide(state: State, now: Long, reasons: List<RiskReason>, recordingOff: Boolean): Decision {
        if (recordingOff) return Decision.Cancel("recording is switched off by the user")
        if (reasons.isEmpty()) return Decision.Cancel("no reason holds")
        if (reasons.toSet() == state.lastPostedSet) return Decision.Skip("same reasons as the notice already posted")
        val sincePost = now - state.lastPostedAt
        if (state.lastPostedAt != 0L && sincePost in 0 until DAY_MS) return Decision.Skip("a notice was posted less than a day ago")
        return Decision.Post(reasons, updating = state.lastPostedSet.isNotEmpty())
    }

    fun nextState(state: State, decision: Decision, now: Long): State = when (decision) {
        is Decision.Post -> State(lastCheckAt = now, lastPostedAt = now, lastPostedSet = decision.reasons.toSet())
        is Decision.Cancel -> state.copy(lastCheckAt = now, lastPostedSet = emptySet())
        is Decision.Skip -> state.copy(lastCheckAt = now)
    }

    /** The notice's text: the reason itself when there is one, a bullet list when there are several. */
    fun noticeText(labels: List<String>): String =
        if (labels.size == 1) labels.first() else labels.joinToString("\n") { "• $it" }

    fun formatSet(set: Set<RiskReason>): String = set.joinToString(",") { it.name }

    /** Reads a stored set; a name this version does not know is dropped rather than failing. */
    fun parseSet(stored: String?): Set<RiskReason> =
        stored.orEmpty().split(',').mapNotNull { name -> RiskReason.values().firstOrNull { it.name == name } }.toSet()
}
