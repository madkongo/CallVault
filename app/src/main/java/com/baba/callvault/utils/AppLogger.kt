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
import kotlinx.coroutines.withContext
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
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuBackend

/**
 * A unified, thread-safe, and asynchronous logging utility with built-in log rotation and redaction capabilities.
 */
object AppLogger {

    private const val TAG = "AppLogger"

    /**
     * Maximum number of lines the log file can hold before being trimmed.
     *
     * **Sized in days of history, not in bytes.** The old cap of 1,000 lines was chosen as a size limit
     * and was far more conservative than it needed to be: measured from a real user's report on
     * 2026-08-25 — 262 lines over 117 minutes including four calls, averaging **117 bytes per line** —
     * 1,000 lines was only ~114 KB but just **7 hours** of history, and after each trim as little as
     * 3.7 hours. A user reported a stuck microphone that could not be diagnosed partly for that reason:
     * by the time anyone looked, the evidence had been trimmed away.
     *
     * At the same measured rate of ~2.2 lines per minute, 10,000 lines is a little over **three days**
     * and about **1.1 MB** — a rounding error on any phone that records calls, and the difference
     * between "not reproducible, so undiagnosable" and "wait for it to happen again and read the log".
     *
     * The rate is dominated by call activity rather than idle chatter, so a heavy user gets fewer days
     * from the same number of lines. Three days is the target for ordinary use, not a guarantee.
     */
    /** Names the always-on part in the in-app viewer, so the two are never confused for each other. */
    private const val JOURNAL_VIEW_HEADING = "--- Setup journal (always on) ---"

    private const val MAX_LOG_LINES = 10_000

    /**
     * Number of lines to retain when the log file is trimmed.
     *
     * Three quarters rather than the old half, so the *floor* stays useful: trimming to half meant the
     * retained history swung between 3.7 and 7.4 hours, and a report captured just after a trim had
     * barely half a day in it. At 75% the window is ~2.4 to ~3.2 days, and the cost is a trim every
     * ~2,500 lines (about 19 hours) instead of every 500.
     */
    private const val LINES_TO_KEEP = 7_500

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

    // ---------------------------------------------------------------------------------------------
    // The recorder daemon's own log
    //
    // The daemon is a SEPARATE PROCESS running as shell. It cannot read the app's preferences and it
    // cannot write to the app's private files directory, so [init] never runs there: `prefs` and
    // `logFile` are both null and every line it logs goes to logcat and nowhere else. That is why a
    // user's debug export contains no CV:RecorderServer, CV:HandoffSource or CV:DirectCapture lines at
    // all — and why a stuck-microphone report on 2026-08-25 could not be diagnosed, because whether the
    // daemon released its AudioRecord is exactly what the export cannot show.
    //
    // So the daemon keeps its recent lines in memory and the app pulls them over the binder at export
    // time. Off by default and switched on explicitly by the app, because the daemon has no way to ask
    // whether the user wants logging at all.
    // ---------------------------------------------------------------------------------------------

    /** How many lines the in-memory ring holds. Sized to cover a long call and its teardown. */
    private const val RING_CAPACITY = 4_000

    /** Set by the app over the binder. Always false in the app process, which uses the file instead. */
    @Volatile
    private var ringEnabled = false

    private val ring = ArrayDeque<String>()

    /**
     * Turns the in-memory ring on or off. Called in the **daemon** process over the binder, mirroring
     * the user's logging preference.
     *
     * **Switching it off stops collecting but keeps what was collected**, which looks like a leak and is
     * the opposite. The flow the Settings screen actually tells people to follow is "enable this,
     * reproduce the issue, then turn it off and share the logs" — so clearing on disable would throw the
     * daemon's side away every single time, in exactly the case it exists for. The lines leave on the
     * next [drainRing], or with [clearRing] when the user deletes the log.
     */
    fun setRingEnabled(enabled: Boolean) {
        synchronized(ring) { ringEnabled = enabled }
    }

    /** Drops everything collected. For the user deleting their log, where "gone" must mean gone. */
    fun clearRing() {
        synchronized(ring) { ring.clear() }
    }

    /** Whether the ring is currently collecting. */
    fun isRingEnabled(): Boolean = ringEnabled

    /**
     * Takes everything collected so far and empties the ring.
     *
     * Draining rather than copying, so a second export does not repeat what the first already carried,
     * and the daemon does not hold the lines any longer than it has to.
     */
    fun drainRing(): List<String> = synchronized(ring) {
        val out = ring.toList()
        ring.clear()
        out
    }

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
        // Always on, unlike the file above: the setup that fails on a stranger's phone happens before
        // anyone has been told to switch logging on. See [SetupJournal].
        SetupJournal.init(context)
        // Read (and on a fresh install, generate) the pseudonym salt here rather than on whichever
        // thread happens to log first, so the hot path only ever sees the cached value.
        pseudonymSalt()

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
        // "Gone" has to mean gone for the always-on journal as well, not only for the file the user
        // switched on themselves.
        SetupJournal.clear()
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
     * One of the two redacted settings, read by the recorder daemon rather than by this process.
     *
     * The daemon is uid 2000 and both of Android 17's redaction sites exempt uid < 10000, so this is the
     * only way an app can see the true value of `adb_enabled` / `development_settings_enabled` on a
     * redacting build — the second site runs inside the calling app's own process, so no amount of
     * reflection or provider work here can recover it.
     *
     * 🧪 UNVERIFIED on an Android 17 device. It is in the report precisely so the next report from one
     * settles it. Never throws and never blocks the report.
     */
    private fun settingAsShell(key: String): String = runCatching {
        val service = com.baba.callvault.server.RecorderConnection.service
            ?: return "(app cannot ask — no daemon connected)"
        service.diagnosticDump(key, null)?.trim()?.ifBlank { "(empty)" } ?: "(refused)"
    }.getOrElse { "(failed: ${it.message})" }

    /** Whether the always-on setup journal has anything in it. */
    fun hasSetupJournal(): Boolean = setupJournalSizeBytes() > 0L

    /** The setup journal's size in bytes, or 0 when there is none. */
    fun setupJournalSizeBytes(): Long = SetupJournal.sizeBytes()

    /**
     * Whether there is anything at all worth sharing — the opt-in log, the setup journal, or both.
     *
     * What the Debug section gates Share on. A phone that failed during setup has no opt-in log by
     * definition, and gating on that alone hid the one file that could explain it.
     */
    fun hasAnyDiagnostics(): Boolean = hasLogs() || hasSetupJournal()

    /** The log's size on disk in bytes, or 0 when there is no log. */
    fun logSizeBytes(): Long {
        val file = logFile ?: return 0L
        return runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
    }

    /**
     * The last [maxLines] lines of the log, for reading inside the app.
     *
     * Tail rather than head: a log is read to find out what just happened. Bounded because the file
     * grows to megabytes and handing all of it to a Compose `Text` would stall the frame that drew
     * it — the size is shown separately, so a truncated view is not a hidden one.
     *
     * Runs on IO and returns null when there is nothing to show, so the caller can distinguish "no
     * log" from "an empty read".
     */
    suspend fun readTail(maxLines: Int): String? = withContext(Dispatchers.IO) {
        // The setup journal is shown first and always. It is written without anyone switching it on,
        // so its owner has to be able to read it without switching anything on either.
        val journal = SetupJournal.readForReport()?.trimEnd()?.let { "$JOURNAL_VIEW_HEADING\n$it\n" }
        val file = logFile ?: return@withContext journal
        runCatching {
            // No opt-in log is the ordinary case on a phone that failed during setup — show the
            // journal on its own rather than an empty screen.
            if (!file.exists() || file.length() == 0L) return@withContext journal
            // Flush first: without it the newest lines sit in the writer's buffer and the view shows
            // a log that is stale exactly where the user is looking.
            flushSync()
            val lines = file.readLines()
            val tail = if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
            journal.orEmpty() + tail.joinToString("\n")
        }.getOrElse {
            Log.w(TAG, "Could not read the log for viewing: ${it.message}")
            null
        }
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
        // No precondition at all. The report is worth sharing even with both logs empty, because the
        // configuration header alone answers the first questions of every bug report — mode, transport,
        // which switches are on, whether the recorder is connected — and that used to be unreachable
        // unless the user had thought to switch logging on beforehand. Requiring a log meant the one
        // person who most needed to send something was the one person who could not.

        val shareDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val report = File(shareDir, "callvault_debug_report.txt")
        return try {
            // Write the header and the log body into ONE output stream. The PrintWriter is flushed
            // (not closed) before copying the body so its text lands ahead of the log bytes; the
            // `use` block closes the underlying stream once both have been written.
            // Pull the daemon's lines BEFORE taking the file lock: it is a blocking binder call into
            // another process, and holding the logger's lock across it would stall every thread trying
            // to log while we wait on a process that may be wedged.
            val daemonEntries = drainDaemonDiagnostics()

            report.outputStream().use { out ->
                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                writeReportHeader(writer, context)
                if (daemonEntries.isEmpty()) {
                    writer.println("Recorder host lines: none (see the note at the end of this file)")
                } else {
                    writer.println("Recorder host lines: ${daemonEntries.size}, merged in below by timestamp")
                }
                writer.println("===========================================")
                writer.println()

                // The setup journal goes FIRST and unconditionally. It is the only part of this report
                // that exists on a phone where logging was never switched on — which is every phone
                // that failed during setup, the case the rest of the report cannot cover.
                SetupJournal.readForReport()?.let { journal ->
                    writer.println("--- Setup journal (always on; the first run, kept as it happened) ---")
                    writer.println(journal.trimEnd())
                    writer.println("===========================================")
                    writer.println()
                }

                // Snapshot the live log under the writer's lock so the copy is consistent.
                val appEntries = fileMutex.withLock {
                    if (source?.exists() == true) entriesOf(source.readLines()) else emptyList()
                }

                // Interleaved rather than appended in a block. The whole point is to read one sequence
                // across two processes — "the app asked the daemon to stop" and "the daemon released
                // its AudioRecord" are three lines apart in time and were previously in different files,
                // one of which did not exist.
                (appEntries + daemonEntries)
                    .sortedBy { it.take(TIMESTAMP_LENGTH) }
                    .forEach { writer.println(it) }

                if (daemonEntries.isEmpty()) {
                    writer.println()
                    writer.println(
                        "NOTE: no lines from the recorder host. Either it was not running, or it " +
                            "predates this app version, or diagnostics were switched on after it started."
                    )
                }
                writer.flush()
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


    /** Length of the "yyyy-MM-dd HH:mm:ss.SSS" prefix every entry starts with, used to sort by time. */
    private const val TIMESTAMP_LENGTH = 23

    private val ENTRY_START = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} """)

    /**
     * Groups raw lines into log *entries*, so a stack trace stays with the line that produced it.
     *
     * Sorting raw lines would scatter the continuation lines of a multi-line entry all over the report,
     * turning the most useful thing in it — a stack trace — into confetti.
     */
    internal fun entriesOf(lines: List<String>): List<String> {
        val entries = mutableListOf<StringBuilder>()
        for (line in lines) {
            if (entries.isEmpty() || ENTRY_START.containsMatchIn(line)) entries.add(StringBuilder(line))
            else entries.last().append('\n').append(line)
        }
        return entries.map { it.toString() }
    }

    /**
     * Asks the recorder host for its collected lines, or an empty list when there is nobody to ask.
     *
     * Never throws: an export must still be produced when the daemon is dead, wedged, or older than
     * this app version and therefore missing the method entirely.
     */
    private fun drainDaemonDiagnostics(): List<String> = runCatching {
        // Redacted here, not at the far end. Our own lines are redacted on the way to disk, and the
        // system lines are redacted by SystemLogCollector -- but these arrive raw over the binder from
        // a process that does its own logging, and they land in the same report that gets attached to
        // a public issue. One rule for all three sources, applied where they enter the report.
        RecorderConnection.service?.drainDiagnostics()?.map { redactForReport(it) }.orEmpty()
    }.onFailure {
        w(TAG, "Could not read the recorder host's diagnostics: ${it.message}")
    }.getOrDefault(emptyList())

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
        // The ROM build, not just the Android version. OEM audio behaviour differs between builds of
        // the same Android release, and without this every OEM question starts by inferring the ROM
        // from the model number — which is guesswork. DISPLAY is the user-visible build id
        // (e.g. "CPH2581_16.0.9.400(EX01)"); INCREMENTAL pins the exact build within it.
        writer.println("ROM Build: ${Build.DISPLAY}")
        writer.println("ROM Incremental: ${Build.VERSION.INCREMENTAL}")
        writer.println("ROM Fingerprint: ${Build.FINGERPRINT}")
        writer.println("Device Country Iso Estimation: ${PhoneNumberManager.getInstance(context).getDeviceCountryIso()}")
        writeConfiguration(writer, context)
    }

    /**
     * The settings and transport state at the moment of export.
     *
     * **Every question a bug report starts with is answered here.** Reading a log without knowing
     * whether resilient recording was on is guesswork: the same call takes a completely different
     * capture path depending on it, and the first three exchanges of every report were spent asking.
     * Only the settings that change *behaviour* are listed — codec, bit rate, theme and the rest change
     * the file or the screen, never the path the audio takes, and padding the header with them makes
     * the ones that matter harder to see.
     *
     * The two privileged modes fail in entirely different ways, so each gets its own block rather than
     * a shared one full of "n/a": in standalone the interesting facts are the ADB transport and
     * wireless debugging, and under Shizuku neither of those exists and what matters is whether
     * Shizuku is installed, running and permitted.
     */
    private fun writeConfiguration(writer: PrintWriter, context: Context) {
        val p = runCatching { AppPreferences(context) }.getOrNull()
        if (p == null) {
            writer.println("Configuration: unavailable")
            writer.println("===========================================")
            writer.println()
            return
        }
        val mode = runCatching { p.getPrivilegedMode() }.getOrNull()

        writer.println("--- Configuration ---")
        writer.println("Privileged mode: ${mode?.name ?: "?"}")
        // Printed in BOTH modes on purpose: the report that needs it most comes from someone stuck in
        // standalone *because* we could not find their Shizuku, and the Shizuku block below is skipped
        // for them. "none found" next to a Shizuku the user swears is installed is the whole diagnosis.
        writer.println("Shizuku manager: ${runCatching { ShizukuBackend.managerPackage(context) }.getOrNull() ?: "none found"}")
        writer.println("Record phone calls: ${p.yesNo { isCarrierRecordingEnabled() }}")
        writer.println("Auto-record incoming: ${p.yesNo { isAutoRecordIncomingEnabled() }}")
        writer.println("Auto-record outgoing: ${p.yesNo { isAutoRecordOutgoingEnabled() }}")
        writer.println("Resilient recording (handoff): ${p.yesNo { isHandoffPersistEnabled() }}")
        writer.println("VoIP recording: ${p.yesNo { isVoipRecordingEnabled() }}")
        writer.println("VoIP auto-start: ${p.yesNo { isVoipAutoStartEnabled() }}")
        writer.println("Offline recording (loopback): ${p.yesNo { isOfflineRecordingEnabled() }}")
        writer.println("Audio source: ${runCatching { p.getAudioSource() }.getOrDefault("?")}")
        writer.println("Ignore anonymous incoming: ${p.yesNo { isIgnoreAnonymousIncomingEnabled() }}")
        writer.println("Ignore cross-country in/out: " +
            "${p.yesNo { isIgnoreCrossCountryIncomingEnabled() }}/${p.yesNo { isIgnoreCrossCountryOutgoingEnabled() }}")
        writer.println("Storage target: ${runCatching { p.getStorageTarget().name }.getOrDefault("?")}")

        writer.println("--- Recorder ---")
        writer.println("Binder connected: ${runCatching { RecorderConnection.isConnected }.getOrDefault(false)}")
        writer.println("Host uid: ${runCatching { RecorderConnection.service?.hostUid()?.toString() }.getOrNull() ?: "?"}")

        if (mode?.needsShizuku == true) {
            writer.println("--- Shizuku ---")
            writer.println("Status: ${runCatching { RecorderBackend.shizukuStatus(context).name }.getOrDefault("?")}")
            writer.println("Capture path: scrcpy (a Shizuku-hosted process cannot start an AudioRecord)")
            writer.println("Not available in this mode: resilient recording, VoIP, offline recording, speaker attribution")
        } else {
            writer.println("--- Standalone transport ---")
            writer.println("Wireless debugging: ${runCatching { AdbShell.isWirelessDebuggingEnabled(context) }.getOrDefault("?")}")
            // ON/OFF/UNKNOWN, not a boolean, and the evidence beside it. A build that redacts
            // ADB_ENABLED (Android 17) reports "false" to every app whatever the truth, so a bare boolean
            // here is triage poison -- every report from such a phone would say USB debugging was off.
            // `adbd` is what the state is actually corroborated against, so it is printed too.
            writer.println("USB debugging: ${runCatching { AdbShell.usbDebuggingState(context).name }.getOrDefault("?")}")
            writer.println("init.svc.adbd: ${runCatching { AdbShell.adbdState().name }.getOrDefault("?")}")
            // What the DAEMON sees. It is uid 2000, which the platform's redaction exempts, so on a phone
            // that lies to the app process these two lines carry the truth and the ones above do not.
            // "(app cannot ask)" means no daemon was connected, not that the read failed.
            writer.println("adb_enabled as shell: ${settingAsShell("setting_adb_enabled")}")
            writer.println("development_settings_enabled as shell: ${settingAsShell("setting_dev_options")}")
            writer.println("WRITE_SECURE_SETTINGS: ${runCatching { AdbShell.hasWriteSecureSettings(context) }.getOrDefault("?")}")
            writer.println("WD plan: ${runCatching { AdbShell.wirelessDebuggingPlan(context).name }.getOrDefault("?")}")
            // Whether this phone's OEM lets the shell grant at all. Unlike codec or bit rate, it CHANGES
            // WHAT IS POSSIBLE: on a blocked phone setup cannot complete, and nothing else in a report says
            // so. See docs/dev-notes/2026-09-16-oem-adb-restrictions-and-onboarding-check.md.
            writer.println("Shell grants (OEM gate): ${runCatching { AdbShell.shellGrantState(context).name }.getOrDefault("?")} (${runCatching { AdbShell.oemGate(context).name }.getOrDefault("?")})")
        }
        writer.println("===========================================")
        writer.println()
    }

    /** Reads one boolean setting for the header, never letting a failure abort the whole report. */
    private inline fun AppPreferences.yesNo(read: AppPreferences.() -> Boolean): String =
        runCatching { if (read()) "on" else "off" }.getOrDefault("?")

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
        // Two consumers with different gates. The file is the app's and follows the user's preference.
        // The ring is the daemon's, where there is no preference to read — `prefs` is null there, so
        // without this every daemon line would be dropped right here and the export would stay blind
        // to the process that actually owns the microphone.
        val toFile = prefs?.isLoggingEnabled() == true
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        // The setup journal is written whether or not logging is on, so it is fed before the gate
        // below — which is the whole point of it. It takes only the tags SetupJournalPolicy names,
        // and the message reaching here is already redacted.
        SetupJournal.record(tag, "$time [$level] $tag: $message")

        if (!toFile && !ringEnabled) return

        // The trace is redacted HERE, not by the callers. They redact only their own message and hand
        // the raw throwable down, so until this line an exception message quoting a recording URI or a
        // number — a SAF FileNotFoundException, a DocumentsContract IllegalArgumentException, a
        // MediaMetadataRetriever failure — wrote it into the report untouched. Redacting the trace once
        // it is a string covers every caller, present and future, and costs nothing on the ordinary
        // path because there is no throwable to stringify there.
        val trace = t?.let { "\n" + redact(Log.getStackTraceString(it)) }.orEmpty()
        val fullMessage = message + trace

        val formattedLine = "$time [$level] $tag: $fullMessage"
        if (toFile) channel.trySend(formattedLine)
        if (ringEnabled) {
            synchronized(ring) {
                while (ring.size >= RING_CAPACITY) ring.removeFirst()
                ring.addLast(formattedLine)
            }
        }
    }

    /**
     * Applies the same redaction to a line that did not come from us.
     *
     * System log lines carry numbers too — the telephony stack logs them — and a report built from
     * logcat goes into the same public issue as our own log. One rule for both.
     */
    fun redactForReport(line: String): String = redact(line)

    /**
     * Strips personal data from a line before it is written anywhere. See [LogRedaction] for the rules.
     *
     * The rules themselves live in [LogRedaction] so they can be unit tested without Android; all this
     * has to do is find the salt, which is the one part that needs a process and a preference store.
     */
    private fun redact(msg: String): String = LogRedaction.redact(msg, pseudonymSalt())

    /**
     * The stored per-install salt, cached after the first successful read.
     *
     * Cached **only when it came from preferences**. Lines are logged before [init] runs, and in the
     * daemon process [init] never runs at all, so a naive cache would pin the fallback salt for the
     * lifetime of the app process and give one contact two different tokens in the same report.
     */
    private fun pseudonymSalt(): String {
        cachedPseudonymSalt?.let { return it }
        val stored = prefs?.let { runCatching { it.getLogPseudonymSalt() }.getOrNull() }
        if (stored != null) cachedPseudonymSalt = stored
        return stored ?: processPseudonymSalt
    }

    @Volatile
    private var cachedPseudonymSalt: String? = null

    /**
     * The salt used where preferences are unreachable: the **daemon process**, which runs as shell and
     * cannot read the app's private preferences, and the app's own first few lines before [init].
     *
     * Random per process, so it still never leaks a name — it only costs correlation *between* the two
     * processes' lines. That is acceptable because the daemon deliberately logs no names at all (see
     * `VoipCallerName`, which resolves a caller and logs only that it succeeded). If the daemon ever
     * starts logging one, push the real salt over the binder alongside `setDiagnosticsEnabled` rather
     * than leaving it on this fallback.
     */
    private val processPseudonymSalt: String by lazy {
        ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
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
