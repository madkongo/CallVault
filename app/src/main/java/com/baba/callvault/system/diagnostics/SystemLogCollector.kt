/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Collects the daemon's and the system's log lines for a bug report, over the privileged shell.
 *
 * **Why any of this is needed.** The recorder daemon is a separate shell-uid process; it cannot write
 * the app's log file, and its stdout is deliberately discarded (keeping that stream open let `adbd`
 * kill the child before it finished starting). Everything it says goes to logcat and nowhere else, so
 * a debug report built only from `app_debug.log` shows one side of the conversation. Issue #18 spent a
 * week there: the exported log was clean end to end because the failure lived in the half nobody
 * could see.
 *
 * The system's own lines are the larger half of the value. Our daemon could at best have said "I
 * captured N bytes"; `AudioFlinger` is where "your capture was silenced" is actually recorded.
 *
 * Everything here is best-effort. No shell, no permission, or an unreadable response means the report
 * is built without this half rather than not built at all.
 */
object SystemLogCollector {

    /** See the call site: keeps uid/package/op headers and running markers, drops the bulk. */
    private const val MIC_OPS_COMMAND =
        "dumpsys appops | grep -E '^[[:space:]]*(Uid [0-9]+:|Package |[A-Z_]+ \\(|Running start at:)'"

    private const val TAG = "CV:SystemLog"

    /** Filename of the collected slice, alongside the app's own report in the same folder. */
    private const val REPORT_NAME = "callvault_system_report.txt"

    /** Line and byte ceilings, so the attachment stays something a mail client will send. */
    private const val MAX_LINES = 4_000
    private const val MAX_BYTES = 1_024 * 1_024

    /**
     * Grows the logcat ring, remembering what it was.
     *
     * Called when debug logging is switched on. Idempotent: the previous size is only recorded when
     * nothing is stored yet, so enabling twice cannot overwrite the real value with the grown one and
     * leave the ring permanently large.
     */
    suspend fun onLoggingEnabled(context: Context) = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        if (prefs.getLogcatRingPreviousKib() == null) {
            val current = LogcatRing.parseMainSizeKib(dump(context, "logcat_size", "logcat -g"))
            if (current == null) {
                // Unreadable. Grow anyway — a bigger ring is the point — but record nothing, so the
                // restore path leaves the buffer alone rather than shrinking it to a guess.
                AppLogger.w(TAG, "Could not read the logcat ring size; growing without a restore point")
            } else {
                prefs.setLogcatRingPreviousKib(current)
                AppLogger.i(TAG, "Logcat ring was ${current} KiB; growing to ${LogcatRing.TARGET_SIZE}")
            }
        }
        dump(context, "logcat_grow", LogcatRing.growCommand(), expectOutput = false)
        Unit
    }

    /** Puts the ring back. Does nothing when the original size was never successfully read. */
    suspend fun onLoggingDisabled(context: Context) = withContext(Dispatchers.IO) {
        val prefs = AppPreferences(context)
        val previous = prefs.getLogcatRingPreviousKib()
        if (previous == null) {
            AppLogger.i(TAG, "No recorded logcat ring size; leaving the buffer as it is")
            return@withContext
        }
        dump(context, "logcat_restore", LogcatRing.restoreCommand(previous), arg = "${previous}K", expectOutput = false)
        prefs.setLogcatRingPreviousKib(null)
        AppLogger.i(TAG, "Logcat ring restored to ${previous} KiB")
    }

    /**
     * Builds the filtered, redacted slice, or null when there is nothing to attach.
     *
     * Written into the same `cacheDir/logs/` the FileProvider already exposes — the only folder the
     * share sheet can read from.
     */
    suspend fun buildReport(context: Context): File? = withContext(Dispatchers.IO) {
        val raw = dump(context, "logcat_dump", "logcat -b main -b system -d -v threadtime")
        if (raw.isNullOrBlank()) {
            AppLogger.w(TAG, "No logcat output; the system report will not be attached")
            return@withContext null
        }

        val kept = raw.lineSequence().filter(SystemLogFilter::keep).toList()
        if (kept.isEmpty()) {
            AppLogger.w(TAG, "Logcat held nothing on the whitelist; the system report will not be attached")
            return@withContext null
        }
        val capped = SystemLogFilter.capToNewest(kept, MAX_LINES, MAX_BYTES)

        // Whether this phone's log is current at all. On the Honor BVL-N59 (2026-11-04) the dump held 121k lines and
        // every kept line was from the last boot, three days earlier — a log that stopped, or one that only keeps
        // what is not ours? This says which, without anyone reading 121k lines.
        val health = LogcatHealth.render(raw, dump(context, "logcat_size", "logcat -g"))
        // Every debugging/USB line in the whole log, not only the window around an event: what switched USB
        // debugging off is the open question on Honor. Keyword lines only, as in UsbOffLogWindow.
        val usbLines = LogcatHealth.debuggingLines(raw)

        // Android's own record of who is holding a microphone. Collected separately from logcat
        // because it answers a question logcat cannot: a capture leaked by a process that has since
        // died, or by a leftover scrcpy server, leaves no log line anywhere — but it is still listed
        // here, and it is still lighting the green dot on the user's screen.
        val micActivity = RecordActivityReport.render(dump(context, "dumpsys_audio", "dumpsys audio"))

        // The app-op that actually drives the green dot. Collected because the section above CANNOT
        // answer the question it looks like it answers: its ring log cannot pair system-only sources
        // at all, and it evicts old events once full. `dumpsys appops` marks a started-and-unfinished
        // mic op with `Running start at:`, which is the only direct evidence of a stuck indicator —
        // and the user cannot be asked to run it by hand.
        // Filtered on the device, not here: the full dump is ~2.3 MB on a well-used phone (measured
        // on an OP12), and pulling that through the ADB stream into a String in a 256 MB heap is
        // waste for the handful of lines that matter. The filter keeps uid, package, EVERY op header
        // and the running markers — 2.3 MB drops to ~156 KB.
        //
        // Every op header is kept deliberately. Narrowing to just the mic ops would let a
        // `Running start at:` belonging to some later non-mic op (CAMERA, say) follow a mic op header
        // directly, and the parser would credit the microphone to it. Falls back to the raw dump if
        // the device's grep does not take the expression, so a picky ROM loses speed, not the section.
        val micOps = MicOpReport.render(
            dump(context, "appops_mic", MIC_OPS_COMMAND) ?: dump(context, "appops_all", "dumpsys appops")
        )

        // How many privileged recorders are alive. Paired with the section above on purpose: an open
        // capture plus more than one recorder process names an orphan that outlived its replacement,
        // which is the one arrangement neither the app's log nor the live daemon's ledger can show.
        val recorders = RecorderProcessReport.render(dump(context, "processes", "ps -A -o USER,PID,ARGS"))

        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, REPORT_NAME)
        runCatching {
            out.writeText(buildString {
                appendLine(header(rawLines = raw.count { it == '\n' }, kept = kept.size, attached = capped.size))
                appendLine(micActivity)
                appendLine(micOps)
                appendLine(recorders)
                appendLine(health)
                appendLine("--- Debugging and USB lines in the whole log (newest ${LogcatHealth.MAX_DEBUG_LINES}) ---")
                if (usbLines.isEmpty()) appendLine("None.") else usbLines.forEach { appendLine(AppLogger.redactForReport(it)) }
                appendLine("-----------------------------------------------------------------------------------------")
                capped.forEach { appendLine(AppLogger.redactForReport(it)) }
            })
        }.onFailure {
            AppLogger.w(TAG, "Could not write the system report: ${it.message}")
            return@withContext null
        }
        AppLogger.i(TAG, "System report: ${capped.size} lines kept of ${kept.size} whitelisted")
        out
    }

    /**
     * States what was collected and what was left out, at the top of the file.
     *
     * The user is about to attach this to a public issue, so the file should say plainly what is in
     * it rather than requiring them to read every line to find out.
     */
    private fun header(rawLines: Int, kept: Int, attached: Int): String = """
        |CallVault system log report
        |
        |This file contains logcat lines from CallVault (app and background helper) and from the
        |Android audio, security and process-lifecycle services. Everything else — including other
        |applications' logs — was filtered out, and phone numbers were redacted.
        |
        |Scanned: $rawLines lines. Matched the filter: $kept. Attached (most recent): $attached.
        |-----------------------------------------------------------------------------------------
    """.trimMargin()

    /**
     * One shell command, output as text, or null on any failure. Never throws.
     *
     * Retries once through a fresh connection, mirroring [UsbDefaultConfig]: the embedded link
     * regularly fails the first read with "Stream closed" while the command itself still runs. That
     * bit us on the first device test — `logcat -G 8M` took effect and `logcat -g` returned nothing,
     * so the ring grew with no recorded size to restore it to.
     */
    /**
     * One diagnostic dump, preferring the daemon and falling back to the app's own ADB shell.
     *
     * **The daemon first, and this is the fix for a real failure.** Collecting these over ADB means
     * seven round-trips, each able to force a reconnect, behind the share screen's 45-second budget.
     * On a phone whose transport is unhealthy that budget runs out and the user silently receives
     * half a report — which cost a tester six rounds of pointless back-and-forth before we
     * reproduced it on our own OP12 by revoking WRITE_SECURE_SETTINGS.
     *
     * The daemon is already the shell user and already connected over binder, so it needs no
     * Wireless Debugging, no WRITE_SECURE_SETTINGS, no transport and no retries. It also works in
     * Shizuku mode, where the ADB path below deliberately refuses to run at all.
     *
     * @param key names a dump; the whitelist that turns it into a command lives in the daemon.
     * @param fallbackCommand the same dump as a shell command, for when no daemon is connected.
     */
    private fun dump(
        context: Context,
        key: String,
        fallbackCommand: String,
        arg: String? = null,
        expectOutput: Boolean = true,
    ): String? {
        RecorderConnection.service?.let { service ->
            val out = runCatching { service.diagnosticDump(key, arg) }
                .onFailure { AppLogger.d(TAG, "daemon dump '$key' failed: ${it.message}") }
                .getOrNull()
            // Empty is a real answer for the commands that print nothing on success, so only fall
            // back when the daemon gave us nothing at all.
            if (out != null && (!expectOutput || out.isNotBlank())) return out
            AppLogger.d(TAG, "daemon dump '$key' gave nothing; falling back to the ADB shell")
        }
        return runShell(context, fallbackCommand, expectOutput)
    }

    private fun runShell(context: Context, command: String, expectOutput: Boolean = true): String? {
        // Shizuku mode has no embedded ADB connection, and driving one anyway does real damage: this
        // collector is the reason toggling the debug-logging setting switched **Wireless debugging on**
        // on a phone in Shizuku mode, which is a mode that must never touch it. Reported from the
        // device on 2026-08-25; the ring-size commands are diagnostics, so losing them there is a known
        // and much smaller cost than opening a network port behind the user's back.
        if (AppPreferences(context).getPrivilegedMode().needsShizuku) {
            AppLogger.i(TAG, "Shizuku mode: skipping '$command' — no embedded ADB, and WD must stay untouched")
            return null
        }
        return AdbShell.asAdbUser(context, "the log collector") { runShellInner(context, command, expectOutput) }
    }

    private fun runShellInner(context: Context, command: String, expectOutput: Boolean): String? {
        repeat(SHELL_ATTEMPTS) { attempt ->
            val connected =
                if (attempt == 0) AdbShell.ensureConnected(context) else AdbShell.forceReconnect(context)
            if (!connected) return@repeat
            val out = runCatching {
                AdbShell.openShell(context, command).use { s ->
                    s.openInputStream().use { readTolerantly(it) }
                }
            }.onFailure {
                AppLogger.d(TAG, "Shell attempt ${attempt + 1} failed ('$command'): ${it.message}")
            }.getOrNull()
            if (!out.isNullOrEmpty()) return out
        }
        // `logcat -G` prints nothing when it succeeds, so silence there is the normal case and not
        // worth a warning. Only a command we actually wanted an answer from is worth complaining about.
        if (expectOutput) AppLogger.w(TAG, "Shell command produced nothing after retry ('$command')")
        return null
    }

    private const val SHELL_ATTEMPTS = 2

    /**
     * Reads a stream to its end, keeping whatever arrived if it is closed underneath us.
     *
     * `readBytes()` throws "Stream closed" and discards everything already received, which is how
     * the first device test lost `logcat -g`'s answer entirely — the output had arrived, the stream
     * was torn down before EOF, and the exception threw the bytes away with it. Here a truncated
     * answer still beats no answer: the ring size is on the first line.
     */
    private fun readTolerantly(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        runCatching {
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
        return String(out.toByteArray())
    }

    private const val READ_CHUNK = 8 * 1024
}
