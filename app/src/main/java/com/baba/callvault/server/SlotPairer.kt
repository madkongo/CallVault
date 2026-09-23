/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Pairs the two sides of an app call by the real time their audio was captured.
 *
 * The file is a run of slots, one chunk long, each standing for a moment of real time. For each
 * slot, each side contributes the chunk whose capture time falls in it, silence when its next
 * chunk is later (a stall, a late start, a re-take gap), and a chunk older than the slot is
 * discarded because its moment has already been written. A side can therefore never push the other
 * side along: however many times the microphone is re-taken, the far party stays where it happened.
 *
 * Why it exists: pairing by arrival — one chunk from each queue per loop — consumed the far queue at
 * the near side's pace. On a Galaxy S21 Ultra where the mic was re-taken 155 times in two minutes
 * (issue #41), the far party ended 6.4 s late, growing ~50 ms a second. Measured, not inferred:
 * `docs/dev-notes/2026-09-22-voip-sync-instrumentation.md`.
 *
 * The content time of a chunk is its HAL timestamp when the device gives one, else its read moment
 * (see [VoipSyncLedger.Side.contentNanos]); either way both sides are on the same monotonic clock.
 */
internal class SlotPairer(private val chunkNanos: Long) {

    enum class Take { TAKE, SILENCE, DISCARD }

    /** Half a chunk either way: HAL timestamps are not chunk-aligned and the slot grid is ours. */
    private val tolerance = chunkNanos / 2

    /** What the slot at [slotNanos] does with a side whose next chunk was captured at [headContentNanos]. */
    fun classify(slotNanos: Long, headContentNanos: Long?): Take = when {
        headContentNanos == null -> Take.SILENCE
        headContentNanos < slotNanos - tolerance -> Take.DISCARD
        headContentNanos >= slotNanos + tolerance -> Take.SILENCE
        else -> Take.TAKE
    }

    /**
     * Whether the slot at [slotNanos] may be written at [nowNanos]. A chunk's audio exists at its
     * content time but is read a record buffer later, up to ~160 ms for the near record, so the file
     * runs [GRACE_NANOS] behind real time; writing a slot sooner would silence a side whose chunk is
     * still on its way.
     */
    fun due(slotNanos: Long, nowNanos: Long): Boolean = nowNanos - slotNanos >= GRACE_NANOS

    /** The first slot: the earlier of the two first captures, so the later side is padded, never cut. */
    fun anchor(firstNear: Long?, firstFar: Long?): Long = when {
        firstNear == null -> firstFar ?: 0L
        firstFar == null -> firstNear
        else -> minOf(firstNear, firstFar)
    }

    companion object {
        /** Comfortably above the larger record's buffer (7680 frames = 160 ms at 48 kHz). */
        const val GRACE_NANOS = 250_000_000L
    }
}
