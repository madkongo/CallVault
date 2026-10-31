/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.integrations.adb

/**
 * Remembers that no already-open adb tcp port answered, so the probe costs something once, not on every
 * connect (see [LoopbackPorts.alreadyOpenCandidates]).
 *
 * The miss is held **in memory only**, so it can never outlive the boot it was measured in (and needs no
 * preference), and it expires after [MISS_TTL_MS]. It is keyed by the candidate list: when adbd's tcp
 * properties change — another app arms a listener, a ROM that sets the property late — the list changes and
 * the next connect asks again.
 *
 * Only "nothing answered as adb" is a miss. A port that answered as adb but whose connect failed is not: that
 * is usually transient (adbd restarting mid-handshake, the connect cap), and caching it would hide the one
 * listener that works for up to [MISS_TTL_MS].
 *
 * Free of Android types so every branch is tested.
 */
internal class OpenAdbPortGate {
    private var missedFor: List<Int>? = null
    private var missedAtMs = 0L

    /** The ports to ask now: none when exactly this list came up empty within the last [MISS_TTL_MS]. */
    @Synchronized
    fun portsToProbe(candidates: List<Int>, nowMs: Long): List<Int> {
        val cached = candidates == missedFor && nowMs - missedAtMs < MISS_TTL_MS
        return if (cached) emptyList() else candidates
    }

    /** No port in [candidates] answered as adb; do not ask again until the list changes or the miss expires. */
    @Synchronized
    fun recordMiss(candidates: List<Int>, nowMs: Long) {
        missedFor = candidates
        missedAtMs = nowMs
    }

    /** A port worked; forget any earlier miss so a later loss of the connection asks again at once. */
    @Synchronized
    fun recordHit() {
        missedFor = null
    }

    companion object {
        /** How long a miss is trusted. Ten minutes: long enough that retries in a burst cost nothing. */
        const val MISS_TTL_MS = 10 * 60 * 1000L
    }
}
