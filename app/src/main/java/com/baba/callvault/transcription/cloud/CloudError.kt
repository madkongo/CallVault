/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

/**
 * Why a cloud transcription did not produce a transcript, and whether waiting could change that.
 *
 * The message is ours, never the server's body: a response body is untrusted text of unknown size,
 * and some services echo request details back. It lands in the transcript row's error and in logs.
 */
class CloudError(
    val kind: Kind,
    message: String,
    /** The HTTP status when there was one, for the log line. */
    val httpStatus: Int? = null,
) : Exception(message) {

    enum class Kind(val isRetryable: Boolean) {
        /** No key saved, or Custom is missing its address or model. Retrying cannot help. */
        NOT_SET_UP(false),

        /** 401/403: the service refused the key. Retrying would only be refused again. */
        BAD_KEY(false),

        /** 413: this chunk was too large for the service. */
        TOO_LARGE(false),

        /** 429, 5xx, a timeout or no network: worth trying again after a back-off. */
        RETRY_LATER(true),

        /** The answer had words but no timings — saving untimed text would break seek and speakers. */
        NO_SEGMENTS(false),

        /** Any other refusal (400, 404 …): usually a model name the service does not have. */
        REJECTED(false),
    }

    val isRetryable: Boolean get() = kind.isRetryable

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERRORS = 500

        /** The error for a non-2xx [status] from [service]. */
        fun forHttpStatus(status: Int, service: String): CloudError = when {
            status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN -> CloudError(
                Kind.BAD_KEY,
                "$service refused the key (HTTP $status). Check it in Settings, Where to transcribe.",
                status,
            )
            status == HTTP_TOO_LARGE -> CloudError(
                Kind.TOO_LARGE, "$service said the audio was too large (HTTP 413).", status,
            )
            status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERRORS -> CloudError(
                Kind.RETRY_LATER, "$service is busy or unavailable (HTTP $status); will try again.", status,
            )
            else -> CloudError(
                Kind.REJECTED,
                "$service refused the request (HTTP $status). Check the model name in Settings.",
                status,
            )
        }

        fun network(service: String, cause: Throwable): CloudError = CloudError(
            Kind.RETRY_LATER,
            // The exception's class only: its message can carry the URL, never the key, but stays out.
            "Could not reach $service (${cause.javaClass.simpleName}); will try again.",
        )

        fun noSegments(service: String): CloudError = CloudError(
            Kind.NO_SEGMENTS,
            "$service answered without timings, so the words cannot be lined up with the call. " +
                "Pick a model that returns segments.",
        )

        fun notSetUp(service: String): CloudError = CloudError(
            Kind.NOT_SET_UP,
            "$service is not set up: add your key in Settings, Where to transcribe.",
        )
    }
}
