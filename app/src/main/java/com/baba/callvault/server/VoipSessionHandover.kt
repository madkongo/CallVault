/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Starts an app-call capture session that is reachable before it starts (review C49).
 *
 * The session starts on the recorder's worker thread, which takes a while (records, encoder, muxer). The app's
 * "Start when they answer" hold sends its first pause over the binder in that window. If the session is only
 * made reachable after `start()` returns, that pause finds no session, is dropped, and is never sent again: the
 * hold the app believes is on is not, or a later release finds nothing to release. A session's pause only arms a
 * flag, so it is safe to receive one before the capture runs.
 */
internal object VoipSessionHandover {

    /** [publish]es [session], then [start]s it; a start that throws withdraws it (`publish(null)`) and rethrows. */
    inline fun <S : Any> publishThenStart(session: S, publish: (S?) -> Unit, start: (S) -> Unit): S {
        publish(session)
        try {
            start(session)
        } catch (t: Throwable) {
            publish(null)
            throw t
        }
        return session
    }
}
