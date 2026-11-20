/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

/**
 * The part of `dumpsys batterystats -c <our package>` that is about CallVault, cut in the recorder before it crosses
 * the binder.
 *
 * **Why.** Users report battery drain, but Android's battery screen hides most of what we cost: the recorder runs as
 * the shell uid 2000 (or root 0 under a root Shizuku), adbd runs as shell too, and Settings books every uid below
 * 10000 as "Android System". The shell user holds BATTERY_STATS, so the recorder can read the real per-uid numbers.
 *
 * **The format.** One CSV row per stat: `9,<uid>,<category>,<type>,<fields…>` (AOSP `BatteryStats.dumpCheckinLocked`).
 * The leading `9` is the ROW format version (`BATTERY_STATS_CHECKIN_VERSION`, 9 on Android 11 to 16); the separate
 * `9,0,i,vers,<report version>,<parcel version>,…` row carries the REPORT version (`CHECKIN_VERSION`: 35 on Android
 * 11-13, 36 on 14+, e.g. `vers,36,212` on the OP9). [BatteryUseParser] checks both. Category `l` is "since last full
 * charge"; `i` is info. Device-wide rows use uid 0, which root's own per-uid rows share — the row TYPE tells them apart.
 *
 * **Why `-c <package>` and never `--checkin`.** `--checkin` is the "real" checkin: it prints the SAVED previous
 * cycle when there is one and then deletes that file (BatteryStatsService.dumpUnmonitored), taking it from the
 * system's own checkin. `-c` is the same CSV with no side effect, and naming a package is what makes the service
 * drop the history (megabytes) — the per-uid rows are still printed for every uid, so the cut below is needed.
 *
 * **Who "us" is, without a caller-supplied argument.** The app's uid is read from batterystats' own uid map
 * (`9,0,i,uid,<appId>,<package>`), which is printed before any stat row; the recorder's uid is its own. Nothing the
 * caller sends reaches the command line or the filter. A caller-known uid ([cut]'s `knownAppUid`) is accepted only
 * as a typed Int, for the app's own re-cut of a raw answer from its ADB shell.
 *
 * **Privacy.** Only numbers leave, plus names of the app's own wake locks, jobs, alarms and processes. Under the shell
 * or root uid a named row is kept only when the name is our package (`<package>`, `<package>:<process>`,
 * `<package>/<component>`); every other one is dropped and only its numbers are summed into a single `other` row per
 * type, rebuilt from parsed numbers — so nothing in a foreign row's text, wherever it sits, can leave. No other
 * uid's row is kept.
 */
object BatteryCheckin {

    const val SHELL_UID = 2000
    const val ROOT_UID = 0

    /** First line of a cut, so a reader can tell a cut from a raw dump (the ADB fallback). */
    const val HEADER = "# callvault batterystats cut v1"

    /** Characters a cut may hold: ~400 KB as UTF-16, well under the binder's 1 MB. */
    const val MAX_CHARS = 200_000

    /** What a name that is not ours is replaced with. */
    const val OTHER_NAME = "other"

    private const val TRUNCATED = "# truncated: "
    private const val PER_USER_RANGE = 100_000
    private const val SINCE_CHARGED = "l"
    private const val INFO = "i"

    /** Device-wide rows worth keeping: battery time, discharge, screen time, the power summary. */
    private val GLOBAL_TYPES = setOf("bt", "dc", "m", "pws")

    /** Per-uid rows worth keeping. */
    private val UID_TYPES = setOf("cpu", "wl", "awl", "wua", "jb", "fgs", "nt", "pr", "pwi")

    /** Row types whose first field is a name. */
    private val NAMED_TYPES = setOf("wl", "wua", "jb", "pr")

    /** One checkin row. [fields] are the values after the type, unquoted; [version] is the row format version. */
    data class Row(val uid: Int, val category: String, val type: String, val fields: List<String>, val version: Int)

    /** True when [text] is already a cut (from the recorder) rather than a raw dump. */
    fun isCut(text: String): Boolean = text.startsWith(HEADER)

    /** The uid the recorder that made the cut [text] runs as, from its header; null for a raw dump. */
    fun recorderUidOf(text: String): Int? = headerValue(text, "recorder_uid")

    /** The app uid the cut [text] was made for, from its header; null for a raw dump or an unknown uid. */
    fun appUidOf(text: String): Int? = headerValue(text, "app_uid")

    private fun headerValue(text: String, key: String): Int? {
        if (!isCut(text)) return null
        return text.lineSequence().first().substringAfter(" $key=", "").takeWhile { it.isDigit() }.toIntOrNull()
    }

    /**
     * The rows of [lines] that are about us, bounded to [maxChars], as text starting with [HEADER].
     *
     * @param recorderUid the recorder's own uid: 2000, or 0 under a root Shizuku (then root's rows are ours).
     * @param knownAppUid the app's uid when the caller knows it; otherwise read from the dump's uid map.
     */
    fun cut(
        lines: Sequence<String>,
        ownPackage: String,
        recorderUid: Int,
        knownAppUid: Int?,
        maxChars: Int = MAX_CHARS,
    ): String {
        var appUid = knownAppUid
        val kept = ArrayList<String>()
        var size = 0
        var left = 0
        val foreign = LinkedHashMap<Pair<Int, String>, LongArray>()
        fun add(out: String) {
            if (size + out.length + 1 > maxChars) {
                left++
                return
            }
            kept += out
            size += out.length + 1
        }
        for (line in lines) {
            val row = row(line) ?: continue
            if (appUid == null) appUid = appUidFromMap(row, ownPackage, recorderUid)
            if (!isOurs(row, ownPackage, recorderUid, appUid)) continue
            if (row.uid != appUid && row.type in NAMED_TYPES && !isOurName(row.fields.firstOrNull(), ownPackage)) {
                val sums = foreign.getOrPut(row.uid to row.type) { LongArray(SUM_SLOTS) }
                numbersOf(row).forEachIndexed { i, n -> sums[i] += n }
                continue
            }
            add(line.trim())
        }
        foreign.forEach { (key, sums) -> add(otherRow(key.first, key.second, sums)) }
        return buildString {
            append(HEADER).append(" recorder_uid=").append(recorderUid).append(" app_uid=").append(appUid ?: "?").append('\n')
            kept.forEach { append(it).append('\n') }
            if (left > 0) append(TRUNCATED).append(left).append(" more rows left out to fit the recorder link\n")
        }
    }

    /** Splits a checkin row on commas, keeping a quoted field (a job or process name) whole and unquoted. */
    fun split(line: String): List<String> {
        val out = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += field.toString(); field.setLength(0) }
                else -> field.append(c)
            }
        }
        out += field.toString()
        return out
    }

    /** [line] as a row, or null when it is not one (history, help text, an error message, a blank line). */
    fun row(line: String): Row? {
        if (line.isBlank() || line.startsWith("#")) return null
        val parts = split(line.trim())
        val version = parts[0].toIntOrNull()
        if (parts.size < 4 || version == null) return null
        val uid = parts[1].toIntOrNull() ?: return null
        return Row(uid, parts[2], parts[3], parts.drop(4), version)
    }

    /** The app's full uid from a uid-map row naming [ownPackage], in the recorder's Android user. */
    private fun appUidFromMap(row: Row, ownPackage: String, recorderUid: Int): Int? {
        if (row.category != INFO || row.type != "uid" || row.fields.getOrNull(1) != ownPackage) return null
        val appId = row.fields.firstOrNull()?.toIntOrNull() ?: return null
        return recorderUid / PER_USER_RANGE * PER_USER_RANGE + appId
    }

    /** Whether [row] is one we keep at all: our info rows, the device-wide rows, and our uids' per-uid rows. */
    private fun isOurs(row: Row, ownPackage: String, recorderUid: Int, appUid: Int?): Boolean {
        if (row.category == INFO) return row.type == "vers" || (row.type == "uid" && row.fields.getOrNull(1) == ownPackage)
        if (row.category != SINCE_CHARGED) return false
        val isRootRow = row.uid == ROOT_UID
        if (isRootRow && row.type in GLOBAL_TYPES) return true
        if (row.type !in UID_TYPES) return false
        if (row.type == "pwi" && row.fields.firstOrNull() != "uid") return false
        val ours = row.uid == appUid || row.uid == SHELL_UID || row.uid == recorderUid
        return ours && !(isRootRow && recorderUid != ROOT_UID)
    }

    /** Our package exactly, one of its processes (`pkg:proc`) or components (`pkg/…`). Never a mere substring. */
    private fun isOurName(name: String?, ownPackage: String): Boolean =
        name != null && (name == ownPackage || name.startsWith("$ownPackage:") || name.startsWith("$ownPackage/"))

    /** Slots summed per foreign row type (the most any type uses: a process row's six numbers). */
    private const val SUM_SLOTS = 6

    /** Digits only: `toLongOrNull` alone also takes a sign, which let "+15551234567" through as a number. */
    private val PLAIN_DIGITS = Regex("^[0-9]{1,18}$")

    /** The numbers of a foreign named row, by type; anything that is not plain digits counts as zero. */
    private fun numbersOf(row: Row): List<Long> {
        fun n(i: Int) = row.fields.getOrNull(i)?.trim()?.takeIf { PLAIN_DIGITS.matches(it) }?.toLong() ?: 0L
        return when (row.type) {
            "wl" -> row.fields.indexOf("p").takeIf { it >= 2 }?.let { listOf(n(it - 1), n(it + 1)) } ?: listOf(0L, 0L)
            "wua" -> listOf(n(1))
            "jb" -> listOf(n(1), n(2))
            "pr" -> (1..6).map { n(it) }
            else -> emptyList()
        }
    }

    /** One `other` row of [type] for [uid], built from [sums] alone, in the layout the parser reads. */
    private fun otherRow(uid: Int, type: String, sums: LongArray): String {
        val values = when (type) {
            "wl" -> "$OTHER_NAME,0,f,0,0,0,0,${sums[0]},p,${sums[1]},0,0,${sums[0]},0,bp,0,0,0,0,0,w,0,0,0,0"
            "wua" -> "$OTHER_NAME,${sums[0]}"
            "jb" -> "\"$OTHER_NAME\",${sums[0]},${sums[1]},0,0"
            else -> "\"$OTHER_NAME\"," + sums.joinToString(",")
        }
        return "$ROW_VERSION,$uid,$SINCE_CHARGED,$type,$values"
    }

    private const val ROW_VERSION = 9
}
