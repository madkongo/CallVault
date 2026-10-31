/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A re-entrant lock whose **entry** wait is bounded: a caller that cannot get in within [entryWaitMs] gets
 * the caller's "busy" answer instead of queueing forever.
 *
 * ## Why
 *
 * `AdbShell.ensureConnected` used to be `@Synchronized`. Every step inside it is bounded now, but the wait to
 * get *into* it was not: a holder parked somewhere nobody bounded (libadb's connection lock, a shell read
 * that never sees EOF — see `libadb-open-lock-wedge`) left every later caller, including the one trying to
 * start a recording, queued behind it with no way out. That is how a wedge turns into hours of missed calls.
 *
 * ## What does not change
 *
 * - **Uncontended:** an untimed `tryLock` is tried first, so a free lock is taken exactly as a monitor would
 *   take it — including by a thread whose interrupt flag is set, which a timed wait would refuse at once.
 * - **Re-entrant:** a thread already holding it gets straight back in (`forceReconnect` → `ensureConnected`,
 *   the arming path → the Wireless-debugging connect), as with `@Synchronized`.
 * - **Contended but healthy:** the second caller waits for the first and then runs, as before — provided the
 *   first finishes within [entryWaitMs], which the caller sizes above its longest bounded hold.
 *
 * Free of Android types so every branch is tested.
 */
internal class BoundedEntryLock(private val entryWaitMs: Long) {

    /** Exposes the owner's name for the log line; `getOwner` is protected in [ReentrantLock]. */
    private class NamedOwnerLock : ReentrantLock() {
        fun ownerName(): String? = owner?.name
    }

    private val lock = NamedOwnerLock()

    /** True when the calling thread holds the lock (for assertions and tests). */
    val isHeldByCurrentThread: Boolean get() = lock.isHeldByCurrentThread

    /**
     * Runs [block] holding the lock, or returns [onBusy] if the lock could not be taken within [entryWaitMs].
     *
     * [onBusy] receives the name of the thread holding the lock at that moment (null if it was released in
     * the meantime), for the log. An interrupt during the wait counts as busy and is re-asserted.
     */
    fun <T> withLockOrElse(onBusy: (holder: String?) -> T, block: () -> T): T {
        if (!acquire()) return onBusy(lock.ownerName())
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(): Boolean {
        if (lock.tryLock()) return true
        return try {
            lock.tryLock(entryWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
