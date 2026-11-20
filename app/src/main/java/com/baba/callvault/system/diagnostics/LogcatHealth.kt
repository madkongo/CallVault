/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Says whether the phone's log is current, and pulls the debugging/USB lines out of all of it.
 *
 * Pure, so it is tested.
 */
object LogcatHealth {

    const val MAX_DEBUG_LINES = 150

    private val STAMP = Regex("^(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\.\\d{3}\\s")

    // Same rule as UsbOffLogWindow: capitalised forms inside identifiers, lower-case ones only at a word start.
    private val DEBUGGING = Regex("Adb|ADB|Usb|USB|(?<![A-Za-z])(adb|usb)|[Dd]ebugg|[Dd]evelop")

    fun render(raw: String, ringSizes: String?): String {
        val stamps = raw.lineSequence().mapNotNull { STAMP.find(it)?.groupValues?.get(1) }.toList()
        return render(stamps.size, stamps.firstOrNull(), stamps.lastOrNull(), ringSizes)
    }

    /** The same, from counts the recorder made while cutting the log down on the phone. */
    fun render(read: Int, oldest: String?, newest: String?, ringSizes: String?): String = buildString {
        appendLine("--- Is the phone's log current? ---")
        appendLine("Lines read: $read. Oldest: ${oldest ?: "?"}. Newest: ${newest ?: "?"}.")
        val sizes = ringSizes?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
        if (sizes.isNotEmpty()) {
            appendLine("Log buffers:")
            sizes.take(MAX_SIZE_LINES).forEach { appendLine("    $it") }
        }
    }

    /**
     * The debugging/USB lines of [lines] that are ours to share, newest [MAX_DEBUG_LINES].
     *
     * A keyword alone is not enough: the vivo report of 2026-11-20 listed a codec's "app developers", a libc probe
     * of `ro.debuggable` and a binder line from other apps' pids. A line is kept only when one of our processes
     * wrote it (windowed, as in [SystemLogFilter.select]), when a debugging service wrote it ([isDebuggingSystemLine]),
     * or when a system line names our package or uid.
     */
    fun debuggingLines(lines: List<String>, ours: SystemLogFilter.Ours): List<String> {
        val windows = SystemLogFilter.ownWindows(lines, ours)
        val namesUs = SystemLogFilter.namesUsMatcher(ours)
        return lines.filter { line ->
            if (!isKeywordLine(line)) return@filter false
            val (pid, at) = SystemLogFilter.pidAndTime(line) ?: return@filter false
            windows.any { it.pid == pid && at in it.fromMs..it.toMs } || isDebuggingSystemLine(line) ||
                (SystemLogFilter.isSystemTagLine(line) && namesUs(line))
        }.takeLast(MAX_DEBUG_LINES)
    }

    /**
     * A debugging line that says nothing about any other app: written by a debugging/USB service, or naming one of
     * the debugging switches. The second is kept from any pid on purpose — the pid of a `Setting adb_enabled has
     * moved …` line is how a report names who wrote the switch, and the text is the platform's own.
     */
    fun isDebuggingSystemLine(line: String): Boolean {
        if (!isKeywordLine(line)) return false
        val tag = SystemLogFilter.tagOf(line) ?: return false
        return tag in DEBUGGING_TAGS || SWITCHES.containsMatchIn(line)
    }

    private fun isKeywordLine(line: String): Boolean = STAMP.containsMatchIn(line) && DEBUGGING.containsMatchIn(line)

    /** Android's adb and USB services, whose lines are about the switches and the transport, not about apps. */
    private val DEBUGGING_TAGS = setOf(
        "adbd", "AdbDebuggingManager", "AdbService", "AdbDebuggingManager.AdbConnectionPortPoller",
        "UsbDeviceManager", "UsbService", "UsbPortManager", "UsbHostManager", "UsbHandler",
    )

    /** The settings and properties that are the debugging switches themselves. */
    private val SWITCHES = Regex(
        "(?<![A-Za-z0-9_])(adb_enabled|adb_wifi_enabled|development_settings_enabled|allow_charging_adb|" +
            "sys\\.usb\\.config|sys\\.usb\\.state|persist\\.sys\\.usb\\.config)(?![A-Za-z0-9_])",
    )

    private const val MAX_SIZE_LINES = 8
}
