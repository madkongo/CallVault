/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

/**
 * The decisions a download makes that are not about bytes on a socket.
 *
 * Split out from `ModelDownloadWorker` because both only started to matter when the summarisation
 * model arrived at 3.46 GB — six times the largest whisper model — and both are worth being able to
 * reason about without WorkManager in the way.
 */
object ModelDownloadPolicy {

    /** Percent has a hundred and one distinct values, and reporting is capped to match. */
    private const val PERCENT = 100

    /**
     * Free space a download must leave behind after it finishes.
     *
     * Filling the last byte of a phone's storage is its own kind of failure: the system starts
     * shedding processes and the user's next photo does not save. Refusing with a reason is better
     * than succeeding into that.
     */
    const val HEADROOM_BYTES = 250L * 1024 * 1024

    /** How far through, 0-100, clamped. */
    fun percentOf(written: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((written * PERCENT) / total).toInt().coerceIn(0, PERCENT)
    }

    /**
     * Whether [percent] is worth writing to WorkManager's progress store.
     *
     * Only when the whole number moves forward. The worker reads the socket in 64 KB buffers, so
     * publishing on every read is roughly 8,700 database transactions for a 574 MB model and 52,800
     * for a 3.46 GB one — all to move a bar that has a hundred positions.
     *
     * Forward only. A server that ignores a `Range` header replies with the whole file and the byte
     * count restarts at zero; a progress bar that visibly rewinds reads as a fault rather than as a
     * resume.
     *
     * @param lastPublished the last figure published, or -1 if none has been.
     */
    fun shouldPublish(lastPublished: Int, percent: Int): Boolean = percent > lastPublished

    /**
     * Whether [remainingBytes] can be written with [HEADROOM_BYTES] left over.
     *
     * Only the remaining bytes are asked for: a resumed download already owns what is on disk, and
     * demanding the full size again would refuse a download that is nearly finished on a phone with
     * room for the rest of it.
     */
    fun hasRoomFor(freeBytes: Long, remainingBytes: Long): Boolean =
        freeBytes >= remainingBytes + HEADROOM_BYTES

    /** What to do with a server's reply to a download request. */
    enum class Reply {
        /** A partial reply that continues exactly where the file on disk ends. */
        APPEND,

        /** A whole-file reply: whatever is on disk goes, and the file is written from its first byte. */
        START_OVER,

        /** A partial reply from somewhere else, or unreadable: the leftover cannot be trusted. */
        DISCARD_AND_RETRY,

        /** Not a reply a download can use. */
        ERROR,
    }

    private val CONTENT_RANGE_START = Regex("""^\s*bytes\s+(\d+)-""")

    /**
     * How to treat a reply with [status] to a request that asked for the file from [resumeFrom], given its
     * `Content-Range` header. A 206 is only appended when it starts exactly at [resumeFrom]: bytes from
     * anywhere else would put a hole or an overlap into a file of the right length, which fails its digest
     * at the very end — "the download was damaged" (report 2026-09-28).
     */
    fun reply(status: Int, resumeFrom: Long, contentRange: String?): Reply = when (status) {
        HTTP_OK -> Reply.START_OVER
        HTTP_PARTIAL -> {
            val start = contentRange?.let { CONTENT_RANGE_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (start != null && start == resumeFrom) Reply.APPEND else Reply.DISCARD_AND_RETRY
        }
        else -> Reply.ERROR
    }

    /** Clean downloads, from nothing, a digest failure earns before the user is told the download was damaged. */
    const val CLEAN_RETRIES_AFTER_DIGEST_FAILURE = 1

    /**
     * Whether a download whose file just failed its digest should start again from nothing on its own.
     * The file has already been deleted; the failure is almost always made by an interrupted attempt,
     * not by the published file (checked byte for byte on 2026-09-28), so one clean try usually settles it.
     */
    fun retryCleanAfterDigestFailure(cleanRetriesUsed: Int): Boolean =
        cleanRetriesUsed < CLEAN_RETRIES_AFTER_DIGEST_FAILURE

    private const val HTTP_OK = 200
    private const val HTTP_PARTIAL = 206

    /**
     * The log line at the start of each attempt: which attempt, fresh or resumed, and the server's answer.
     * Before 2.4.3 a download logged only that it failed, so a "damaged" report could not say why (2026-09-28).
     *
     * Sizes are in MB, never raw byte counts: the log's redactor reads a run of 8+ digits as a phone number
     * and blanks it, which is what happened to the first version of these lines.
     */
    fun attemptStartLine(
        modelId: String, attempt: Int, resumeFrom: Long, sizeBytes: Long, status: Int, contentRange: String?,
    ): String {
        val from = if (resumeFrom > 0L) "resuming at ${mb(resumeFrom)} of ${mb(sizeBytes)}" else "from the start (${mb(sizeBytes)})"
        val served = contentRange
            ?.let { CONTENT_RANGE_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.let { ", server continues at ${mb(it)}" }
            ?: contentRange?.let { ", unreadable Content-Range" }.orEmpty()
        return "$modelId: download attempt $attempt, $from; HTTP $status$served"
    }

    /** The log line at the end of each attempt: why it ended, how far it got, how long, how fast. */
    fun attemptEndLine(
        modelId: String, why: String, attemptBytes: Long, totalBytes: Long, sizeBytes: Long, elapsedMs: Long,
    ): String {
        val kbPerSecond = if (elapsedMs > 0L) attemptBytes * MS_PER_SECOND / elapsedMs / BYTES_PER_KB else 0L
        return "$modelId: download attempt ended — $why; ${mb(attemptBytes)} this attempt, ${mb(totalBytes)} of " +
            "${mb(sizeBytes)} on disk, ${elapsedMs / MS_PER_SECOND} s, $kbPerSecond KB/s"
    }

    /** A size for the log, in MB — never a raw byte count, which the redactor blanks as a phone number. */
    fun mb(bytes: Long): String = "%.1f MB".format(java.util.Locale.ROOT, bytes / BYTES_PER_MB)

    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = 1024.0 * 1024.0
    private const val MS_PER_SECOND = 1000L
}

