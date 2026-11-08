/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.clips

import java.util.Locale

/**
 * The part of a recording to share, in milliseconds from its start. Always inside the recording and at least
 * [MIN_MS] long — every way of building one goes through [of], which enforces both.
 */
data class ClipRange(val startMs: Long, val endMs: Long) {

    val lengthMs: Long get() = endMs - startMs

    /** Moves the start, keeping [MIN_MS] before the end. */
    fun withStart(ms: Long, durationMs: Long): ClipRange = of(ms.coerceAtMost(endMs - MIN_MS), endMs, durationMs)

    /** Moves the end, keeping [MIN_MS] after the start. */
    fun withEnd(ms: Long, durationMs: Long): ClipRange = of(startMs, ms.coerceAtLeast(startMs + MIN_MS), durationMs)

    companion object {
        const val MIN_MS = 1_000L

        /** Selection when opened from the playing position: this much from where the user is listening. */
        const val FROM_POSITION_MS = 30_000L

        /** A valid range for a recording of [durationMs], or the whole recording if the input makes no sense. */
        fun of(startMs: Long, endMs: Long, durationMs: Long): ClipRange {
            val total = durationMs.coerceAtLeast(0L)
            if (total <= MIN_MS) return ClipRange(0L, total)
            val start = startMs.coerceIn(0L, total - MIN_MS)
            val end = endMs.coerceIn(start + MIN_MS, total)
            return ClipRange(start, end)
        }

        /** The whole recording: what the list's "Share a part" opens with. */
        fun whole(durationMs: Long): ClipRange = of(0L, durationMs, durationMs)

        /**
         * [FROM_POSITION_MS] from [positionMs]: what the playback screen opens with. Near the end it slides back so
         * the selection is still that long when the recording allows.
         */
        fun fromPosition(positionMs: Long, durationMs: Long): ClipRange {
            val end = (positionMs + FROM_POSITION_MS).coerceAtMost(durationMs)
            val start = (end - FROM_POSITION_MS).coerceAtMost(positionMs)
            return of(start, end, durationMs)
        }

        /** `m:ss`, or `h:mm:ss` from an hour. */
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0L)) / 1000
            return if (s >= 3600) {
                String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            } else {
                String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
            }
        }
    }
}

/** The clip's file name: the recording's name and the range, e.g. `Dana Levi (1-12 – 2-40).ogg`. */
object ClipNaming {
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Invisible direction marks (the list wraps names in them); dropped, not replaced. */
    private val FORMAT = Regex("\\p{Cf}")
    private const val MAX_BASE = 80

    /** @param name who the call was with, or the recording's file name without its extension. */
    fun fileName(name: String, range: ClipRange, extension: String): String {
        val base = name.replace(FORMAT, "").replace(UNSAFE, "_").trim().take(MAX_BASE)
            .ifEmpty { "clip" }
        // Colons are not allowed in file names on many targets, so the clock uses dashes.
        val from = ClipRange.clock(range.startMs).replace(':', '-')
        val to = ClipRange.clock(range.endMs).replace(':', '-')
        return "$base ($from – $to).$extension"
    }
}
