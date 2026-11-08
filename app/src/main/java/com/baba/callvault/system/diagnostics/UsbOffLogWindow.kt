/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Picks out what the phone itself logged around the moment USB debugging went off.
 *
 * The question a report has to answer is **who** switched it off. Android logs the switch in system_server
 * (`UsbDeviceManager: setAdbEnabled: false`), and the process that wrote the setting through the old
 * `Settings.Secure` name logs `Setting adb_enabled has moved …` under its **own** pid — so the pid column,
 * joined with `ps`, names the writer. Nothing can read this while `adbd` is down; it is read from the ring once
 * the recorder is back, which is why the window is chosen by timestamp.
 *
 * Only lines about debugging and USB are kept: a whole-window dump would carry every other app's log lines into
 * a report the user may post publicly. Case-sensitive on purpose — "usb" in any case also matches `StatusBar`.
 *
 * Pure, so it is tested without a device.
 */
object UsbOffLogWindow {

    const val LEAD_MS = 15_000L
    const val TRAIL_MS = 10_000L
    const val MAX_LINES = 120
    const val MAX_LINE_CHARS = 300

    // Capitalised forms match inside identifiers (UsbDeviceManager, AdbDebuggingManager); lower-case ones only at
    // the start of a word (adbd, adb_enabled, usb-ffs) — "Statusbar" carries a lower-case "usb" and flooded the
    // first OP9 capture with SystemUI lines.
    private val KEYWORDS = Regex("Adb|ADB|Usb|USB|(?<![A-Za-z])(adb|usb)|[Dd]ebugg|[Dd]evelop")
    private val STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val LINE = Regex("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(\\d+)\\s+\\d+\\s")

    /** The `logcat -T` start for an event at [eventMs]: [LEAD_MS] before it, in the phone's local time. */
    fun startStamp(eventMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(eventMs - LEAD_MS), zone).format(STAMP)

    /** Lines inside the window that mention debugging or USB, oldest first, at most [MAX_LINES]. */
    fun select(lines: Sequence<String>, eventMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val year = LocalDateTime.ofInstant(Instant.ofEpochMilli(eventMs), zone).year
        val from = eventMs - LEAD_MS
        val to = eventMs + TRAIL_MS
        return lines
            .filter { line ->
                val at = timeOf(line, year, zone) ?: return@filter false
                at in from..to && KEYWORDS.containsMatchIn(line)
            }
            .take(MAX_LINES)
            .map { it.take(MAX_LINE_CHARS) }
            .toList()
    }

    /** The pids that logged [lines]. */
    fun pidsOf(lines: List<String>): Set<Int> =
        lines.mapNotNull { LINE.find(it)?.groupValues?.get(7)?.toIntOrNull() }.toSet()

    /**
     * `pid → process` for [pids], from `ps -A -o USER,PID,ARGS`. A pid not in the list has exited since, and is
     * simply left out.
     */
    fun processNames(psOutput: String?, pids: Set<Int>): Map<Int, String> {
        if (psOutput == null || pids.isEmpty()) return emptyMap()
        return psOutput.lineSequence().mapNotNull { row ->
            val cols = row.trim().split(Regex("\\s+"), limit = 3)
            val pid = cols.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (pid in pids) pid to (cols.getOrNull(2) ?: "?") else null
        }.toMap()
    }

    private fun timeOf(line: String, year: Int, zone: ZoneId): Long? {
        val g = LINE.find(line)?.groupValues ?: return null
        return runCatching {
            LocalDateTime.of(year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt() * 1_000_000)
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
