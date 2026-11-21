/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Counts the reads of one capture loop that returned nothing useful, so a capture that stopped getting audio says so
 * (review C38). Pure bookkeeping: it never sleeps, never decides to stop, never changes what the loop does. The loop
 * it watches was silent about every failed read before this existed.
 *
 * One reader thread owns one tally; it is not thread-safe on purpose.
 */
internal class ReadFaultTally {

    /** Reads that returned a negative `AudioRecord` error code. */
    var errors = 0L
        private set

    /** Reads that returned zero bytes. A blocking read should not, so a long run of them is worth saying. */
    var empties = 0L
        private set

    /** The first error code seen; null while there has been none. */
    var firstCode: Int? = null
        private set

    /** The longest stretch of consecutive reads that gave no audio (errors and empties together). */
    var longestRun = 0L
        private set

    private var run = 0L

    /** Feeds one read result. True exactly once, on the first error, so the caller can log it without flooding. */
    fun onRead(read: Int): Boolean {
        if (read > 0) {
            run = 0L
            return false
        }
        run++
        if (run > longestRun) longestRun = run
        if (read == 0) {
            empties++
            return false
        }
        errors++
        if (firstCode == null) {
            firstCode = read
            return true
        }
        return false
    }

    /** The line for the capture health, or null when the capture read cleanly (the normal case on every phone we have). */
    fun summary(): String? {
        if (errors == 0L && empties < EMPTY_READS_WORTH_SAYING) return null
        return buildString {
            append("readErrors=").append(errors)
            firstCode?.let { append(" firstCode=").append(it).append('(').append(CaptureFaultLines.codeName(it)).append(')') }
            append(" longestRun=").append(longestRun)
            if (empties > 0L) append(" emptyReads=").append(empties)
        }
    }

    companion object {
        /** A blocking read returning 0 now and then is not a fault; this many in one call is. */
        const val EMPTY_READS_WORTH_SAYING = 100L
    }
}

/** The one-line accounts of a capture that ended badly, shared by the direct and the app-call paths. */
internal object CaptureFaultLines {

    /** `AudioRecord`'s own error constants, by value, so a report reads "DEAD_OBJECT" and not "-6". */
    fun codeName(code: Int): String = when (code) {
        -1 -> "ERROR"
        -2 -> "BAD_VALUE"
        -3 -> "INVALID_OPERATION"
        -6 -> "DEAD_OBJECT"
        else -> "code $code"
    }

    /** One side of an app call stopped reading: from here to the end of the call that side is silence in the file. */
    fun voipSideEnded(side: String, code: Int, atMillis: Long): String =
        "VoIP $side capture stopped reading (read=$code ${codeName(code)}) at ${atMillis}ms; the rest of the call has no $side audio"

    /** The encode or capture thread died with an exception; whatever was written before it is all the file has. */
    fun threadFailed(what: String, reason: String?): String =
        "$what failed and the capture ended there: ${reason?.takeIf { it.isNotBlank() } ?: "no message"}"

    /** The encoder never produced its end-of-stream; the file was finalised without its tail. */
    fun eosTimedOut(budgetMillis: Long, eosQueued: Boolean): String =
        "encoder did not finish within ${budgetMillis}ms of end of stream (end-of-stream ${if (eosQueued) "queued" else "could not be queued"}); " +
            "the file was finalised without the codec's last frames"
}
