/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import java.lang.ref.WeakReference

/**
 * Forces a collection and reports whether a binder proxy is really gone.
 *
 * A native `IAudioRecord` ref is only released when its `BinderProxy` is finalized, so the handoff code
 * has to drop the proxy and collect. It used to log "released" after the collection without looking at
 * the result; this returns the real outcome so the log can say "freed" or "still reachable".
 *
 * Blocking (a few collection passes): call it from a background thread, never at call end on the
 * caller's thread.
 */
object ProxyCollector {
    /** Collection rounds before giving up; each round is gc, finalization, gc. */
    const val MAX_ROUNDS = 3

    /** True once [ref] no longer holds its referent. [gc] is a seam for tests. */
    fun collect(
        ref: WeakReference<*>,
        gc: () -> Unit = {
            System.gc()
            System.runFinalization()
            System.gc() // finalizer-enqueued proxies are reclaimed on the following cycle
        },
    ): Boolean {
        repeat(MAX_ROUNDS) {
            if (ref.get() == null) return true
            runCatching { gc() }
            if (ref.get() == null) return true
        }
        return ref.get() == null
    }
}
