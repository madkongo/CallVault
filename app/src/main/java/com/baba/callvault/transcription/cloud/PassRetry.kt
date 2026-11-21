/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.utils.AppLogger

/**
 * Retries ONE cloud pass in place when the service is busy or the network dropped (review 2026-11-20, C168), so a
 * failure on pass 4 does not throw away passes 1-3 and upload them all again on the user's key and data.
 *
 * Bounded ([MAX_TRIES]), stoppable (the wait is sliced and [aborted] is checked before every wait and try), and it
 * honours a 429/503 Retry-After up to [MAX_WAIT_MS]. Only RETRY_LATER is retried; every other error is thrown as is.
 */
object PassRetry {

    private const val TAG = "CV:PassRetry"
    const val MAX_TRIES = 3
    const val MAX_WAIT_MS = 60_000L
    private const val BASE_WAIT_MS = 4_000L
    private const val SLICE_MS = 500L

    fun <T> run(
        label: String,
        aborted: () -> Boolean,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        block: () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: CloudError) {
                if (e.kind != CloudError.Kind.RETRY_LATER || attempt >= MAX_TRIES || aborted()) throw e
                val waitMs = waitFor(attempt, e.retryAfterMs)
                AppLogger.w(TAG, "$label: try $attempt failed (${e.httpStatus ?: e.message}); trying again in ${waitMs / 1000} s")
                if (!pause(waitMs, aborted, sleep)) throw e
                attempt++
            }
        }
    }

    /** The wait before try [attempt] + 1: the service's own Retry-After when it gave one, else a growing back-off. */
    fun waitFor(attempt: Int, retryAfterMs: Long?): Long =
        (retryAfterMs ?: (BASE_WAIT_MS shl (attempt - 1))).coerceIn(0L, MAX_WAIT_MS)

    /** Waits in slices so Stop is seen within half a second; false when stopped meanwhile. */
    private fun pause(totalMs: Long, aborted: () -> Boolean, sleep: (Long) -> Unit): Boolean {
        var left = totalMs
        while (left > 0) {
            if (aborted()) return false
            val slice = minOf(left, SLICE_MS)
            sleep(slice)
            left -= slice
        }
        return !aborted()
    }

    /** Retry-After as seconds ("30"); the HTTP-date form is ignored. Null when absent or unusable. */
    fun parseRetryAfter(value: String?): Long? =
        value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).coerceAtMost(MAX_WAIT_MS) }
}
