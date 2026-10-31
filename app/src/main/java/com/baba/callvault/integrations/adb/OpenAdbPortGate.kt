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
 * preference). It is keyed by the candidate list: when adbd's tcp properties change — another app arms a
 * listener, our own arm, a ROM that sets the property late — the list changes and the next connect asks
 * again. A process restart also asks again, which costs a refused loopback connect (about a millisecond)
 * per candidate on the phones where nothing listens.
 *
 * Free of Android types so every branch is tested.
 */
internal class OpenAdbPortGate {
    private var missedFor: List<Int>? = null

    /** The ports to ask now: none when exactly this list already came up empty. */
    @Synchronized
    fun portsToProbe(candidates: List<Int>): List<Int> = if (candidates == missedFor) emptyList() else candidates

    /** Nothing in [candidates] gave us a connection; do not ask again until the list changes. */
    @Synchronized
    fun recordMiss(candidates: List<Int>) {
        missedFor = candidates
    }

    /** A port worked; forget any earlier miss so a later loss of the connection asks again at once. */
    @Synchronized
    fun recordHit() {
        missedFor = null
    }
}
