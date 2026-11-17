/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The date and time a custom file-name template can write as two separate tags (#82): `{day}` (`20261112`),
 * `{day-}` (`2026-11-12`), `{time}` (`143520`) and `{time-}` (`14-35-20`).
 *
 * One place knows these shapes, so the list, the merge naming, the log redaction and the template check all
 * read the same thing. A stamp is a day, ONE joiner (`_`, `-`, a space or `T`) and a time, in that order:
 * the pair is what the app can find again in a name, and day-before-time is what keeps names sortable.
 * Seconds are always there, so two calls can only share a name when they start in the same second.
 */
internal object FileNameStamp {

    const val DAY_TAG = "{day}"
    const val DAY_DASHED_TAG = "{day-}"
    const val TIME_TAG = "{time}"
    const val TIME_DASHED_TAG = "{time-}"

    /** The joiners allowed between the day and the time. */
    const val JOINERS = "_ T-"

    const val DAY_PATTERN = """(?:\d{8}|\d{4}-\d{2}-\d{2})"""
    const val TIME_PATTERN = """(?:\d{6}|\d{2}-\d{2}-\d{2})"""
    private const val JOINER = """[_ T-]"""

    /** A day, a joiner and a time, for building longer patterns. No lookarounds: the caller adds its own. */
    const val PAIR_PATTERN = "$DAY_PATTERN$JOINER$TIME_PATTERN"

    private val WHOLE = Regex("""(\d{4})-?(\d{2})-?(\d{2})$JOINER(\d{2})-?(\d{2})-?(\d{2})""")

    /** A stamp that stands alone: not inside a longer run of digits. */
    private val INSIDE = Regex("""(?<!\d)(\d{4})(-?)(\d{2})-?(\d{2})($JOINER)(\d{2})(-?)(\d{2})-?(\d{2})(?!\d)""")

    /** [raw] when it is exactly one day+time stamp, as `yyyyMMdd_HHmmss`; null otherwise. */
    fun normalize(raw: String): String? {
        val m = WHOLE.matchEntire(raw.trim()) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        return "$y$mo${d}_$h$mi$s"
    }

    /**
     * [stem] with its day+time stamp moved forward by [seconds], written in the same shapes it was found in
     * (dashed or not, same joiner). Null when the stem holds no such stamp, or one that is not a real time.
     */
    fun bumped(stem: String, seconds: Int): String? {
        val m = INSIDE.find(stem) ?: return null
        val g = m.groupValues
        val dayDashed = g[2].isNotEmpty()
        val timeDashed = g[7].isNotEmpty()
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
        val moved = runCatching { format.parse(g[1] + g[3] + g[4] + g[6] + g[8] + g[9]) }.getOrNull()
            ?.let { format.format(it.time + seconds * 1000L) } ?: return null
        val day = if (dayDashed) "${moved.substring(0, 4)}-${moved.substring(4, 6)}-${moved.substring(6, 8)}" else moved.substring(0, 8)
        val time = if (timeDashed) "${moved.substring(8, 10)}-${moved.substring(10, 12)}-${moved.substring(12, 14)}" else moved.substring(8, 14)
        return stem.replaceRange(m.range, day + g[5] + time)
    }

    /** The `{day}`/`{day-}` + joiner + `{time}`/`{time-}` pair in a template, or null when it has none. */
    private val TEMPLATE_PAIR = Regex("""\{day-?\}[_ T-]\{time-?\}""")

    fun pairInTemplate(template: String): IntRange? = TEMPLATE_PAIR.find(template)?.range
}
