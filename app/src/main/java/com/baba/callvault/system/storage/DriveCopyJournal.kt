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
 * message, memory trims, errors): only that process can say what happened inside it. Each process has its own
 * file; the report merges them.
 */
object DriveCopyJournal {

    private const val FILE_NAME = "drive_copy_journal.txt"
    private const val COPIER_FILE_NAME = "drive_copy_journal_copier.txt"

    /** Each of the two files; together they keep the size the single file had (about 384 KB). */
    internal const val MAX_BYTES_PER_FILE = 192 * 1024
    private const val STAMP_LENGTH = 23 // "yyyy-MM-dd HH:mm:ss.SSS"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Adds one line. A line written by the `:drive` process itself ([fromCopier]) is tagged `[:drive]` and goes to
     * that process's own file, which only that process appends to and trims. The app does the same with its file.
     * One writing process per file: the old shared file lost a copier line appended while the app rewrote it to trim
     * (review C145). [readForReport] merges the two by their timestamps.
     *
     * A failure to write is itself reported to logcat: the journal is the one place a failure must not vanish.
     */
    fun record(context: Context, rawLine: String, fromCopier: Boolean = false) = synchronized(lock) {
        // Redacted like every other line that can leave the phone: a recording's name carries a contact or a number.
        val line = (if (fromCopier) "[:drive] " else "") + AppLogger.redactForReport(rawLine)
        android.util.Log.i("CV:DriveCopy", line)
        runCatching {
            val file = File(context.filesDir, if (fromCopier) COPIER_FILE_NAME else FILE_NAME)
            file.appendText("${stamp.format(Date())} $line\n")
            if (file.length() > MAX_BYTES_PER_FILE) {
                val text = file.readText()
                file.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        }.onFailure { android.util.Log.w("CV:DriveCopy", "journal write failed: ${it.javaClass.simpleName}: ${it.message}") }
        Unit
    }

    fun readForReport(context: Context): String? = runCatching {
        fun read(name: String) = File(context.filesDir, name).takeIf { it.exists() && it.length() > 0 }?.readText()
        merge(read(FILE_NAME), read(COPIER_FILE_NAME))
    }.getOrNull()

    /**
     * The two files as one, ordered by the timestamp each line starts with (the sort is stable, so equal stamps keep the
     * app's line first). A line with no timestamp (the tail of a multi-line entry) stays with the line before it.
     */
    internal fun merge(app: String?, copier: String?): String? {
        if (app.isNullOrEmpty() && copier.isNullOrEmpty()) return null
        if (copier.isNullOrEmpty()) return app
        if (app.isNullOrEmpty()) return copier
        val entries = (groups(app) + groups(copier)).sortedBy { it.first }
        return entries.joinToString("") { it.second }
    }

    private fun groups(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, StringBuilder>>()
        for (raw in text.split('\n').dropLastWhile { it.isEmpty() }) {
            val isStamped = raw.length >= STAMP_LENGTH && raw[4] == '-' && raw[10] == ' ' && raw[19] == '.'
            if (isStamped || out.isEmpty()) out.add(raw.take(STAMP_LENGTH) to StringBuilder())
            out.last().second.append(raw).append('\n')
        }
        return out.map { it.first to it.second.toString() }
    }

    /** `files per minute`, one decimal, for the pace line. */
    fun pace(files: Int, elapsedMs: Long): String =
        if (elapsedMs <= 0L) "n/a" else "%.1f".format(Locale.US, files * 60_000.0 / elapsedMs)
}
