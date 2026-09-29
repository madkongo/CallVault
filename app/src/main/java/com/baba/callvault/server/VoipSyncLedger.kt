/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import java.util.Locale

/**
 * What a VoIP capture knows about the alignment of its two sides, kept so that ONE call's log from a
 * phone we do not have can say which side moved, by how much, and why (issue #41: the far party
 * heard earlier than the near party on a Galaxy S21 Ultra).
 *
 * The file is mono, so nothing can be measured from it afterwards; the measurement has to be made
 * here, while the two sides still exist. Two things are recorded:
 *
 *  - **The offset itself.** Every chunk carries the real time its audio was captured, from the HAL
 *    (`AudioRecord.getTimestamp`) when the device gives one, else the moment it was read. For each
 *    pair written to the file the ledger takes near minus far. **Positive = the near audio at that
 *    file position is newer than the far audio beside it, so the far audio sits LATER in the file
 *    than it happened: the far party sounds late.** Negative = the far party sounds early, which is
 *    what issue #41 reports. (Worked through with a 1 s near stall at the start: near frame 0 was
 *    captured at t=1 s, far frame 0 at t=0, offset +1 s; a far word at t=0.5 lands at file 0.5 s next
 *    to the near reply from t=1.5 — the far word has moved later by the stall.) Sampled into the log
 *    every few seconds so a drift shows as a trend. Measured on the OP9 2026-09-22: +106 ms at the
 *    start, +372 ms after three mic re-takes — the far party late there, not early.
 *  - **Every way a side can lose time**, per side: chunks read, silence stand-ins (with the longest
 *    run — one long stall and many short ones are different faults), chunks dropped on a full queue
 *    (uncounted until now), all-zero chunks and mic re-takes with the gap each cost.
 *
 * Counters are touched from the feeder threads and read from the mux thread, hence the volatiles;
 * a snapshot that is a chunk stale is fine, a torn count is not.
 */
internal class VoipSyncLedger(private val sampleRate: Int, private val chunkFrames: Int) {

    /** One capture direction's bookkeeping. */
    class Side(val name: String, private val sampleRate: Int) {
        @Volatile var chunksRead = 0L; private set
        @Volatile var chunksDropped = 0L; private set
        @Volatile var chunksSubstituted = 0L; private set
        @Volatile var chunksDiscarded = 0L; private set
        @Volatile var zeroChunks = 0L; private set
        @Volatile var retakes = 0; private set
        @Volatile var retakeGapTotalMs = 0L; private set

        /**
         * What Android said at each re-take (`isClientSilenced`): true = it really had silenced us, false =
         * the zeros were a pause, unknown = it would not say. Evidence for backlog #9 — nothing reads these.
         */
        @Volatile var retakesSilenced = 0; private set
        @Volatile var retakesNotSilenced = 0; private set
        @Volatile var retakesUnknown = 0; private set
        @Volatile var longestStallChunks = 0; private set
        @Volatile var currentStallChunks = 0; private set

        /**
         * "hal" while a HAL fix is in hand, "read" while the raw read moment stands in, "read-" while
         * the read moment corrected by a latency learned from an earlier fix stands in.
         */
        @Volatile var timeSource = "read"; private set
        @Volatile private var tsFrame = -1L
        @Volatile private var tsNanos = 0L

        /** Read moment minus content time, learned whenever a fix is in hand; -1 until then. */
        @Volatile private var learnedLatencyNanos = -1L

        /** Whether the current record still has no fix — the feeder asks the HAL every chunk until it does. */
        val needsTimestamp: Boolean get() = tsFrame < 0

        fun read() { chunksRead++; currentStallChunks = 0 }
        fun dropped() { chunksDropped++ }
        fun discarded() { chunksDiscarded++ }
        fun substituted() {
            chunksSubstituted++
            currentStallChunks++
            if (currentStallChunks > longestStallChunks) longestStallChunks = currentStallChunks
        }
        fun zeroChunk() { zeroChunks++ }
        fun retake(gapNanos: Long, platformSilenced: Boolean? = null) {
            retakes++
            retakeGapTotalMs += gapNanos / 1_000_000L
            when (platformSilenced) {
                true -> retakesSilenced++
                false -> retakesNotSilenced++
                null -> retakesUnknown++
            }
        }

        /** The HAL's latest (frame, time) fix for the record currently feeding this side. */
        fun timestamp(framePosition: Long, nanos: Long) {
            tsFrame = framePosition; tsNanos = nanos; timeSource = "hal"
        }

        /** Forgets the fix: a fresh record (re-take, resume) numbers its frames from zero again. */
        fun newRecord() {
            tsFrame = -1L
            timeSource = if (learnedLatencyNanos >= 0) "read-" else "read"
        }

        /**
         * When the audio starting at [frameIndex] of the current record was captured, in the
         * monotonic clock — from the HAL fix when there is one; else [readAtNanos] less the read
         * latency learned from an earlier fix; else [readAtNanos] itself.
         */
        fun contentNanos(frameIndex: Long, readAtNanos: Long): Long {
            if (tsFrame < 0) return if (learnedLatencyNanos >= 0) readAtNanos - learnedLatencyNanos else readAtNanos
            val content = tsNanos + (frameIndex - tsFrame) * 1_000_000_000L / sampleRate
            learnedLatencyNanos = (readAtNanos - content).coerceAtLeast(0L)
            return content
        }

        fun format(queued: Int): String =
            "$name{read=$chunksRead sub=$chunksSubstituted stall=${longestStallChunks} drop=$chunksDropped disc=$chunksDiscarded q=$queued" +
                (if (name == "near") {
                    " zero=$zeroChunks retake=$retakes/${retakeGapTotalMs}ms" +
                        "(silenced=$retakesSilenced quiet=$retakesNotSilenced unknown=$retakesUnknown)"
                } else "") +
                " ts=$timeSource}"
    }

    val near = Side("near", sampleRate)
    val far = Side("far", sampleRate)

    @Volatile var lastOffsetMs: Long? = null; private set
    @Volatile var firstOffsetMs: Long? = null; private set
    @Volatile var minOffsetMs: Long? = null; private set
    @Volatile var maxOffsetMs: Long? = null; private set

    /** One pair written to the file; null on a side means a silence stand-in went in its place. */
    fun paired(nearContentNanos: Long?, farContentNanos: Long?) {
        if (nearContentNanos == null || farContentNanos == null) { lastOffsetMs = null; return }
        val offset = (nearContentNanos - farContentNanos) / 1_000_000L
        lastOffsetMs = offset
        if (firstOffsetMs == null) firstOffsetMs = offset
        minOffsetMs = minOffsetMs?.let { minOf(it, offset) } ?: offset
        maxOffsetMs = maxOffsetMs?.let { maxOf(it, offset) } ?: offset
    }

    /** The periodic line: where the file is, where the wall clock is, and the sides' state. */
    fun snapshot(fileFrames: Long, wallNanos: Long, nearQueued: Int, farQueued: Int): String =
        "VoIP sync t=${seconds(fileFrames)} wall=${wallSeconds(wallNanos)} offset=${ms(lastOffsetMs)} " +
            "${near.format(nearQueued)} ${far.format(farQueued)}"

    /** The end-of-call line, with the offset's course over the call. */
    fun summary(fileFrames: Long, wallNanos: Long): String =
        "VoIP sync summary file=${seconds(fileFrames)} wall=${wallSeconds(wallNanos)} " +
            "offset first=${ms(firstOffsetMs)} last=${ms(lastOffsetMs)} min=${ms(minOffsetMs)} max=${ms(maxOffsetMs)} " +
            "(+ = far party late, - = early) ${near.format(0)} ${far.format(0)}"

    private fun seconds(frames: Long) = String.format(Locale.US, "%.1fs", frames.toDouble() / sampleRate)
    private fun wallSeconds(nanos: Long) = String.format(Locale.US, "%.1fs", nanos / 1_000_000_000.0)
    private fun ms(v: Long?) = v?.let { (if (it >= 0) "+" else "") + "${it}ms" } ?: "n/a"
}
