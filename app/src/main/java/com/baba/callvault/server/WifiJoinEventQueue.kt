/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * Carries the recorder's Wi-Fi watch lines ([WifiJoinEvent]) to the app's always-on log, each once (review N15).
 *
 * Lines are pushed to the app ([push]: the app's provider, which also starts the app's process when it is not
 * running) on [schedule]'s thread, which is not the join-check thread. Pushes are coalesced: a line schedules one push
 * [PUSH_DELAY_MS] later unless one is already scheduled, so a burst of lines is one push and one is in flight at most. Lines the app could not take are kept, oldest dropped past [capacity], and go with the
 * next push that gets through or the app's next `drainWifiJoinEvents`. A batch is taken out before it is sent, so a
 * push and a drain never hand over the same line.
 */
class WifiJoinEventQueue(
    private val push: (Array<String>) -> Boolean,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val capacity: Int = CAPACITY,
) {

    private val kept = ArrayDeque<WifiJoinEvent>()
    private var dropped = 0
    private var pushScheduled = false

    fun add(event: WifiJoinEvent) {
        if (event.warn) AppLogger.w(TAG, event.text) else AppLogger.i(TAG, event.text)
        val schedulePush = synchronized(this) {
            kept.addLast(event)
            while (kept.size > capacity) {
                kept.removeFirst()
                dropped++
            }
            !pushScheduled.also { pushScheduled = true }
        }
        if (schedulePush) schedule(PUSH_DELAY_MS, ::pushKept)
    }

    /** Everything not yet handed over, oldest first; forgotten once returned. */
    fun drain(): Array<String> = take().encode()

    private fun pushKept() {
        synchronized(this) { pushScheduled = false }
        val batch = take()
        if (batch.events.isEmpty()) return
        val delivered = runCatching { push(batch.encode()) }
            .onFailure { AppLogger.w(TAG, "Could not hand the Wi-Fi watch lines to the app: ${it.message}") }
            .getOrDefault(false)
        if (!delivered) putBack(batch)
    }

    /** What was kept, and how many older lines were dropped before it. */
    private class Batch(val events: List<WifiJoinEvent>, val dropped: Int) {
        fun encode(): Array<String> {
            val note = if (dropped > 0) {
                listOf(WifiJoinEvent(events.firstOrNull()?.atMs ?: 0L, warn = true, text = "$dropped $DROPPED_NOTE"))
            } else emptyList()
            return (note + events).map { it.encode() }.toTypedArray()
        }
    }

    @Synchronized
    private fun take(): Batch = Batch(kept.toList(), dropped).also {
        kept.clear()
        dropped = 0
    }

    /** A batch the app did not take goes back in front of anything newer; the oldest still go past [capacity]. */
    @Synchronized
    private fun putBack(batch: Batch) {
        batch.events.asReversed().forEach { kept.addFirst(it) }
        dropped += batch.dropped
        while (kept.size > capacity) {
            kept.removeFirst()
            dropped++
        }
    }

    private companion object {
        const val TAG = "CV:WifiJoinWatch"
        const val CAPACITY = 64
        const val PUSH_DELAY_MS = 3_000L
        const val DROPPED_NOTE = "earlier lines were dropped while the app could not be reached"
    }
}
