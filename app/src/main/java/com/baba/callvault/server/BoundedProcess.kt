/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one diagnostic command with a timeout that actually fires.
 *
 * The dumps used to read the whole output and only then `waitFor(timeout)` — so the timeout could never fire: a
 * wedged `logd` kept `logcat -d` from ending, the read never returned, and the recorder's binder thread was held
 * for good. Here the output is read on a thread of its own and the caller waits at most [run]'s `timeoutMs`; then
 * the process is killed, which ends the read with what had arrived. When a grandchild still holds the pipe (a
 * shell pipeline whose shell was killed), the reader is abandoned — it is a daemon thread — and the caller gets
 * no value rather than waiting on it.
 */
object BoundedProcess {

    /** How long a killed process gets to let its reader finish with what had arrived. */
    private const val GRACE_MS = 500L

    data class Outcome<T>(val value: T?, val timedOut: Boolean, val exitCode: Int?, val timeoutMs: Long) {
        /** Why the output may be incomplete, or null when the command ran to a clean end. */
        val partial: String?
            get() = when {
                timedOut -> "timed out after ${timeoutMs / 1000} s"
                exitCode == null -> "exit status unknown"
                exitCode != 0 -> "exited with status $exitCode"
                else -> null
            }
    }

    fun <T> run(command: Array<String>, timeoutMs: Long, read: (InputStream) -> T): Outcome<T> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val result = AtomicReference<Result<T>?>(null)
        val reader = Thread({ result.set(runCatching { process.inputStream.use(read) }) }, "cv-dump-reader")
            .apply { isDaemon = true; start() }
        try {
            reader.join(timeoutMs)
            val timedOut = reader.isAlive
            if (timedOut) {
                process.destroyForcibly()
                reader.join(GRACE_MS)
            }
            val value = if (reader.isAlive) null else result.get()?.getOrNull()
            val exitCode = if (process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) process.exitValue() else null
            return Outcome(value, timedOut, if (timedOut) null else exitCode, timeoutMs)
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * [command]'s whole output as text, or null when it could not be had in full: not started, timed out (the
     * output would be cut at an arbitrary byte, and a parser could take half a number for a whole one) or blank.
     * A non-zero exit still returns the text: `dumpsys` often prints its answer and exits non-zero. Logs why a
     * null came back, under [tag], so a report can say which dump was missing.
     */
    fun runText(command: Array<String>, timeoutMs: Long, tag: String): String? {
        val outcome = try {
            run(command, timeoutMs) { it.bufferedReader().readText() }
        } catch (e: java.io.IOException) {
            AppLogger.w(tag, "could not start ${command.lastOrNull()?.take(40)}: ${e.message}")
            throw e
        }
        if (outcome.timedOut) {
            AppLogger.w(tag, "${command.lastOrNull()?.take(40)} timed out after ${timeoutMs} ms; stopped it, answer not used")
            return null
        }
        return outcome.value?.takeIf { it.isNotBlank() }
    }
}
