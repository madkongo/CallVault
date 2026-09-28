/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions a download has to make that are not about bytes on a socket.
 *
 * Both were written for whisper models of 190–574 MB. The summarisation model is 3.46 GB, and at six
 * times the size each stops being a detail: one turns into tens of thousands of database writes, and
 * the other into a phone with no free storage left.
 */
class ModelDownloadPolicyTest {

    // ---- progress ----

    @Test
    fun `publishes when the whole percent moves`() {
        assertTrue(ModelDownloadPolicy.shouldPublish(lastPublished = 41, percent = 42))
    }

    @Test
    fun `stays quiet while the percent is unchanged`() {
        // This is the entire point. Publishing on every 64 KB buffer is about 8,700 WorkManager
        // writes for a 574 MB model and roughly 52,800 for a 3.46 GB one — each one a database
        // transaction, for a number on screen that can only take a hundred values.
        assertFalse(ModelDownloadPolicy.shouldPublish(lastPublished = 42, percent = 42))
    }

    @Test
    fun `publishes the first figure even though it is zero`() {
        // Nothing has been published yet, so the bar has to be told it exists. -1 is "never".
        assertTrue(ModelDownloadPolicy.shouldPublish(lastPublished = -1, percent = 0))
    }

    @Test
    fun `never goes backwards`() {
        // A server that ignores a Range request restarts the file. The bytes go back to zero; a
        // progress bar that visibly rewinds reads as a fault rather than a resume.
        assertFalse(ModelDownloadPolicy.shouldPublish(lastPublished = 80, percent = 3))
    }

    @Test
    fun `a whole download publishes at most a hundred and one times`() {
        // Walks a 3.46 GB download in 64 KB buffers and counts what would reach the database.
        val total = 3_462_680_032L
        val buffer = 1L shl 16
        var written = 0L
        var lastPublished = -1
        var publishes = 0

        while (written < total) {
            written = minOf(written + buffer, total)
            val percent = ModelDownloadPolicy.percentOf(written, total)
            if (ModelDownloadPolicy.shouldPublish(lastPublished, percent)) {
                lastPublished = percent
                publishes++
            }
        }

        assertTrue("published $publishes times", publishes <= 101)
        assertEquals("the last figure must be 100", 100, lastPublished)
    }

    @Test
    fun `percent is never over a hundred`() {
        // A server may send more than the published length; the bar must not read 104%.
        assertEquals(100, ModelDownloadPolicy.percentOf(written = 4_000_000_000L, total = 3_462_680_032L))
    }

    @Test
    fun `percent of nothing is zero rather than a crash`() {
        assertEquals(0, ModelDownloadPolicy.percentOf(written = 0L, total = 0L))
    }

    // ---- storage ----

    @Test
    fun `room for a download that fits with space to spare`() {
        assertTrue(ModelDownloadPolicy.hasRoomFor(freeBytes = 10_000_000_000L, remainingBytes = 3_462_680_032L))
    }

    @Test
    fun `no room when the file would not fit at all`() {
        assertFalse(ModelDownloadPolicy.hasRoomFor(freeBytes = 2_000_000_000L, remainingBytes = 3_462_680_032L))
    }

    @Test
    fun `no room when it would fit but leave the phone with nothing`() {
        // Filling the last byte of a phone's storage is its own failure: the OS starts killing
        // things, and the user's next photo does not save. Better to refuse and say so.
        val free = 3_462_680_032L + 1_000_000L

        assertFalse(ModelDownloadPolicy.hasRoomFor(freeBytes = free, remainingBytes = 3_462_680_032L))
    }

    @Test
    fun `only the remaining bytes need to fit`() {
        // A resumed download already owns what is on disk. Asking for the whole size again would
        // refuse a download that is 90% done on a phone that has room for the rest.
        assertTrue(ModelDownloadPolicy.hasRoomFor(freeBytes = 1_000_000_000L, remainingBytes = 100_000_000L))
    }

    // ---- resuming, and a damaged download (report 2026-09-28: "the download was damaged") ----------
    //
    // The published files were checked byte for byte and are fine, so a digest failure is made on the
    // phone. These are the decisions that keep a leftover file from carrying bad bytes into the next try.

    @Test
    fun `a resume is appended only when the server continues exactly where the file ends`() {
        assertEquals(
            ModelDownloadPolicy.Reply.APPEND,
            ModelDownloadPolicy.reply(status = 206, resumeFrom = 1_000L, contentRange = "bytes 1000-1999/2000"),
        )
    }

    @Test
    fun `a resume that continues from anywhere else discards the leftover`() {
        // Appending bytes that start somewhere other than the end of the file writes a hole or an
        // overlap into it — a file of the right length that can never pass its digest.
        assertEquals(
            ModelDownloadPolicy.Reply.DISCARD_AND_RETRY,
            ModelDownloadPolicy.reply(status = 206, resumeFrom = 1_000L, contentRange = "bytes 0-1999/2000"),
        )
    }

    @Test
    fun `a partial reply without a readable range is not trusted`() {
        assertEquals(
            ModelDownloadPolicy.Reply.DISCARD_AND_RETRY,
            ModelDownloadPolicy.reply(status = 206, resumeFrom = 1_000L, contentRange = null),
        )
    }

    @Test
    fun `a whole-file reply starts the file again from nothing`() {
        // A server that ignores Range sends everything; what is on disk must go, not be prefixed.
        assertEquals(ModelDownloadPolicy.Reply.START_OVER, ModelDownloadPolicy.reply(200, resumeFrom = 1_000L, contentRange = null))
        assertEquals(ModelDownloadPolicy.Reply.START_OVER, ModelDownloadPolicy.reply(200, resumeFrom = 0L, contentRange = null))
    }

    @Test
    fun `any other status is an error`() {
        assertEquals(ModelDownloadPolicy.Reply.ERROR, ModelDownloadPolicy.reply(416, resumeFrom = 1_000L, contentRange = null))
        assertEquals(ModelDownloadPolicy.Reply.ERROR, ModelDownloadPolicy.reply(503, resumeFrom = 0L, contentRange = null))
    }

    @Test
    fun `a failed digest gets one clean download before the user is told`() {
        // The failure is almost always made by the interrupted attempt, not by the file itself.
        assertTrue(ModelDownloadPolicy.retryCleanAfterDigestFailure(cleanRetriesUsed = 0))
        assertFalse(ModelDownloadPolicy.retryCleanAfterDigestFailure(cleanRetriesUsed = 1))
    }

    // ---- what a download attempt writes to the log -----------------------------------------------------

    @Test
    fun `the start line says whether the attempt resumed and how the server answered`() {
        val line = ModelDownloadPolicy.attemptStartLine("small-q5_1", attempt = 3, resumeFrom = 62_914_560L,
            sizeBytes = 190_085_487L, status = 206, contentRange = "bytes 62914560-190085486/190085487")
        listOf("small-q5_1", "attempt 3", "resuming at 60.0 MB of 181.3 MB", "HTTP 206", "server continues at 60.0 MB").forEach {
            assertTrue("start line must carry '$it': $line", line.contains(it))
        }
    }

    @Test
    fun `the start line of a fresh attempt says so`() {
        val line = ModelDownloadPolicy.attemptStartLine("small-q5_1", 1, 0L, 190_085_487L, 200, null)
        assertTrue(line, line.contains("from the start"))
    }

    @Test
    fun `the end line gives how far it got, how long it took, the speed and why it ended`() {
        val line = ModelDownloadPolicy.attemptEndLine("small-q5_1", why = "stopped by Android (timeout)",
            attemptBytes = 60L * 1024 * 1024, totalBytes = 122_914_560L, sizeBytes = 190_085_487L, elapsedMs = 600_000L)
        listOf("stopped by Android (timeout)", "60.0 MB this attempt", "117.2 MB of 181.3 MB on disk", "600 s", "102 KB/s").forEach {
            assertTrue("end line must carry '$it': $line", line.contains(it))
        }
    }

    @Test
    fun `no line carries a run of digits the log redactor would blank as a phone number`() {
        val lines = listOf(
            ModelDownloadPolicy.attemptStartLine("m", 2, 62_914_560L, 2_620_370_976L, 206, "bytes 62914560-2620370975/2620370976"),
            ModelDownloadPolicy.attemptEndLine("m", "complete", 2_557_456_416L, 2_620_370_976L, 2_620_370_976L, 1_234_567L),
        )
        lines.forEach { assertFalse("8+ digits in: $it", Regex("""\d{8,}""").containsMatchIn(it)) }
    }

    @Test
    fun `the end line survives an attempt that took no time`() {
        val line = ModelDownloadPolicy.attemptEndLine("m", "complete", 0L, 0L, 10L, elapsedMs = 0L)
        assertTrue(line, line.contains("0 KB/s"))
    }
}

