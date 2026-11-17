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
 * Written by the app's main process, and by the copier process for its own life-cycle lines (start, each
 * message, memory trims, errors): only that process can say what happened inside it.
 */
object DriveCopyJournal {

    private const val FILE_NAME = "drive_copy_journal.txt"
    private const val MAX_BYTES = 384 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Adds one line. [fromCopier] marks a line written by the `:drive` process itself: it is tagged `[:drive]`,
     * appended to the same file (one short append per line), and never trims it — the trim rewrites the file
     * and only one process may do that.
     *
     * A failure to write is itself reported to logcat: the journal is the one place a failure must not vanish.
     */
    fun record(context: Context, rawLine: String, fromCopier: Boolean = false) = synchronized(lock) {
        // Redacted like every other line that can leave the phone: a recording's name carries a contact or a number.
        val line = (if (fromCopier) "[:drive] " else "") + AppLogger.redactForReport(rawLine)
        android.util.Log.i("CV:DriveCopy", line)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            file.appendText("${stamp.format(Date())} $line\n")
            if (!fromCopier && file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }.onFailure { android.util.Log.w("CV:DriveCopy", "journal write failed: ${it.javaClass.simpleName}: ${it.message}") }
        Unit
    }

    fun readForReport(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    /** `files per minute`, one decimal, for the pace line. */
    fun pace(files: Int, elapsedMs: Long): String =
        if (elapsedMs <= 0L) "n/a" else "%.1f".format(Locale.US, files * 60_000.0 / elapsedMs)
}
