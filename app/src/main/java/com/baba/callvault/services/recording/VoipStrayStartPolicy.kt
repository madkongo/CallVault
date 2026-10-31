/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager

/**
 * Whether an app-call start is a real app call or the tail of a carrier call (#45).
 *
 * On Samsung a Wi-Fi call keeps the mode at MODE_IN_COMMUNICATION. At hang-up Telecom drops the call
 * about 1.5 s before the mode leaves, so [VoipTelephonyGate] let a start through and every Wi-Fi call
 * left a stray 1 s `_voip` file. A "carrier episode" latch closes that window: it is set when a start
 * is refused because a carrier call is up, and cleared when the mode leaves IN_COMMUNICATION or
 * [TAIL_MS] after the carrier call has gone, whichever is first. The tail is what lets a WhatsApp call
 * that carries on after the phone call ended still be recorded.
 */
object VoipStrayStartPolicy {

    /** Hang-up protection after the managed call drops. Samsung leaves the mode ~1.5 s later. */
    const val TAIL_MS = 3_000L

    /** @param managedEndedAt when the managed call was first seen gone (elapsedRealtime), or null. */
    data class Latch(val active: Boolean = false, val managedEndedAt: Long? = null)

    enum class Verdict {
        START,
        /** Telecom has a carrier call up. */
        REFUSE_MANAGED_CALL,
        /** Same IN_COMMUNICATION stretch as a carrier call that just ended. */
        REFUSE_CARRIER_EPISODE,
    }

    /**
     * Deliberately not "our carrier recorder is running": on One UI a WhatsApp call raises OFFHOOK
     * ~230 ms before the mode changes, so the carrier service is up for a genuine app call too.
     */
    fun verdict(latched: Boolean, managedCall: Boolean): Verdict = when {
        managedCall -> Verdict.REFUSE_MANAGED_CALL
        latched -> Verdict.REFUSE_CARRIER_EPISODE
        else -> Verdict.START
    }

    /** The latch after a mode event: leaving IN_COMMUNICATION ends the episode. */
    fun afterMode(latch: Latch, mode: Int): Latch =
        if (mode == AudioManager.MODE_IN_COMMUNICATION) latch else Latch()

    /** The latch as of [nowMs]: a managed call keeps it, its absence starts and then ends the tail. */
    fun afterTime(latch: Latch, managedCall: Boolean, nowMs: Long): Latch = when {
        !latch.active -> latch
        managedCall -> Latch(active = true)
        latch.managedEndedAt == null -> Latch(active = true, managedEndedAt = nowMs)
        nowMs - latch.managedEndedAt >= TAIL_MS -> Latch()
        else -> latch
    }

    /** The latch after a start was judged: a managed-call refusal starts an episode. */
    fun afterVerdict(latch: Latch, verdict: Verdict): Latch =
        if (verdict == Verdict.REFUSE_MANAGED_CALL) Latch(active = true) else latch

    /** How long until the tail ends, so the caller can look again then; null when not in a tail. */
    fun tailRemainingMs(latch: Latch, nowMs: Long): Long? =
        latch.managedEndedAt?.takeIf { latch.active }?.let { (TAIL_MS - (nowMs - it)).coerceAtLeast(0L) }
}
