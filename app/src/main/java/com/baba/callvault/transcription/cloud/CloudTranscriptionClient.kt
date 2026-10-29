/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import com.baba.callvault.transcription.TranscriptSegment
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The answer to one POST: its status and, when it succeeded, its body. */
data class HttpReply(val status: Int, val body: String)

/** One HTTPS POST. Substituted in tests, which must never reach a real service. */
fun interface HttpPoster {
    /** @throws IOException when the server could not be reached or the connection broke. */
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply
}

/**
 * Sends one chunk of WAV to an OpenAI-compatible `/audio/transcriptions` and returns its segments.
 *
 * The key travels in the Authorization header and nowhere else: not in a URL, not in a message, not in
 * a log line. The contact-name prompt the on-device path uses is deliberately NOT sent — a name is
 * more than the service needs to hear.
 */
class CloudTranscriptionClient(private val http: HttpPoster = UrlConnectionPoster()) {

    /** The multipart fields for one request; split out so the request shape is testable. */
    internal fun fields(endpoint: CloudEndpoint, language: String?): List<Pair<String, String>> = buildList {
        add("model" to endpoint.model)
        add("response_format" to "verbose_json")
        add("timestamp_granularities[]" to "segment")
        add("temperature" to "0")
        isoLanguage(language)?.let { add("language" to it) }
    }

    /** @throws CloudError for every way this can fail. */
    fun transcribe(endpoint: CloudEndpoint, apiKey: String, wav: ByteArray, language: String?): List<TranscriptSegment> =
        transcribe(endpoint, apiKey, wav, WAV, language)

    /** As above, for audio in another container — the recording's own bytes, see [UploadFormat]. */
    fun transcribe(
        endpoint: CloudEndpoint,
        apiKey: String,
        audio: ByteArray,
        format: UploadFormat.Named,
        language: String?,
    ): List<TranscriptSegment> {
        val body = send(endpoint, apiKey, audio, format, language)
        return VerboseJsonParser.segments(body, endpoint.label)
    }

    /** What the Settings "Test" button reports. */
    enum class TestOutcome { WORKS, BAD_KEY, NO_TIMINGS, UNREACHABLE, REFUSED }

    /** One second of silence: proves the key, the address and the model, and that timings come back. */
    fun test(endpoint: CloudEndpoint, apiKey: String): TestOutcome {
        val body = try {
            send(endpoint, apiKey, WavEncoder.silence(), WAV, language = null)
        } catch (e: CloudError) {
            return when (e.kind) {
                CloudError.Kind.BAD_KEY -> TestOutcome.BAD_KEY
                CloudError.Kind.RETRY_LATER -> TestOutcome.UNREACHABLE
                else -> TestOutcome.REFUSED
            }
        }
        val result = runCatching { VerboseJsonParser.read(body) }.getOrNull() ?: return TestOutcome.REFUSED
        return if (result.hasSegmentsField) TestOutcome.WORKS else TestOutcome.NO_TIMINGS
    }

    private fun send(
        endpoint: CloudEndpoint,
        apiKey: String,
        audio: ByteArray,
        format: UploadFormat.Named,
        language: String?,
    ): String {
        if (apiKey.isBlank()) throw CloudError.notSetUp(endpoint.label)
        // A header value must be printable ASCII; anything else makes HttpURLConnection throw an
        // exception whose message can quote the header — the key. Refused here with our own words.
        if (!isPlausibleKey(apiKey.trim())) {
            throw CloudError(CloudError.Kind.BAD_KEY, "The ${endpoint.label} key has characters a key cannot contain. Paste it again.")
        }
        val form = MultipartForm()
        val payload = try {
            form.build(fields(endpoint, language), "file", format.fileName, format.contentType, audio)
        } catch (e: IllegalArgumentException) {
            // A Custom model name with a quote or a line break in it: refused, never sent.
            throw CloudError(CloudError.Kind.REJECTED, "The model name for ${endpoint.label} has a character it cannot contain.")
        }
        val reply = try {
            http.post(
                endpoint.transcriptionsUrl,
                mapOf(
                    "Authorization" to "Bearer ${apiKey.trim()}",
                    "Content-Type" to form.contentType,
                    "Accept" to "application/json",
                ),
                payload,
            )
        } catch (e: IOException) {
            throw CloudError.network(endpoint.label, e)
        }
        if (reply.status !in HTTP_OK_RANGE) throw CloudError.forHttpStatus(reply.status, endpoint.label)
        return reply.body
    }

    companion object {
        private val WAV = UploadFormat.Named("chunk.wav", "audio/wav")
        private val HTTP_OK_RANGE = 200..299
        private const val ISO_639_1_LENGTH = 2

        /**
         * The pinned language as ISO-639-1, or null to let the service detect it. Whisper's few
         * longer codes ("haw", "yue") are not ISO-639-1 and are left to detection.
         */
        private val PRINTABLE_ASCII = '!'..'~'

        /** Whether [key] can travel in a header at all: printable ASCII, no spaces. */
        fun isPlausibleKey(key: String): Boolean = key.isNotEmpty() && key.all { it in PRINTABLE_ASCII }

        fun isoLanguage(language: String?): String? =
            language?.trim()?.lowercase()?.takeIf { it.length == ISO_639_1_LENGTH && it.all(Char::isLetter) }
    }
}

/** [HttpPoster] over HttpURLConnection, the app's existing HTTP stack. */
class UrlConnectionPoster(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : HttpPoster {

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false // a redirect would resend the key somewhere else
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setFixedLengthStreamingMode(body.size)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                connection.inputStream.use { readCapped(it) }.toString(Charsets.UTF_8)
            } else {
                // Discarded unread: never surfaced — see CloudError — and of no use to us.
                ""
            }
            return HttpReply(status, text)
        } finally {
            connection.disconnect()
        }
    }

    /** At most [MAX_REPLY_BYTES]: a server that answers without end must not run the heap out. */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_REPLY_BYTES) throw IOException("Reply larger than $MAX_REPLY_BYTES bytes")
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        /** 📐 Hours of segments in verbose_json fit in far less; one pass is at most six minutes. */
        const val MAX_REPLY_BYTES = 5 * 1024 * 1024
        const val BUFFER_BYTES = 16 * 1024
        const val CONNECT_TIMEOUT_MS = 20_000

        /** OpenRouter says upstream providers time out after 60 s; this leaves room for the upload. */
        const val READ_TIMEOUT_MS = 120_000
    }
}
