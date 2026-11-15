/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An always-on record of every Drive copy: size, milliseconds per step, result, and the sweep's pace.
 *
 * Always on, like the setup journal, because the people this matters to are the ones with a big backlog who
 * never switched debug logging on. On 2.5.1 a Honor user's backlog copied about ten files an hour and the
 * log could not say which step took the time. Small and capped: one line per file.
 *
 * Written only from the app's main process (the copier process reports its timings back, it does not write).
 */
object DriveCopyJournal {

    private const val FILE_NAME = "drive_copy_journal.txt"
    private const val MAX_BYTES = 192 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun record(context: Context, rawLine: String) = synchronized(lock) {
        // Redacted like every other line that can leave the phone: a recording's name carries a contact or a number.
        val line = AppLogger.redactForReport(rawLine)
        android.util.Log.i("CV:DriveCopy", line)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("${stamp.format(Date())} $line\n")
            if (file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }
        Unit
    }

    fun readForReport(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    /** `files per minute`, one decimal, for the pace line. */
    fun pace(files: Int, elapsedMs: Long): String =
        if (elapsedMs <= 0L) "n/a" else "%.1f".format(Locale.US, files * 60_000.0 / elapsedMs)
}
