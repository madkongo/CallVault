/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Which recorder sessions THIS app process started and still owns (review C2).
 *
 * The recorder runs in its own process and outlives ours. When the phone kills CallVault mid-call, the
 * recorder keeps recording, and the restarted app gets the same recorder back. The recorder cannot say
 * who started its session, so the app keeps its own list: a session is ours from just before we ask the
 * recorder to start it until our release has stopped it. A recorder that is recording while this list is
 * empty is recording for a process that no longer exists: an orphan (see [com.baba.callvault.services.recording.OrphanRecorderSession]).
 *
 * In memory on purpose: the list must die with the process that made it, which is exactly what makes a
 * leftover session recognisable after a restart.
 */
object DaemonSessionOwnership {

    /** Owner -> true once it has begun stopping its session (see [beginRelease]). Guarded by itself. */
    private val owners = LinkedHashMap<Any, Boolean>()

    /** Claims the recorder's session for [owner]. Called BEFORE the start is dispatched, so no check can catch it unowned. */
    fun claim(owner: Any) {
        synchronized(owners) { owners[owner] = false }
    }

    /**
     * [owner] has begun stopping its session on another thread (an app call ended for a phone call, review C1). Still
     * owned until [release]; a start that finds only releasing owners may wait for them instead of failing.
     */
    fun beginRelease(owner: Any) {
        synchronized(owners) { if (owners.containsKey(owner)) owners[owner] = true }
    }

    /** Gives [owner]'s claim back once its session is stopped, or its start failed. Idempotent. */
    fun release(owner: Any) {
        synchronized(owners) {
            if (owners.remove(owner) != null) (owners as Object).notifyAll()
        }
    }

    /** True while anything in this process owns a recorder session. */
    val isOwnedHere: Boolean
        get() = synchronized(owners) { owners.isNotEmpty() }

    /** True while [owner] holds a claim. */
    fun isOwnedBy(owner: Any): Boolean = synchronized(owners) { owners.containsKey(owner) }

    /** True when there are claims other than [self], and every one of them is being released. */
    fun othersAllReleasing(self: Any): Boolean = synchronized(owners) {
        val others = owners.filterKeys { it !== self }
        others.isNotEmpty() && others.values.all { it }
    }

    /** Waits up to [timeoutMs] until no owner other than [self] is left. True when they are all gone. */
    fun awaitOthersReleased(self: Any, timeoutMs: Long): Boolean = synchronized(owners) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (owners.keys.any { it !== self }) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return@synchronized false
            (owners as Object).wait(left)
        }
        true
    }

    /** For tests only. */
    internal fun clearForTest() = synchronized(owners) { owners.clear() }
}
