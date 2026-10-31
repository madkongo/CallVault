/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

/**
 * How a recording's own bytes are named when they are uploaded as they are, without decoding.
 *
 * Decoding to WAV for upload cost ~20 s per five-minute pass on the OP9 (Android's Opus decoder runs
 * ~12-15x real time), against ~2 s for the upload and the answer. The services decode compressed audio
 * themselves, so the recording goes up as it is wherever its container is one they take.
 *
 * Accepted list: Groq's documented types (flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm). OpenRouter and
 * Custom servers are not guaranteed to take all of them; a refusal falls back to WAV (see [CloudTranscriber]).
 */
object UploadFormat {

    /** The name and content type to upload under. */
    data class Named(val fileName: String, val contentType: String)

    /**
     * 📐 The largest recording sent in one piece. Under the 25 MB upload limit Groq and OpenRouter state,
     * with room for the multipart framing. Opus at the app's default 24 kbps is ~3 KB/s, so this is about
     * 1 h 50 min of call — every call under the 60-minute transcription limit goes up in ONE pass, with
     * no seam at all. Larger files (or other formats) are cut into passes.
     */
    const val SINGLE_UPLOAD_MAX_BYTES = 20L * 1024 * 1024

    private val BY_EXTENSION = mapOf(
        // An .opus file is Ogg; Groq's list names the container, so it goes up as .ogg.
        "ogg" to Named("audio.ogg", "audio/ogg"),
        "oga" to Named("audio.ogg", "audio/ogg"),
        "opus" to Named("audio.ogg", "audio/ogg"),
        "m4a" to Named("audio.m4a", "audio/mp4"),
        "mp4" to Named("audio.mp4", "audio/mp4"),
        "mp3" to Named("audio.mp3", "audio/mpeg"),
        "mpeg" to Named("audio.mpeg", "audio/mpeg"),
        "mpga" to Named("audio.mpga", "audio/mpeg"),
        "wav" to Named("audio.wav", "audio/wav"),
        "flac" to Named("audio.flac", "audio/flac"),
        "webm" to Named("audio.webm", "audio/webm"),
    )

    /** The upload name for a file called [fileName], or null when its type is not one the services take. */
    fun forFileName(fileName: String?): Named? {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return BY_EXTENSION[extension]
    }

    /** Whether a file of [sizeBytes] named [fileName] can go up whole, in one request. */
    fun sendsWhole(fileName: String?, sizeBytes: Long): Boolean =
        sizeBytes in 1..SINGLE_UPLOAD_MAX_BYTES && forFileName(fileName) != null

    /**
     * The container a cut pass is remuxed into (no re-encode), by the track's codec, or null when only a
     * decode to WAV will do. Opus → Ogg, AAC → MPEG-4: the two the app records, and the two
     * `MediaMuxer` writes on Android 10+.
     */
    fun forRemuxedCodec(trackMime: String?): Named? = when (trackMime) {
        "audio/opus" -> Named("chunk.ogg", "audio/ogg")
        "audio/mp4a-latm" -> Named("chunk.m4a", "audio/mp4")
        else -> null
    }
}
