/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    private val owners: MutableSet<Any> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Claims the recorder's session for [owner]. Called BEFORE the start is dispatched, so no check can catch it unowned. */
    fun claim(owner: Any) {
        owners.add(owner)
    }

    /** Gives [owner]'s claim back once its session is stopped, or its start failed. Idempotent. */
    fun release(owner: Any) {
        owners.remove(owner)
    }

    /** True while anything in this process owns a recorder session. */
    val isOwnedHere: Boolean
        get() = owners.isNotEmpty()

    /** For tests only. */
    internal fun clearForTest() = owners.clear()
}
