/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * A `multipart/form-data` body, built in memory: a few text fields and one file.
 *
 * Hand-rolled rather than a new HTTP dependency (the app uses HttpURLConnection everywhere); the
 * format is small and fully specified by RFC 7578. The body is at most one chunk of WAV, ~11.5 MB.
 */
class MultipartForm(val boundary: String = "CallVault-" + UUID.randomUUID().toString()) {

    init {
        // A boundary that could occur in a field would split the body in the wrong place.
        require(boundary.isNotEmpty() && boundary.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Bad multipart boundary"
        }
    }

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    /**
     * @param fields name → value, in order. A field whose name or value holds a line break is refused:
     *   it would let a value forge a header of the next part.
     */
    fun build(
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        fileContentType: String,
        fileBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(fileBytes.size + OVERHEAD_BYTES)
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        for ((name, value) in fields) {
            requireSafe(name)
            requireSafe(value)
            write("--$boundary$CRLF")
            write("Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF")
            write(value)
            write(CRLF)
        }
        requireSafe(fileField)
        requireSafe(fileName)
        requireSafe(fileContentType)
        write("--$boundary$CRLF")
        write("Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"$CRLF")
        write("Content-Type: $fileContentType$CRLF$CRLF")
        out.write(fileBytes)
        write(CRLF)
        write("--$boundary--$CRLF")
        return out.toByteArray()
    }

    private fun requireSafe(text: String) {
        require(text.none { it == '\r' || it == '\n' || it == '"' }) { "Multipart field holds a forbidden character" }
    }

    private companion object {
        const val CRLF = "\r\n"
        const val OVERHEAD_BYTES = 1024
    }
}
