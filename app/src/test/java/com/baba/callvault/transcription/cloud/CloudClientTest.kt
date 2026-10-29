/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.transcription.ChunkPlan
import com.baba.callvault.transcription.TranscriptSegment
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** verbose_json parsing and the client's handling of replies — no network, a fake [HttpPoster]. */
@RunWith(RobolectricTestRunner::class) // for a real org.json
@Config(sdk = [35])
class CloudClientTest {

    private val groq = CloudEndpoint.resolve(TranscriptionService.GROQ, null, null)!!

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `segments are read in seconds and stored in milliseconds`() {
        val body = """{"text":"hi there","segments":[
            {"id":0,"start":0.0,"end":1.25,"text":" hi "},
            {"id":1,"start":43.92,"end":50.16,"text":"there"}]}"""
        assertEquals(
            listOf(TranscriptSegment(0, 1250, "hi"), TranscriptSegment(43920, 50160, "there")),
            VerboseJsonParser.segments(body, "Groq"),
        )
    }

    @Test
    fun `empty and untimed segments are dropped`() {
        val body = """{"text":"a","segments":[{"start":0,"end":1,"text":"  "},{"text":"no times"},{"start":2,"end":3,"text":"a"}]}"""
        assertEquals(listOf(TranscriptSegment(2000, 3000, "a")), VerboseJsonParser.segments(body, "Groq"))
    }

    @Test
    fun `words without segments are refused rather than stored untimed`() {
        assertNoSegments("""{"text":"hello there"}""")
        assertNoSegments("""{"text":"hello there","segments":[]}""")
    }

    @Test
    fun `silence is an empty transcript, not an error`() {
        assertEquals(emptyList<TranscriptSegment>(), VerboseJsonParser.segments("""{"text":"","segments":[]}""", "Groq"))
    }

    @Test
    fun `a body that is not json is a refusal`() {
        try {
            VerboseJsonParser.segments("<html>", "Groq")
            fail()
        } catch (e: CloudError) {
            assertEquals(CloudError.Kind.REJECTED, e.kind)
        }
    }

    @Test
    fun `segments of each chunk are offset by where that chunk began`() {
        // The same stitch the on-device path uses; a five-minute run-up chunk starts 290 s in.
        val plan = ChunkPlan.plan(totalMs = 10 * 60_000L)
        val second = plan[1]
        val local = VerboseJsonParser.segments("""{"text":"x y","segments":[
            {"start":5.0,"end":6.0,"text":"x"},{"start":12.0,"end":13.0,"text":"y"}]}""", "Groq")
        val stitched = ChunkPlan.stitch(local, second)
        // The first lies inside the run-up (290-300 s) the previous chunk already covered.
        assertEquals(listOf(TranscriptSegment(302_000, 303_000, "y")), stitched)
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the key goes in the authorization header and nowhere else`() {
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        var seenBody = ByteArray(0)
        val client = CloudTranscriptionClient { url, headers, body ->
            seenUrl = url; seenHeaders = headers; seenBody = body
            HttpReply(200, """{"text":"","segments":[]}""")
        }
        client.transcribe(groq, " gsk_secret123 ", WavEncoder.silence(), "he")
        assertEquals("Bearer gsk_secret123", seenHeaders["Authorization"])
        assertFalse(seenUrl.contains("gsk_"))
        assertFalse(String(seenBody, Charsets.ISO_8859_1).contains("gsk_secret123"))
        assertTrue(seenHeaders.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
    }

    @Test
    fun `a refused key is not retried and does not leak the server's words`() {
        val client = CloudTranscriptionClient { _, _, _ -> HttpReply(401, "") }
        try {
            client.transcribe(groq, "gsk_bad", WavEncoder.silence(), null)
            fail()
        } catch (e: CloudError) {
            assertEquals(CloudError.Kind.BAD_KEY, e.kind)
            assertFalse(e.isRetryable)
            assertFalse(e.message!!.contains("gsk_bad"))
        }
    }

    @Test
    fun `no network is worth retrying`() {
        val client = CloudTranscriptionClient { _, _, _ -> throw IOException("timeout") }
        try {
            client.transcribe(groq, "k", WavEncoder.silence(), null)
            fail()
        } catch (e: CloudError) {
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun `a missing key is reported as not set up without calling anything`() {
        val client = CloudTranscriptionClient { _, _, _ -> error("must not be called") }
        try {
            client.transcribe(groq, " ", WavEncoder.silence(), null)
            fail()
        } catch (e: CloudError) {
            assertEquals(CloudError.Kind.NOT_SET_UP, e.kind)
        }
    }

    @Test
    fun `the test button tells a working key from a bad one and from a model without timings`() {
        fun outcome(reply: HttpReply) = CloudTranscriptionClient { _, _, _ -> reply }.test(groq, "k")
        assertEquals(CloudTranscriptionClient.TestOutcome.WORKS, outcome(HttpReply(200, """{"text":"","segments":[]}""")))
        assertEquals(CloudTranscriptionClient.TestOutcome.NO_TIMINGS, outcome(HttpReply(200, """{"text":""}""")))
        assertEquals(CloudTranscriptionClient.TestOutcome.BAD_KEY, outcome(HttpReply(401, "")))
        assertEquals(CloudTranscriptionClient.TestOutcome.UNREACHABLE, outcome(HttpReply(503, "")))
        assertEquals(CloudTranscriptionClient.TestOutcome.REFUSED, outcome(HttpReply(404, "")))
    }

    private fun assertNoSegments(body: String) {
        try {
            VerboseJsonParser.segments(body, "Groq")
            fail("untimed words were accepted")
        } catch (e: CloudError) {
            assertEquals(CloudError.Kind.NO_SEGMENTS, e.kind)
        }
    }
}
