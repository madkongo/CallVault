/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

/**
 * The one rule of [CallRedirectDiagService], kept free of Android so a unit test can hold it:
 * answer Telecom FIRST, and let nothing that follows (the logging) stand between the call and the answer.
 *
 * Telecom CANCELS the user's outgoing call when a call redirection app does not answer within 5 s, so a diagnostic
 * that can delay or break the answer is worse than none. The answer is given before anything is measured or written;
 * the measuring and writing happen afterwards, inside `finally`, and each of them is allowed to throw.
 */
object CallRedirectAnswer {

    /**
     * @param boundAtMs   when Telecom's bind reached the service, on the same clock as [now].
     * @param now         a monotonic clock in milliseconds.
     * @param place       `placeCallUnmodified()`. Called exactly once, before anything else.
     * @param describe    what to write about the call (account, number shape); may throw.
     * @param log         where the line goes; may throw.
     */
    fun answer(
        boundAtMs: Long,
        now: () -> Long,
        place: () -> Unit,
        describe: () -> String,
        log: (String) -> Unit,
    ) {
        val arrivedAt = now()
        var placeMs = -1L
        var placeError: Throwable? = null
        try {
            place()
            placeMs = now() - arrivedAt
        } catch (t: Throwable) {
            placeError = t
        } finally {
            runCatching {
                val failure = placeError?.let { " placeCallUnmodified FAILED ${it.javaClass.simpleName}" }.orEmpty()
                log(
                    "onPlaceCall: bind->onPlaceCall=${arrivedAt - boundAtMs}ms " +
                        "placeCallUnmodified=${if (placeMs >= 0) "${placeMs}ms" else "n/a"}$failure ${describe()}",
                )
            }
        }
    }
}
