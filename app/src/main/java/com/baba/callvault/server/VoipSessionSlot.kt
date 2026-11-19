/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The recorder's current app-call capture session, reachable for a pause from the moment its start is accepted
 * (review C49 and its rework).
 *
 * `startVoipRecording` accepts on the binder thread and builds the session on the worker. The app's "Start when they
 * answer" hold sends its first pause as soon as the start returns, which can be before the worker has built the
 * session. That pause used to find no session, was dropped, and was never sent again. Now a pause in that window is
 * queued ([beginStart] opens it) and applied the moment the session is published, before it starts. A session's
 * pause only arms a flag, so it is safe to apply before the capture runs.
 */
internal class VoipSessionSlot<S : Any>(private val applyPause: (S, Boolean) -> Unit) {

    enum class PauseResult { APPLIED, QUEUED, IGNORED }

    private var session: S? = null
    private var starting = false
    private var pendingPause: Boolean? = null

    /** The published session, or null. */
    val current: S? @Synchronized get() = session

    /** A start was accepted: the previous session is forgotten and pauses are queued until [publishThenStart]. */
    @Synchronized
    fun beginStart() {
        session = null
        starting = true
        pendingPause = null
    }

    /** Applies [paused] to the session, queues it while one is being built, or ignores it when none is. */
    @Synchronized
    fun setPaused(paused: Boolean): PauseResult {
        session?.let { applyPause(it, paused); return PauseResult.APPLIED }
        if (!starting) return PauseResult.IGNORED
        pendingPause = paused
        return PauseResult.QUEUED
    }

    /** Publishes [s] with any queued pause applied, then [start]s it; a start that throws withdraws it and rethrows. */
    fun publishThenStart(s: S, start: (S) -> Unit): S {
        publish(s)
        try {
            start(s)
        } catch (t: Throwable) {
            withdraw()
            throw t
        }
        return s
    }

    @Synchronized
    private fun publish(s: S) {
        pendingPause?.let { applyPause(s, it) }
        pendingPause = null
        starting = false
        session = s
    }

    /** The start failed: no session, nothing queued. */
    @Synchronized
    fun withdraw() {
        session = null
        starting = false
        pendingPause = null
    }
}
