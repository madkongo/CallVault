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
}
