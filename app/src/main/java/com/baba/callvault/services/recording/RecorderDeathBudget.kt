/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Review R7 step 2: stops a recorder that keeps dying seconds after it starts from being relaunched back to back.
 *
 * A death of a recorder that lived under [shortLifeMs] counts. [maxShortDeaths] of them inside [windowMs] trips the
 * budget: the caller then leaves the next relaunch to the normal once-a-minute tick and counts the death as a failed
 * attempt, so the "stuck" notice can show. A recorder that lived longer clears the count. The caller never applies the
 * verdict during a call or a ring (see [CallActivity]).
 */
class RecorderDeathBudget(
    private val maxShortDeaths: Int = MAX_SHORT_DEATHS,
    private val windowMs: Long = WINDOW_MS,
    private val shortLifeMs: Long = SHORT_LIFE_MS,
) {
    data class Verdict(val tripped: Boolean, val firstOfRun: Boolean, val shortDeathsInWindow: Int)

    private var connectedAtMs: Long? = null
    private var shortDeaths: List<Long> = emptyList()
    private var inRun = false

    @Synchronized
    fun onConnected(now: Long) {
        connectedAtMs = now
    }

    @Synchronized
    fun onDeath(now: Long): Verdict {
        val connectedAt = connectedAtMs
        connectedAtMs = null
        // A death with no known connect: nothing to say about how long it lived.
        if (connectedAt == null) return Verdict(tripped = false, firstOfRun = false, shortDeathsInWindow = shortDeaths.size)
        if (now - connectedAt >= shortLifeMs) {
            shortDeaths = emptyList()
            inRun = false
            return Verdict(tripped = false, firstOfRun = false, shortDeathsInWindow = 0)
        }
        shortDeaths = (shortDeaths + now).filter { now - it <= windowMs }
        val tripped = shortDeaths.size >= maxShortDeaths
        val first = tripped && !inRun
        inRun = tripped
        return Verdict(tripped, first, shortDeaths.size)
    }

    companion object {
        /** Loose on purpose: a screen-off incoming call must not meet a cold recorder because of a few quick deaths. */
        const val MAX_SHORT_DEATHS = 3
        const val WINDOW_MS = 10 * 60_000L
        const val SHORT_LIFE_MS = 60_000L
    }
}
