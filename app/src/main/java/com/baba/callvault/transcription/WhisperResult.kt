/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Reads whisper's return code (C42).
 *
 * `whisper_full` returns non-zero when a pass could not be decoded (a Silero model that fails to
 * load, an encoder error). That code used to be discarded, so such a pass looked exactly like
 * silence and a transcript with a multi-minute hole was stored as complete. A stop also returns a
 * non-zero code, so it is told apart by the abort flag, never by the code.
 */
object WhisperResult {

    class PassFailedException(val code: Int, message: String) : RuntimeException(message)

    /** Throws [PassFailedException] when pass [pass] of [passes] failed and nobody asked it to stop. */
    fun check(rc: Int, aborted: Boolean, pass: Int, passes: Int) {
        if (rc == 0 || aborted) return
        throw PassFailedException(rc, "whisper failed on pass $pass/$passes (code $rc)")
    }

    /** whisper_full's code for a failed VAD (the Silero context did not load, or detecting speech failed). */
    const val VAD_FAILED = -1

    /**
     * Runs one pass through [decode] (given the VAD model path, or null for none) and returns the VAD path
     * the successful decode used.
     *
     * A VAD failure (-1, nobody aborted) is retried ONCE with VAD off, so a corrupt-but-present VAD file
     * costs the trimming and not the transcript; the retry is logged through [log]. Any other failure,
     * or a retry that fails too, throws [PassFailedException].
     */
    fun runPass(
        vadModelPath: String?,
        aborted: () -> Boolean,
        pass: Int,
        passes: Int,
        log: (String) -> Unit,
        decode: (String?) -> Int,
    ): String? {
        val rc = decode(vadModelPath)
        if (rc == VAD_FAILED && vadModelPath != null && !aborted()) {
            log("whisper pass $pass/$passes failed with the VAD (code $rc); decoding it again without VAD")
            check(decode(null), aborted(), pass, passes)
            return null
        }
        check(rc, aborted(), pass, passes)
        return vadModelPath
    }
}
