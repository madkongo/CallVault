/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Runs one piece of work at a time for ALL callers: while a run is in progress, any other caller does not start its
 * own, it waits for that run and takes its result.
 *
 * Why: bringing the recorder up (launch, then clearing leftovers) must happen once per cold start, however many paths
 * ask for it at the same moment (the call, the keep-alive, the VoIP arm, app start). The launch itself was already
 * serialised on the ADB lock, but each waiting caller then ran its own "clear other recorder processes" step against
 * the host the first one had just started (BabApps #95: "Clearing 1 other recorder process(es)" twice, 44 ms apart,
 * 0.27 s before the capture was invalidated). A caller that arrives after the run has finished starts a new run, which
 * is a cheap no-op when the recorder is already up.
 */
class SingleFlight<T> {

    private var current: CompletableFuture<T>? = null

    /**
     * @param onJoin called on a caller that joined a run already in progress, before it waits.
     * @param block the work; runs on the first caller's own thread.
     */
    fun run(onJoin: () -> Unit = {}, block: () -> T): T {
        val joined: CompletableFuture<T>?
        val mine: CompletableFuture<T>?
        synchronized(this) {
            val running = current
            if (running != null) {
                joined = running
                mine = null
            } else {
                joined = null
                mine = CompletableFuture<T>().also { current = it }
            }
        }
        if (joined != null) {
            onJoin()
            return try {
                joined.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        val future = mine!!
        try {
            val result = block()
            future.complete(result)
            return result
        } catch (t: Throwable) {
            future.completeExceptionally(t)
            throw t
        } finally {
            synchronized(this) { current = null }
        }
    }
}
