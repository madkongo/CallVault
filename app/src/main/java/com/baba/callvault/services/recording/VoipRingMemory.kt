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

    /**
     * A ring still "active" this long after it was posted is over (review C30): nothing else may close it when its
     * notification is rewritten in place or never removed, and an open ring took the next call's notice for its pickup.
     */
    const val MAX_RING_MS = VoipDirection.MAX_RING_MS

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

    /**
     * Since when (elapsedRealtime) the listener has been connected to the system; 0 while it is not. Only a listener
     * connected since before a call could have rung makes "no ring seen" mean anything (review C20).
     */
    @Volatile var listenerConnectedSince = 0L
        private set

    /** True while the listener is connected to the system. */
    val listenerConnected: Boolean get() = listenerConnectedSince > 0L

    fun onListenerConnected(now: Long) {
        listenerConnectedSince = now.coerceAtLeast(1L)
    }

    fun onListenerDisconnected() {
        listenerConnectedSince = 0L
    }

    /** When a recording of [pkg]'s call last ended (elapsedRealtime), so a split call's next file is known (C20). */
    private val recordingEndedAt = HashMap<String, Long>()

    @Synchronized
    fun noteRecordingEnded(pkg: String, now: Long) {
        recordingEndedAt[pkg] = now
    }

    @Synchronized
    fun lastRecordingEndedAt(pkg: String): Long = recordingEndedAt[pkg] ?: 0L

    /**
     * Posted or updated: [incomingMarker] says it is a ringing call notification, [ongoing] that it is an ongoing one,
     * [callLike] that it carries a call's own markers (the call category, a CallStyle type, the incoming flag or a
     * running call timer).
     *
     * The pickup (review C30) is the ring's own notification rewritten as ongoing (WhatsApp), a call-like ongoing
     * notice while the app rings, or any ongoing notice just after the ring was removed (Telegram, whose ongoing call
     * notice carries no call marker at all). Any other ongoing notice of the app (a backup, an upload) is not.
     */
    @Synchronized
    fun onPosted(
        pkg: String,
        key: String,
        incomingMarker: Boolean,
        ongoing: Boolean,
        groupSummary: Boolean,
        now: Long,
        callLike: Boolean = false,
    ) {
        if (incomingMarker) {
            val e = entries.getOrPut(pkg) { Entry() }
            e.ringKey = key
            e.ringActive = true
            e.lastRingAt = now
            e.answeredAt = 0L
            return
        }
        val e = entries[pkg] ?: return
        if (!ongoing && key == e.ringKey && e.ringActive) {
            // The ringing notice rewritten in place into one that neither rings nor is ongoing (a missed call):
            // the ring is over, and it was not answered.
            e.ringActive = false
            return
        }
        if (!ongoing || groupSummary) return
        val ringing = isRinging(e, now)
        val pickup = ringing && (callLike || key == e.ringKey)
        val handover = e.removedAt > 0L && now - e.removedAt <= HANDOVER_MS
        if ((pickup || handover) && e.answeredAt == 0L) {
            e.answeredAt = now
            e.ringActive = false
        }
    }

    private fun isRinging(e: Entry, now: Long): Boolean = e.ringActive && now - e.lastRingAt <= MAX_RING_MS

    /**
     * Whether any app is ringing right now, or was answered so recently that its recording has not started yet (review
     * C54): the audio mode is still NORMAL and nothing records during either, so callers that must not disturb the
     * recorder during a call (the stuck-microphone heal) cannot see it any other way. A declined or missed ring is over.
     */
    @Synchronized
    fun anyRingActive(now: Long): Boolean = entries.values.any { e ->
        isRinging(e, now) || (e.answeredAt in 1L..now && now - e.answeredAt <= ANSWER_FRESH_MS)
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
        // Only an answer before [now]: a ring is read as of the call's start too (C20), never with a later call's answer.
        if (e.answeredAt in 1L..now && now - e.answeredAt <= ANSWER_FRESH_MS) return Verdict.ANSWERED_RING
        if (isRinging(e, now)) return Verdict.UNRESOLVED_RING
        // When the ring ended: removed, or its time ran out while nothing closed it (C30).
        val ringEnd = if (e.ringActive) e.lastRingAt + MAX_RING_MS else e.lastRingAt
        val last = maxOf(ringEnd, e.removedAt)
        if (e.lastRingAt > 0L && now - last <= UNRESOLVED_MS) return Verdict.UNRESOLVED_RING
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
        recordingEndedAt.clear()
        listenerConnectedSince = 0L
    }
}
