/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * The diagnostic dumps the privileged daemon is willing to run on the app's behalf.
 *
 * **Why this exists.** The system half of a debug report is `logcat`, `dumpsys audio`, `dumpsys
 * appops` and `ps` — commands that need the shell user. The app used to reach them by opening its own
 * ADB shell, which meant seven round-trips, each able to reconnect, behind a 45-second budget. On a
 * phone whose transport is not healthy that budget ran out and the user silently got half a report,
 * which cost a tester six rounds of pointless back-and-forth. Reproduced on our own OP12 by revoking
 * WRITE_SECURE_SETTINGS: one file, after a long wait.
 *
 * The daemon already runs as the shell user and the app already talks to it over binder. So it can
 * simply run these itself — no Wireless Debugging, no WRITE_SECURE_SETTINGS, no transport, no
 * retries, no timeout to run out.
 *
 * **Why a whitelist and not a command runner.** A method that executed a string handed over the
 * binder would give shell-uid execution to anything that could reach this service. The app names a
 * dump; the daemon decides what that name means. The only caller-supplied value that ever reaches a
 * command line is the logcat buffer size on restore, and it is checked against [SIZE] first.
 */
object DiagnosticDumps {

    private const val TAG = "CV:DiagDump"

    /** Absolute paths: a bare name resolves against PATH, which a privileged process must not trust. */
    private const val SH = "/system/bin/sh"
    private const val LOGCAT = "/system/bin/logcat"
    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val PS = "/system/bin/ps"
    private const val SETTINGS = "/system/bin/settings"

    /** A logcat buffer size and nothing else — digits with an optional unit. */
    private val SIZE = Regex("^[0-9]{1,7}[KMG]?$")

    /** How long any one dump may take. Generous for `logcat -d` on a full ring, finite for everything. */
    private const val TIMEOUT_SECONDS = 20L

    /**
     * The command for [key], or null if it is not one we run.
     *
     * [arg] is used only by `logcat_restore`, and only after [SIZE] accepts it.
     */
    fun commandFor(key: String, arg: String?): Array<String>? = when (key) {
        "logcat_size" -> arrayOf(LOGCAT, "-g")
        "logcat_grow" -> arrayOf(LOGCAT, "-b", "main", "-G", "8M")
        "logcat_restore" -> arg?.takeIf { SIZE.matches(it) }?.let { arrayOf(LOGCAT, "-b", "main", "-G", it) }
        "logcat_dump" -> arrayOf(LOGCAT, "-b", "main", "-b", "system", "-d", "-v", "threadtime")
        "dumpsys_audio" -> arrayOf(DUMPSYS, "audio")
        "appops_all" -> arrayOf(DUMPSYS, "appops")
        // Filtered on the device: the raw dump is megabytes and most of it is irrelevant. A pipeline
        // is the one case that needs a shell.
        "appops_mic" -> arrayOf(
            SH, "-c",
            "$DUMPSYS appops | grep -E '^[[:space:]]*(Uid [0-9]+:|Package |[A-Z_]+ \\(|Running start at:)'",
        )
        "processes" -> arrayOf(PS, "-A", "-o", "USER,PID,ARGS")
        // The two settings Android 17 redacts, read from HERE rather than from the app.
        //
        // Both of the platform's redaction sites exempt uid < FIRST_APPLICATION_UID, and the daemon is
        // uid 2000 (confirmed on a Pixel 8 running Android 17: `recorder host identity: uid=2000 …
        // context=u:r:shell:s0`). So this process should see the true value where the app process is
        // told "0" whatever the truth. Nothing in the app can recover it — the second redaction site
        // runs INSIDE the calling app's own process — so a lower uid is the only way.
        //
        // Read-only, fixed key, no argument: the name selects the setting, the caller never supplies it.
        // See docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md.
        "setting_adb_enabled" -> arrayOf(SETTINGS, "get", "global", "adb_enabled")
        "setting_dev_options" -> arrayOf(SETTINGS, "get", "global", "development_settings_enabled")
        else -> null
    }

    /** Runs [key] and returns its output, or null if it is not allowed, fails, or times out. */
    fun run(key: String, arg: String?): String? {
        val command = commandFor(key, arg) ?: run {
            AppLogger.w(TAG, "refused unknown diagnostic dump '$key'")
            return null
        }
        return runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                AppLogger.w(TAG, "diagnostic dump '$key' timed out after ${TIMEOUT_SECONDS}s")
                return null
            }
            output
        }.onFailure { AppLogger.w(TAG, "diagnostic dump '$key' failed: ${it.message}") }.getOrNull()
    }
}
