/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * The rule that stops a decoder which has gone quiet from holding a job forever.
 *
 * [AudioDecoder]'s loop waits at most 10 ms per call into the codec, but loops for as long as the
 * decoder has not said end-of-stream. A decoder that never says it — a broken or odd file — kept
 * a transcription "working" or a waveform undrawn until the user tapped Stop. Idea from the
 * slman890 fork (`8534f237`).
 *
 * **On progress, never on total time.** A long call is decoded in five-minute windows and a slow phone
 * may take a while over each; none of that is a stall. Only a stretch of [LIMIT_MS] with no output at
 * all is. Pure, so the rule is tested on the JVM with made-up clocks.
 */
internal object DecodeStall {

    /**
     * 120 s without a single output buffer. A healthy decoder hands one back every few milliseconds
     * (a 90-minute call decodes in about twelve seconds in total), so this is thousands of times the
     * longest normal gap: a slow-but-working phone never reaches it, and a stuck decoder is released
     * in two minutes rather than never. Same figure as the fork it came from.
     */
    const val LIMIT_MS = 120_000L

    sealed interface Verdict {
        /** Keep going; [lastProgressMs] is the clock to carry into the next turn. */
        data class Continue(val lastProgressMs: Long) : Verdict

        /** The decoder said end-of-stream: a normal finish, however late it came. */
        data object Finished : Verdict

        /** Nothing came out for [idleMs], which is past the limit. */
        data class Stalled(val idleMs: Long) : Verdict
    }

    /**
     * One turn of the decode loop.
     *
     * @param lastProgressMs when output last came out (or when decoding began).
     * @param madeProgress whether this turn produced output or a format change.
     * @param reachedEnd whether this turn saw end-of-stream.
     */
    fun next(
        lastProgressMs: Long,
        nowMs: Long,
        madeProgress: Boolean,
        reachedEnd: Boolean,
        limitMs: Long = LIMIT_MS,
    ): Verdict {
        if (reachedEnd) return Verdict.Finished
        if (madeProgress) return Verdict.Continue(nowMs)
        val idleMs = nowMs - lastProgressMs
        return if (idleMs > limitMs) Verdict.Stalled(idleMs) else Verdict.Continue(lastProgressMs)
    }
}

/** A decode given up because the decoder produced nothing for [DecodeStall.LIMIT_MS]. */
class DecodeStalledException(message: String) : IllegalStateException(message)
