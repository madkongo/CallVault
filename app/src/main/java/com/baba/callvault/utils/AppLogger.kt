/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Build
import com.baba.callvault.BuildConfig
import com.baba.callvault.integrations.scrcpy.ScrcpyConfig
import java.io.OutputStreamWriter
import java.io.PrintWriter
import com.baba.callvault.data.AppPreferences

/**
 * A unified, thread-safe, and asynchronous logging utility with built-in log rotation and redaction capabilities.
 */
object AppLogger {

    private const val TAG = "AppLogger"

    /** Maximum number of lines the log file can hold before being trimmed. */
    private const val MAX_LOG_LINES = 1000

    /** Number of lines to retain when the log file is trimmed. */
    private const val LINES_TO_KEEP = 500

    /** Coroutine scope dedicated to background log persistence. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Unbounded channel acting as the internal queue for log strings. */
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)

    /** Mutex to safely synchronize file writes, trimming, and deletions. */
    private val fileMutex = Mutex()

    /** Ongoing buffered writer for appending to the log file. */
    private var logWriter: BufferedWriter? = null

    /** Tracks the current number of lines in the log file to trigger rotation. */
    private var lineCount = 0

    /** Reference to the app preferences to check logging enablement dynamically. */
    private var prefs: AppPreferences? = null

    /** Pointer to the internal application diagnostic log file. */
    private var logFile: File? = null

    /**
     * Redaction is always on. The shareable diagnostic log must never contain raw phone numbers.
     *
     * (Previously this was gated by a developer-only "Debug mode" toggle that disabled redaction;
     * that toggle has been removed, so redaction is now unconditional and cannot be turned off.)
     */
    private val isRedactionEnabled: Boolean
        get() = true

    /**
     * Initializes the logging mechanism for the main application process.
     * Sets up the primary log file, attaches an uncaught exception handler, and launches the persistent IO loop.
     *
     * @param context The application context.
     */
    fun init(context: Context) {
        prefs = AppPreferences(context)
        logFile = File(context.cacheDir, "app_debug.log")

        // Store the original default uncaught exception handler to ensure we can forward exceptions after flushing
        // logs
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(TAG, "Caught an uncaught exception, flushing logs to disk before process death...", throwable)
            flushSync()
            // Forward runtime exception to original uncaught handler
            defaultHandler?.uncaughtException(thread, throwable)
        }

        scope.launch {
            // On initialization, we need to determine how many lines are already in the log file (if it exists)
            fileMutex.withLock {
                lineCount = if (logFile?.exists() == true) logFile!!.readLines().size else 0
                logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
            }

            // Continuously consume log messages from the channel and write them to disk, while managing log rotation.
            for (line in channel) {
                fileMutex.withLock {
                    logWriter?.apply {
                        write(line)
                        newLine()
                        flush()
                        lineCount++
                    }

                    if (lineCount >= MAX_LOG_LINES) {
                        logWriter?.close()
                        logFile?.let { file ->
                            if (file.exists()) {
                                val lines = file.readLines()
                                val keptLines = lines.takeLast(LINES_TO_KEEP)
                                file.writeText(keptLines.joinToString("\n") + "\n")
                                lineCount = keptLines.size
                            }
                        }
                        logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
                    }
                }
            }
        }
    }

    /**
     * Safely deletes the existing internal log file and resets the writing stream
     * and line tracking metrics. Execution is managed sequentially via a Mutex lock.
     */
    fun clearLogs() {
        scope.launch {
            fileMutex.withLock {
                logWriter?.close()
                logFile?.delete()
                logWriter = logFile?.let { BufferedWriter(FileWriter(it, true)) }
                lineCount = 0
            }
        }
    }

    /**
     * Whether a valid (existing, non-empty) log file is currently on disk and therefore worth
     * sharing. Used by the Settings Debug section to decide whether to offer the Share action.
     */
    fun hasLogs(): Boolean {
        val file = logFile
        return file != null && file.exists() && file.length() > 0L
    }

    /**
     * Builds a self-contained diagnostic report (metadata header + the redacted log history) into a
     * file inside the app's cache directory, ready to be attached to a share-sheet via FileProvider.
     *
     * The report lives under `cacheDir/logs/` (the only folder exposed by the FileProvider) and is
     * overwritten on each call. Phone numbers remain redacted.
     *
     * Suspends and copies the log body under [fileMutex] so it never captures a half-flushed or
     * mid-rotation file (the logger's IO coroutine writes under the same lock).
     *
     * @param context Application context.
     * @return The report [File], or `null` if no log file exists yet (nothing to share).
     */
    suspend fun buildShareableReport(context: Context): File? {
        val source = logFile
        if (source == null || !source.exists() || source.length() == 0L) return null

        val shareDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val report = File(shareDir, "callvault_debug_report.txt")
        return try {
            // Write the header and the log body into ONE output stream. The PrintWriter is flushed
            // (not closed) before copying the body so its text lands ahead of the log bytes; the
            // `use` block closes the underlying stream once both have been written.
            report.outputStream().use { out ->
                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writeReportHeader(writer, context)
                writer.flush()
                // Snapshot the live log under the writer's lock so the copy is consistent.
                fileMutex.withLock {
                    if (source.exists()) source.inputStream().use { input -> input.copyTo(out) }
                }
                out.flush()
                if (writer.checkError()) {
                    e(TAG, "PrintWriter reported an error while building the shareable report")
                    return null
                }
            }
            report
        } catch (e: Exception) {
            e(TAG, "Failed to build shareable report", e)
            null
        }
    }

    /** Writes the common report metadata header (app/device/runtime info) to [writer]. */
    private fun writeReportHeader(writer: PrintWriter, context: Context) {
        writer.println("=== CallVault AppLogger Export ===")
        writer.println("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        writer.println("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        writer.println("Scrcpy Server: ${ScrcpyConfig.SCRCPY_VERSION}")
        writer.println("Manufacturer: ${Build.MANUFACTURER}")
        writer.println("Model: ${Build.MODEL}")
        writer.println("Device: ${Build.DEVICE}")
        writer.println("Product: ${Build.PRODUCT}")
        writer.println("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        writer.println("Device Country Iso Estimation: ${PhoneNumberManager.getInstance(context).getDeviceCountryIso()}")
        writer.println("===========================================")
        writer.println()
    }

    /** Logs a Verbose level message and optionally its throwable trace. */
    fun v(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.v(tag, finalMessage, t) else Log.v(tag, finalMessage)
        logInternal("V", tag, finalMessage, t)
    }

    /** Logs a Debug level message and optionally its throwable trace. */
    fun d(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.d(tag, finalMessage, t) else Log.d(tag, finalMessage)
        logInternal("D", tag, finalMessage, t)
    }

    /** Logs an Info level message and optionally its throwable trace. */
    fun i(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.i(tag, finalMessage, t) else Log.i(tag, finalMessage)
        logInternal("I", tag, finalMessage, t)
    }

    /** Logs a Warning level message and optionally its throwable trace. */
    fun w(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.w(tag, finalMessage, t) else Log.w(tag, finalMessage)
        logInternal("W", tag, finalMessage, t)
    }

    /** Logs an Error level message and optionally its throwable trace. */
    fun e(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.e(tag, finalMessage, t) else Log.e(tag, finalMessage)
        logInternal("E", tag, finalMessage, t)
    }

    /** Logs a "What a Terrible Failure" (assert) message and optionally its throwable trace. */
    fun wtf(tag: String, message: String, t: Throwable? = null) {
        val finalMessage = if (isRedactionEnabled) redact(message) else message
        if (t != null) Log.wtf(tag, finalMessage, t) else Log.wtf(tag, finalMessage)
        logInternal("WTF", tag, finalMessage, t)
    }

    /**
     * Prepares a log message by enriching it with more detailed metadata (timestamp, log level, tag) and then
     * forwarding it to the channel.
     *
     * **WARNING**: YOU MUST ENSURE THE MESSAGE IS [redact] BEFORE CALLING THIS METHOD TO TRY TO AVOID LEAKING SENSITIVE DATA INTO THE LOG FILE.
     */
    private fun logInternal(level: String, tag: String, message: String, t: Throwable?) {
        if (prefs?.isLoggingEnabled() != true) return

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val fullMessage = message + (t?.let { "\n${Log.getStackTraceString(it)}" } ?: "")

        val formattedLine = "$time [$level] $tag: $fullMessage"
        channel.trySend(formattedLine)
    }

    /**
     * Redacts highly sensitive personal information (e.g. phone numbers) from the given text
     * before it gets committed to physical storage.
     */
    private fun redact(msg: String): String {
        val phoneRedactionRegex = Regex(
            "(?<!\\d)" +              // Negative Lookbehind: Don't start in the middle of another number
                    "(?:\\+?\\d{1,3}[-.\\s]?)?" + // Optional Country Code (e.g., +1 or 33)
                    "(?:\\(\\d{1,4}\\)|\\d{1,4})" + // Area code (with or without parentheses)
                    "[-.\\s]?\\d{3,4}" +      // Prefix
                    "[-.\\s]?\\d{3,4}" +      // Line number
                    "(?!\\d)"                 // Negative Lookahead: Don't end in the middle of another number
        )
        return msg.replace(phoneRedactionRegex, "[PHONE_REDACTED]")
    }

    /**
     * Synchronously drains the logging channel and forcefully writes all pending messages to disk.
     * This ensures that crucial crash traces and late logs are not lost if the process is
     * abruptly killed before the asynchronous IO worker can process them.
     */
    private fun flushSync() {
        val file = logFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                var message = channel.tryReceive().getOrNull()
                while (message != null) {
                    writer.write(message)
                    writer.append('\n')
                    message = channel.tryReceive().getOrNull()
                }
                writer.flush()
            }
        } catch (_: Exception) {
            // We're already crashing, ignore I/O errors here so we don't block the actual crash from propagating.
        }
    }
}
