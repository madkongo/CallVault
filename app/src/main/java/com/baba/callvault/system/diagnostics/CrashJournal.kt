/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import android.app.Application
import android.content.Context
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale

/**
 * An always-on note of every uncaught exception in the app's own processes, written whether or not debug logging
 * is on, and printed in every report under "Crashes (always on)".
 *
 * Why: Android's exit record says only "crash". With logging off — every report from someone who was never told to
 * switch it on — the exception itself was nowhere, and a crash Android raises on our main thread (the
 * `RemoteServiceException` family: "Bad notification for startForeground", "Context.startForegroundService() did not
 * then call Service.startForeground()") is exactly the kind that has no line of ours in front of it.
 *
 * Covers the main process and the Drive copier (`:drive`): both are the app's uid and can write [FILE_NAME]. The
 * recorder host cannot — it runs as the shell user and has no access to the app's files — so its crashes are left to
 * the system report, which keeps every line the recorder's pid wrote (its `AndroidRuntime` trace included).
 *
 * Bounded twice: one entry is at most [MAX_ENTRY_CHARS] (top [MAX_FRAMES] frames, short cause chain), and the file at
 * most [MAX_FILE_BYTES], trimmed oldest entry first. Each entry's text goes through the report's redaction.
 */
object CrashJournal {

    const val FILE_NAME = "crash_journal.txt"
    const val MAX_FRAMES = 15
    const val MAX_ENTRY_CHARS = 4_000
    const val MAX_FILE_BYTES = 32 * 1024

    private const val MAX_MESSAGE = 500
    private const val MAX_CAUSES = 4
    private const val CAUSE_FRAMES = 5
    private const val ENTRY_SEPARATOR = "\n\n"
    private const val HEADER_MARK = " crash pid="

    private val lock = Any()

    /**
     * Writes a note for [error] on [thread] of this process. Synchronous — the process is about to die — and never
     * throws: a failure here must not stand between the crash and the platform's own handler.
     */
    fun record(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val entry = safeEntry(
                atMillis = System.currentTimeMillis(),
                pid = android.os.Process.myPid(),
                process = runCatching { Application.getProcessName() }.getOrNull() ?: "?",
                thread = thread.name,
                error = error,
                redact = AppLogger::redactForReport,
            )
            append(File(context.filesDir, FILE_NAME), entry)
        }
    }

    /**
     * [format], or — when formatting or redaction itself throws (a dying process can be out of memory, or a
     * redactor can fail) — a minimal entry with the header and only the exception's class, which carries no
     * personal data and is still what a report most needs. Catches [Throwable] on purpose: nothing may stop the note.
     */
    fun safeEntry(
        atMillis: Long, pid: Int, process: String, thread: String, error: Throwable, redact: (String) -> String,
    ): String = try {
        format(atMillis, pid, process, thread, error, redact)
    } catch (failure: Throwable) {
        "${stamp(atMillis)}$HEADER_MARK$pid process=$process thread=$thread\n" +
            "${error.javaClass.name} (note cut short: ${failure.javaClass.simpleName})"
    }

    private fun stamp(atMillis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(atMillis))

    /** The whole journal for a report, or null when nothing has crashed. */
    fun readForReport(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    /** Deletes the journal, for the user deleting their logs. */
    fun clear(context: Context) {
        synchronized(lock) { runCatching { File(context.filesDir, FILE_NAME).delete() } }
    }

    /**
     * One entry: a header with time, pid, process and thread, then the exception, its top frames and its causes.
     * Only the body is passed through [redact]; the header is ours and its timestamp must stay readable.
     */
    fun format(atMillis: Long, pid: Int, process: String, thread: String, error: Throwable, redact: (String) -> String): String {
        val header = "${stamp(atMillis)}$HEADER_MARK$pid process=$process thread=$thread"
        val body = StringBuilder()
        appendThrowable(body, error, MAX_FRAMES, prefix = "")
        val seen = IdentityHashMap<Throwable, Unit>().apply { put(error, Unit) }
        var cause = error.cause
        var causes = 0
        while (cause != null && !seen.containsKey(cause) && causes < MAX_CAUSES) {
            seen[cause] = Unit
            appendThrowable(body, cause, CAUSE_FRAMES, prefix = "Caused by: ")
            cause = cause.cause
            causes++
        }
        return (header + "\n" + redact(body.toString().trimEnd())).take(MAX_ENTRY_CHARS)
    }

    private fun appendThrowable(out: StringBuilder, t: Throwable, frames: Int, prefix: String) {
        val message = t.message?.take(MAX_MESSAGE)?.replace('\n', ' ')
        out.append(prefix).append(t.javaClass.name).append(message?.let { ": $it" } ?: "").append('\n')
        t.stackTrace.take(frames).forEach { out.append("  at ").append(it).append('\n') }
        val rest = t.stackTrace.size - frames
        if (rest > 0) out.append("  ... ").append(rest).append(" more\n")
    }

    /** Appends [entry] to [file], then trims whole entries from the front until it is back under [MAX_FILE_BYTES]. */
    fun append(file: File, entry: String) = synchronized(lock) {
        file.appendText(entry.trimEnd() + ENTRY_SEPARATOR)
        if (file.length() <= MAX_FILE_BYTES) return@synchronized
        val entries = file.readText().split(ENTRY_SEPARATOR).filter { it.isNotBlank() }
        var size = entries.sumOf { it.toByteArray().size + ENTRY_SEPARATOR.length }
        var from = 0
        // Down to three quarters, so the next crash does not trim again straight away.
        while (from < entries.size - 1 && size > MAX_FILE_BYTES * 3 / 4) {
            size -= entries[from].toByteArray().size + ENTRY_SEPARATOR.length
            from++
        }
        // Written beside the journal and renamed over it, so a process killed mid-trim leaves the old journal whole
        // rather than a half-written one.
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        temp.writeText(entries.drop(from).joinToString(ENTRY_SEPARATOR) + ENTRY_SEPARATOR)
        if (!temp.renameTo(file)) {
            temp.delete()
            error("could not replace ${file.name}")
        }
    }

    private const val TEMP_SUFFIX = ".tmp"

    /** The exception line of the newest entry written by [pid], or null when there is none. */
    fun summaryForPid(journal: String, pid: Int): String? =
        journal.split(ENTRY_SEPARATOR)
            .lastOrNull { entry -> entry.lineSequence().firstOrNull()?.contains("$HEADER_MARK$pid ") == true }
            ?.lines()?.getOrNull(1)
}
