/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import com.baba.callvault.utils.FileNameStamp
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * How a merged recording gets a name of its own that still reads back as the same call. Pure, for testing.
 */
internal object MergeNaming {

    /** `yyyyMMdd_HHmmss.SSS`, wherever it sits in the name: every preset but the Honor one. */
    private val MILLIS_DATE = Regex("""(?<!\d)(\d{8}_\d{6})\.(\d{3})(?!\d)""")

    /** The Honor preset's trailing `_yyyyMMddHHmmss` (#56), which has no millisecond to nudge. */
    private val HONOR_DATE = Regex("""(@[^@]*_)(\d{14})$""")

    /**
     * [stem] with its timestamp moved by [by] milliseconds — or by [by] seconds for the Honor shape — so the
     * date shown stays the same call. A stem with no recognisable date gets `_<by>` appended.
     */
    fun bumpedStem(stem: String, by: Int): String {
        MILLIS_DATE.find(stem)?.let { m ->
            val millis = "%03d".format((m.groupValues[2].toInt() + by) % 1000)
            return stem.replaceRange(m.groups[2]!!.range, millis)
        }
        // A {day}/{time} stamp (#82) has no millisecond either, so its second moves.
        FileNameStamp.bumped(stem, by)?.let { return it }
        HONOR_DATE.find(stem)?.let { m ->
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
            val time = runCatching { format.parse(m.groupValues[2]) }.getOrNull() ?: return stem + "_" + by
            return stem.replaceRange(m.groups[2]!!.range, format.format(time.time + by * 1000L))
        }
        return stem + "_" + by
    }
}
