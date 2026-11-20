/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Who may run now, across every [TranscriptionWorker] in this process (review C124).
 *
 * An after-call run is its own unique work, so it no longer queues behind (or in front of) a tapped
 * run in one WorkManager chain; this gate is what keeps the unattended run from decoding on top of
 * another run instead. A worker that is refused returns retry and is asked again later.
 *
 * - [Kind.AFTER_CALL]: enters only when nothing else is running.
 * - [Kind.TAP]: enters unless an after-call run is running (a tap never waits for a sweep: it did not before).
 * - [Kind.SWEEP]: always enters, as before.
 */
internal object TranscriptionRunGate {

    enum class Kind { AFTER_CALL, TAP, SWEEP }

    private val lock = Any()
    private var active = 0
    private var afterCallActive = false

    /** True when [kind] may run now; the caller must then call [leave] exactly once. */
    fun tryEnter(kind: Kind): Boolean = synchronized(lock) {
        val allowed = when (kind) {
            Kind.AFTER_CALL -> active == 0
            Kind.TAP -> !afterCallActive
            Kind.SWEEP -> true
        }
        if (allowed) {
            active++
            if (kind == Kind.AFTER_CALL) afterCallActive = true
        }
        allowed
    }

    fun leave(kind: Kind) = synchronized(lock) {
        if (active > 0) active--
        if (kind == Kind.AFTER_CALL) afterCallActive = false
    }

    /** Whether this is the only run in the process, i.e. the one that owns the engine (review C179). */
    fun isOnlyRun(): Boolean = synchronized(lock) { active <= 1 }

    /** Test seam. */
    fun reset() = synchronized(lock) { active = 0; afterCallActive = false }
}
