/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.echo

/**
 * [EchoAligner] for the capture loop: never allowed to break a recording. If it throws once, the far
 * side passes through raw for the rest of the call, exactly as before this existed.
 */
class SafeFarAligner(chunkBytes: Int, private val log: (String) -> Unit) {

    private val aligner = EchoAligner()
    private val out = ByteArray(chunkBytes)
    private var failed = false
    private var loggedDelay = 0

    /** The far chunk to mix: lined up with its leak into [near], or [far] itself. */
    fun align(near: ByteArray, far: ByteArray): ByteArray {
        if (failed) return far
        return try {
            aligner.alignFar(near, far, out)
            if (aligner.appliedDelayFrames != loggedDelay) {
                loggedDelay = aligner.appliedDelayFrames
                log(String.format(java.util.Locale.ROOT, "VoIP echo: far voice found in the mic %.1f ms late (%s); far side delayed to match", aligner.appliedDelayMs, aligner.lastEstimate))
            }
            out
        } catch (t: Throwable) {
            failed = true
            log("VoIP echo: aligner failed, far side left as captured for this call: ${t.message}")
            far
        }
    }
}
