/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure pieces of the cloud path (issue #48): presets, WAV, multipart, error mapping. */
class CloudPiecesTest {

    // ── Presets ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `groq preset points at the openai-compatible route with turbo`() {
        val endpoint = CloudEndpoint.resolve(TranscriptionService.GROQ, null, null)!!
        assertEquals("https://api.groq.com/openai/v1/audio/transcriptions", endpoint.transcriptionsUrl)
        assertEquals("whisper-large-v3-turbo", endpoint.model)
        assertEquals("cloud:groq/whisper-large-v3-turbo", endpoint.engineId)
        assertEquals("https://console.groq.com/keys", TranscriptionService.GROQ.preset!!.keyPageUrl)
    }

    @Test
    fun `openrouter preset uses its transcription route`() {
        val endpoint = CloudEndpoint.resolve(TranscriptionService.OPENROUTER, null, null)!!
        assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", endpoint.transcriptionsUrl)
        assertEquals("cloud:openrouter/microsoft/mai-transcribe-2", endpoint.engineId)
    }

    @Test
    fun `on device has no endpoint and is the default`() {
        assertNull(CloudEndpoint.resolve(TranscriptionService.ON_DEVICE, "https://x.example", "m"))
        assertEquals(TranscriptionService.ON_DEVICE, TranscriptionService.DEFAULT)
        assertFalse(TranscriptionService.ON_DEVICE.isCloud)
    }

    @Test
    fun `an unknown stored key reads as on device, never as a cloud service`() {
        assertEquals(TranscriptionService.ON_DEVICE, TranscriptionService.fromKey("grok"))
        assertEquals(TranscriptionService.ON_DEVICE, TranscriptionService.fromKey(null))
        assertEquals(TranscriptionService.GROQ, TranscriptionService.fromKey("groq"))
    }

    @Test
    fun `custom needs an https address and a model`() {
        assertNull(CloudEndpoint.resolve(TranscriptionService.CUSTOM, "http://plain.example/v1", "m"))
        assertNull(CloudEndpoint.resolve(TranscriptionService.CUSTOM, "https://ok.example/v1", " "))
        assertNull(CloudEndpoint.resolve(TranscriptionService.CUSTOM, null, "m"))
        val ok = CloudEndpoint.resolve(TranscriptionService.CUSTOM, " https://ok.example/v1/ ", " whisper-1 ")!!
        assertEquals("https://ok.example/v1/audio/transcriptions", ok.transcriptionsUrl)
        assertEquals("cloud:custom/whisper-1", ok.engineId)
    }

    @Test
    fun `custom address refuses user info, queries and fragments`() {
        assertNull(CloudEndpoint.validCustomBaseUrl("https://user:pw@host.example/v1"))
        assertNull(CloudEndpoint.validCustomBaseUrl("https://host.example/v1?key=1"))
        assertNull(CloudEndpoint.validCustomBaseUrl("https:///v1"))
        assertNull(CloudEndpoint.validCustomBaseUrl("ftp://host.example"))
    }

    @Test
    fun `engine ids tell cloud from on-device models`() {
        assertTrue(CloudEndpoint.isCloudEngineId("cloud:groq/whisper-large-v3-turbo"))
        assertFalse(CloudEndpoint.isCloudEngineId("small-q5_1"))
        assertFalse(CloudEndpoint.isCloudEngineId(null))
    }

    // ── WAV ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `wav header describes 16 kHz mono 16-bit pcm`() {
        val wav = WavEncoder.encode(FloatArray(16_000))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, b.getInt(40))
        assertEquals(44 + 32_000, wav.size)
    }

    @Test
    fun `wav samples are clipped, not wrapped`() {
        val wav = WavEncoder.encode(floatArrayOf(2f, -2f, 0.5f))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Short.MAX_VALUE, b.getShort(44))
        assertEquals((-Short.MAX_VALUE).toShort(), b.getShort(46))
        assertEquals((0.5f * Short.MAX_VALUE).toInt().toShort(), b.getShort(48))
    }

    @Test
    fun `a six-minute chunk stays under the 25 MB upload limit`() {
        val bytes = WavEncoder.HEADER_BYTES + 6L * 60 * 16_000 * 2
        assertTrue(bytes < 25L * 1024 * 1024)
    }

    // ── Multipart ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `multipart body carries each field and the file between boundaries`() {
        val form = MultipartForm("b-1")
        val body = form.build(listOf("model" to "m", "language" to "he"), "file", "chunk.wav", "audio/wav", byteArrayOf(1, 2, 3))
        val text = String(body, Charsets.ISO_8859_1)
        assertEquals("multipart/form-data; boundary=b-1", form.contentType)
        assertTrue(text.startsWith("--b-1\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nm\r\n"))
        assertTrue(text.contains("--b-1\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nhe\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"chunk.wav\"\r\nContent-Type: audio/wav\r\n\r\n\u0001\u0002\u0003\r\n"))
        assertTrue(text.endsWith("--b-1--\r\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `multipart refuses a value that could forge a header`() {
        MultipartForm("b").build(listOf("model" to "m\r\nX: y"), "file", "a.wav", "audio/wav", byteArrayOf())
    }

    @Test
    fun `request asks for verbose json with segment timings and the pinned language`() {
        val endpoint = CloudEndpoint.resolve(TranscriptionService.GROQ, null, null)!!
        val fields = CloudTranscriptionClient().fields(endpoint, "he").toMap()
        assertEquals("verbose_json", fields["response_format"])
        assertEquals("segment", fields["timestamp_granularities[]"])
        assertEquals("he", fields["language"])
        assertEquals("whisper-large-v3-turbo", fields["model"])
        assertFalse("auto-detect sends no language", "language" in CloudTranscriptionClient().fields(endpoint, null).toMap())
    }

    @Test
    fun `only iso 639-1 codes are sent as the language`() {
        assertEquals("he", CloudTranscriptionClient.isoLanguage("he"))
        assertNull(CloudTranscriptionClient.isoLanguage("yue"))
        assertNull(CloudTranscriptionClient.isoLanguage(null))
    }

    // ── Errors ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `http statuses map to whether waiting could help`() {
        assertEquals(CloudError.Kind.BAD_KEY, CloudError.forHttpStatus(401, "Groq").kind)
        assertEquals(CloudError.Kind.BAD_KEY, CloudError.forHttpStatus(403, "Groq").kind)
        assertEquals(CloudError.Kind.TOO_LARGE, CloudError.forHttpStatus(413, "Groq").kind)
        assertEquals(CloudError.Kind.RETRY_LATER, CloudError.forHttpStatus(429, "Groq").kind)
        assertEquals(CloudError.Kind.RETRY_LATER, CloudError.forHttpStatus(503, "Groq").kind)
        assertEquals(CloudError.Kind.REJECTED, CloudError.forHttpStatus(400, "Groq").kind)
        assertFalse(CloudError.forHttpStatus(401, "Groq").isRetryable)
        assertTrue(CloudError.forHttpStatus(500, "Groq").isRetryable)
    }
}
