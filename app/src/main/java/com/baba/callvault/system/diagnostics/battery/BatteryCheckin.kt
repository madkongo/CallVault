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
 * **The format.** One CSV row per stat: `9,<uid>,<category>,<type>,<fields…>` (AOSP `BatteryStats.dumpCheckinLocked`,
 * checkin version 9 since Android 6). Category `l` is "since last full charge"; `i` is info. Device-wide rows use
 * uid 0, which root's own per-uid rows share — the row TYPE tells them apart.
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
 * **Privacy.** Only numbers leave, plus names of the app's own wake locks, jobs, alarms and processes. A name under
 * the shell or root uid is kept only if it is visibly ours; any other becomes `other`. No other uid's row is kept.
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

    /** Row types whose first field is a name; jobs and processes quote theirs. */
    private val NAMED_TYPES = setOf("wl", "wua", "jb", "pr")
    private val QUOTED_TYPES = setOf("jb", "pr")

    /** A name visibly ours, so it may be shown under the shell or root uid. */
    private val OURS = Regex("callvault", RegexOption.IGNORE_CASE)

    /** One checkin row. [fields] are the values after the type, unquoted. */
    data class Row(val uid: Int, val category: String, val type: String, val fields: List<String>)

    /** True when [text] is already a cut (from the recorder) rather than a raw dump. */
    fun isCut(text: String): Boolean = text.startsWith(HEADER)

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
        for (line in lines) {
            val row = row(line) ?: continue
            if (appUid == null) appUid = appUidFromMap(row, ownPackage, recorderUid)
            val out = keep(line, row, ownPackage, recorderUid, appUid) ?: continue
            if (size + out.length + 1 > maxChars) {
                left++
                continue
            }
            kept += out
            size += out.length + 1
        }
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
        if (parts.size < 4 || parts[0].toIntOrNull() == null) return null
        val uid = parts[1].toIntOrNull() ?: return null
        return Row(uid, parts[2], parts[3], parts.drop(4))
    }

    /** The app's full uid from a uid-map row naming [ownPackage], in the recorder's Android user. */
    private fun appUidFromMap(row: Row, ownPackage: String, recorderUid: Int): Int? {
        if (row.category != INFO || row.type != "uid" || row.fields.getOrNull(1) != ownPackage) return null
        val appId = row.fields.firstOrNull()?.toIntOrNull() ?: return null
        return recorderUid / PER_USER_RANGE * PER_USER_RANGE + appId
    }

    /** The line to keep for [row], possibly with a foreign name replaced, or null to drop it. */
    private fun keep(line: String, row: Row, ownPackage: String, recorderUid: Int, appUid: Int?): String? {
        if (row.category == INFO) {
            val isOurMap = row.type == "uid" && row.fields.getOrNull(1) == ownPackage
            return if (row.type == "vers" || isOurMap) line.trim() else null
        }
        if (row.category != SINCE_CHARGED) return null
        val isRootRow = row.uid == ROOT_UID
        if (isRootRow && row.type in GLOBAL_TYPES) return line.trim()
        if (row.type !in UID_TYPES) return null
        if (row.type == "pwi" && row.fields.firstOrNull() != "uid") return null
        val ours = row.uid == appUid || row.uid == SHELL_UID || row.uid == recorderUid
        if (!ours || (isRootRow && recorderUid != ROOT_UID)) return null
        return if (row.uid == appUid) line.trim() else withForeignNameReplaced(line.trim(), row)
    }

    private fun withForeignNameReplaced(line: String, row: Row): String {
        if (row.type !in NAMED_TYPES) return line
        val name = row.fields.firstOrNull() ?: return line
        if (OURS.containsMatchIn(name)) return line
        val replacement = if (row.type in QUOTED_TYPES) "\"$OTHER_NAME\"" else OTHER_NAME
        val values = row.fields.drop(1)
        val version = line.substringBefore(',')
        return (listOf(version, row.uid.toString(), row.category, row.type, replacement) + values).joinToString(",")
    }
}
