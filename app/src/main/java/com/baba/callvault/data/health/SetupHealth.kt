/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

/** What the status card has to say about whether recording actually works. */
sealed interface SetupHealth {
    /** No call has proved anything yet — a fresh install, or nothing since the setup changed. */
    data object Unverified : SetupHealth

    data class Verified(val atMillis: Long) : SetupHealth

    /** It worked, but the setup has changed since; the next call will confirm it still does. */
    data object StaleAfterChange : SetupHealth

    data class LastCallFailed(val atMillis: Long, val reason: FailureReason, val callAtMillis: Long? = null) : SetupHealth

    /** A call happened that CallVault never saw at all. */
    data class CallNotRecorded(val atMillis: Long, val label: String?) : SetupHealth

    /**
     * A call the user expected recorded started while [prerequisite] was missing — a USER-OWNED,
     * excused cause (no folder, never paired, Developer options off, or the secure-settings grant
     * gone with no live daemon), reported precisely instead of reading as an unexplained
     * [CallNotRecorded]. Distinct from [CallNotRecorded] (and persisted separately in
     * [HealthFacts]) so a call that was genuinely never recordable never blurs into the daemon-died
     * case this whole feature exists to catch.
     */
    data class CallMissedNotReady(val atMillis: Long, val label: String?, val prerequisite: Prerequisite) : SetupHealth
}

/**
 * True for the states that should flip the card to a warning.
 *
 * [StaleAfterChange] is deliberately excluded: a setup change (e.g. an app update bumping the build's
 * version code) is informational, not proof that anything is broken. It just means the next call
 * hasn't re-confirmed the new configuration yet. Painting that as a warning would raise a false alarm
 * on every update, exactly the "cried wolf" outcome this whole feature exists to avoid.
 */
val SetupHealth.isProblem: Boolean
    get() = this is SetupHealth.LastCallFailed ||
        this is SetupHealth.CallNotRecorded ||
        this is SetupHealth.CallMissedNotReady

object SetupHealthDeriver {

    /**
     * First match wins, most urgent first: a call that vanished entirely (whether unexplained or
     * because a prerequisite was missing at the time), then a call that failed, then a setup change
     * that makes an old verification stop speaking for the current configuration.
     *
     * The gap, the missed-while-not-ready entry, and the failure are all read from [facts] rather than
     * passed in transiently: any of them at or older than [HealthFacts.lastVerifiedAt] is spent — a
     * later call proved things work again — but each must still be POSSIBLE to outlive the sweep pass
     * (or call start) that found it. Passing a transient "newest gap this pass" made the sweep's own
     * watermark advance erase the warning it had just raised, the moment the next resume re-swept past
     * it.
     *
     * [CallNotRecorded] and [CallMissedNotReady] share the SAME precedence — both mean "a call CallVault
     * never observed" — so whichever of the two is newer wins when both are active; a tie prefers
     * [CallMissedNotReady] since it names a cause rather than leaving the user to wonder.
     */
    fun derive(facts: HealthFacts, currentFingerprint: String): SetupHealth {
        val gapActive = facts.lastGapAt > facts.lastVerifiedAt
        val notReadyPrerequisite = facts.lastNotReadyPrerequisite
        val notReadyActive = notReadyPrerequisite != null && facts.lastNotReadyAt > facts.lastVerifiedAt

        if (notReadyActive && (!gapActive || facts.lastNotReadyAt >= facts.lastGapAt)) {
            return SetupHealth.CallMissedNotReady(facts.lastNotReadyAt, facts.lastNotReadyLabel, notReadyPrerequisite)
        }
        if (gapActive) {
            return SetupHealth.CallNotRecorded(facts.lastGapAt, facts.lastGapLabel)
        }

        val reason = facts.lastFailureReason
        if (reason != null && facts.lastFailureAt > facts.lastVerifiedAt) {
            return SetupHealth.LastCallFailed(facts.lastFailureAt, reason, facts.lastFailureCallAt.takeIf { it > 0L })
        }
        if (facts.lastVerifiedAt <= 0L) return SetupHealth.Unverified
        if (facts.verifiedFingerprint != currentFingerprint) return SetupHealth.StaleAfterChange
        return SetupHealth.Verified(facts.lastVerifiedAt)
    }
}
