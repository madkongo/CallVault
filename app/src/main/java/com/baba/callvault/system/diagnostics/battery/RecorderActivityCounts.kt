/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics.battery

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * How often the app tried to (re)start the recorder or rebuild its ADB link inside the battery period, counted from
 * lines the app ALREADY writes to its debug log. Nothing new is tracked: with debug logging off there is nothing to
 * count, and the report says so rather than showing zeros.
 */
object RecorderActivityCounts {

    /**
     * @property launchAttempts the ADB launcher's "launching recorder daemon" lines.
     * @property relaunches the keep-alive's "relaunching" decisions (each normally followed by a launch attempt).
     * @property adbRebuilds the keep-alive rebuilding the ADB connection after relaunches kept timing out.
     * @property logStartMillis the first entry's time, so a log shorter than the period is not read as a quiet one.
     */
    data class LogCounts(val launchAttempts: Int, val relaunches: Int, val adbRebuilds: Int, val logStartMillis: Long?)

    private const val STAMP_LENGTH = 23
    private const val LAUNCHER = "CV:RecorderLauncher: "
    private const val KEEP_ALIVE = "CV:DaemonKeepAlive: "

    /** Counts over [entries] (debug-log entries, `yyyy-MM-dd HH:mm:ss.SSS [L] TAG: message`) at or after [sinceMillis]. */
    fun count(entries: List<String>, sinceMillis: Long?, zone: TimeZone = TimeZone.getDefault()): LogCounts {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = zone }
        val timed = entries.mapNotNull { entry -> timeOf(entry, format)?.let { it to entry } }
        val inPeriod = timed.filter { (time, _) -> sinceMillis == null || time >= sinceMillis }.map { it.second }
        return LogCounts(
            launchAttempts = inPeriod.count { it.contains(LAUNCHER) && it.contains("launching recorder daemon") },
            relaunches = inPeriod.count { it.contains(KEEP_ALIVE) && it.contains("— relaunching") },
            adbRebuilds = inPeriod.count { it.contains(KEEP_ALIVE) && it.contains("rebuilding the ADB connection") },
            logStartMillis = timed.minOfOrNull { it.first },
        )
    }

    private fun timeOf(entry: String, format: SimpleDateFormat): Long? {
        if (entry.length < STAMP_LENGTH) return null
        return try {
            format.parse(entry.substring(0, STAMP_LENGTH))?.time
        } catch (_: ParseException) {
            null
        }
    }
}
