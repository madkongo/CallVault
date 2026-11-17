/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * What the calling app's notification said BEFORE the pickup (BabApps item 113).
 *
 * An incoming app call is only recognised by its ringing notification (a CallStyle of type incoming,
 * with Answer and Decline), and that notification is gone, or already rewritten as "ongoing", by the
 * time the audio mode flips at the pickup. [VoipRingListenerService] feeds this memory the moment the
 * notification posts; the call's recording asks it once. Only "this package was ringing, and was then
 * answered" is kept: no title, no text, no name, no number.
 *
 * Pure apart from the clock, which is passed in, so the rules are unit-tested.
 */
object VoipRingMemory {

    /** A recording that starts this soon after the answer belongs to that call. */
    const val ANSWER_FRESH_MS = 10_000L

    /**
     * After a ring ends without an answer we still do not call the next call outgoing for this long:
     * a ring we saw means "an incoming call happened here", and what followed is not proven to be
     * something else.
     */
    const val UNRESOLVED_MS = 90_000L

    /**
     * An ongoing notification this soon after the ringing one was removed is the same call's (Telegram
     * removes its ringing notification and posts the ongoing one a moment apart). Short on purpose: a
     * declined call followed at once by a call placed from the same app must not read as answered.
     */
    const val HANDOVER_MS = 1_500L

    enum class Verdict {
        /** The app rang, and the call was picked up just now: this call is incoming. */
        ANSWERED_RING,

        /** The app rang and no pickup was seen (declined, missed, or still ringing): says nothing about THIS call. */
        UNRESOLVED_RING,

        /** No ring seen for this app. */
        NONE,
    }

    private class Entry {
        var ringKey: String? = null
        var ringActive = false
        var lastRingAt = 0L
        var removedAt = 0L
        var answeredAt = 0L
    }

    private val entries = HashMap<String, Entry>()

    /** True while the listener is connected to the system: only then does "no ring seen" mean anything. */
    @Volatile var listenerConnected = false

    /** Posted or updated: [incomingMarker] says it is a ringing call notification, [ongoing] that it is an ongoing one. */
    @Synchronized
    fun onPosted(pkg: String, key: String, incomingMarker: Boolean, ongoing: Boolean, groupSummary: Boolean, now: Long) {
        val e = entries.getOrPut(pkg) { Entry() }
        if (incomingMarker) {
            e.ringKey = key
            e.ringActive = true
            e.lastRingAt = now
            e.answeredAt = 0L
            return
        }
        if (!ongoing || groupSummary) return
        val handover = e.ringActive || (e.removedAt > 0L && now - e.removedAt <= HANDOVER_MS)
        if (handover && e.answeredAt == 0L) {
            e.answeredAt = now
            e.ringActive = false
        }
    }

    /** A notification of [pkg] was removed. Removing the ringing one ends the ring. */
    @Synchronized
    fun onRemoved(pkg: String, key: String, now: Long) {
        val e = entries[pkg] ?: return
        if (e.ringKey == key && e.ringActive) {
            e.ringActive = false
            e.removedAt = now
        }
    }

    @Synchronized
    fun verdict(pkg: String, now: Long): Verdict {
        val e = entries[pkg] ?: return Verdict.NONE
        if (e.answeredAt > 0L && now - e.answeredAt <= ANSWER_FRESH_MS) return Verdict.ANSWERED_RING
        val last = maxOf(e.lastRingAt, e.removedAt)
        if (e.ringActive || (last > 0L && now - last <= UNRESOLVED_MS)) return Verdict.UNRESOLVED_RING
        return Verdict.NONE
    }

    /** The call that used the answer has started; it cannot be used for a second call. */
    @Synchronized
    fun consume(pkg: String) {
        entries[pkg]?.let { it.answeredAt = 0L; it.removedAt = 0L; it.lastRingAt = 0L }
    }

    @Synchronized
    internal fun clearForTest() {
        entries.clear()
        listenerConnected = false
    }
}
