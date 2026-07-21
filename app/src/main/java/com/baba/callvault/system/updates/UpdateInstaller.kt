/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * The two install paths of the in-app updater. Callers MUST run [ApkVerifier.isValidUpdate] on the
 * APK first — especially before [installSilentlyViaShell], which will happily install anything.
 *
 * - [installSilentlyViaShell]: zero-tap. STREAMS the verified APK bytes straight into `pm install -S`
 *   over the embedded ADB shell — the exact bytes that were verified are the bytes installed, with no
 *   world-readable intermediate file (closing the stage-then-install TOCTOU). Chains a
 *   WRITE_SECURE_SETTINGS re-grant that the surviving shell process runs after the app is replaced,
 *   healing the known grant-drop-on-update wart. Reads pm's own result so a rejected install is
 *   reported promptly instead of masquerading as success.
 * - [installViaPackageInstaller]: standard session install (fallback when the shell can't come up).
 *   Shows the system confirm dialog when required.
 */
object UpdateInstaller {

    private const val TAG = "CV:UpdateInstaller"

    /** Broadcast action [UpdateInstallReceiver] listens on for PackageInstaller session status. */
    const val ACTION_INSTALL_STATUS = "com.baba.callvault.UPDATE_INSTALL_STATUS"

    /** How long to wait for pm's "Success"/"Failure" line after streaming all bytes. */
    private const val RESULT_READ_BUDGET_MS = 20_000L

    /** Outcome of a shell install attempt. */
    enum class ShellResult {
        /** Install command accepted the bytes; final confirmation arrives via MY_PACKAGE_REPLACED. */
        DISPATCHED,
        /** pm rejected the install (bad signature, downgrade, storage, …) — reported promptly. */
        FAILED,
        /** The embedded ADB shell could not be brought up (e.g. Developer options off). */
        UNAVAILABLE
    }

    /**
     * Streams [apkFile] into `pm install -S` over the embedded ADB shell. Blocking (ADB I/O) — call
     * from a worker thread. `pm install -r -S <size>` reads exactly the APK's bytes from stdin, so the
     * verified file is installed directly with no intermediate copy. A trailing `&& pm grant …` is run
     * by the (shell-uid, separate) shell process, which survives this app being replaced.
     */
    fun installSilentlyViaShell(context: Context, apkFile: File): ShellResult {
        val size = apkFile.length()
        if (size <= 0L) return ShellResult.FAILED
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.w(TAG, "Silent install unavailable: embedded ADB shell did not connect")
            return ShellResult.UNAVAILABLE
        }
        // `-r` reinstall, `-S <size>` stream-from-stdin; the re-grant runs only on install success.
        val command =
            "pm install -r -S $size && " +
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

        // exec: (raw, no PTY) — REQUIRED for streaming the binary APK to stdin; shell: would corrupt
        // or prematurely close the stream (the "Stream closed mid-send" failure this fixes).
        val wroteAllBytes = runCatching {
            AdbShell.openExec(context, command).use { shell ->
                shell.openOutputStream().use { output ->
                    apkFile.inputStream().use { it.copyTo(output) }
                    output.flush()
                }
                // All bytes are sent; from here the install is committed. Read pm's verdict — but a
                // read exception / EOF now means the app is being replaced (success), NOT a failure.
                return readShellVerdict(shell)
            }
        }
        // Only reached if openShell / openOutputStream / the byte copy threw — a genuine pre-commit
        // failure (the return inside the block exits the function on the success path).
        AppLogger.w(TAG, "Silent install failed before bytes were fully sent: ${wroteAllBytes.exceptionOrNull()?.message}")
        return ShellResult.FAILED
    }

    /** After all bytes are streamed, classify pm's output. EOF/exception ⇒ replaced (DISPATCHED). */
    private fun readShellVerdict(shell: io.github.muntashirakon.adb.AdbStream): ShellResult {
        val output = StringBuilder()
        runCatching {
            shell.openInputStream().use { input ->
                val deadline = System.currentTimeMillis() + RESULT_READ_BUDGET_MS
                val buffer = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) output.append(String(buffer, 0, read))
                    if (output.contains("Success") || output.contains("Failure")) break
                }
            }
        }
        val text = output.toString()
        return when {
            text.contains("Failure") -> {
                AppLogger.e(TAG, "pm install rejected the update: ${text.trim()}")
                ShellResult.FAILED
            }
            // "Success" seen, or the stream ended without a verdict because the app is being
            // replaced — either way the install is under way; MY_PACKAGE_REPLACED confirms it.
            else -> {
                AppLogger.i(TAG, "Silent update install dispatched (streamed pm install)")
                ShellResult.DISPATCHED
            }
        }
    }

    /**
     * Standard PackageInstaller session commit of the VERIFIED [apkFile] (streamed straight into the
     * session — same bytes that were verified). Status is delivered to [UpdateInstallReceiver] via
     * [ACTION_INSTALL_STATUS]; USER_ACTION_NOT_REQUIRED is requested so the install is silent when
     * Android allows it, otherwise the system prompts.
     * @return true when the session was committed (outcome arrives via the receiver).
     */
    fun installViaPackageInstaller(context: Context, apkFile: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("callvault-update", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val statusReceiver = PendingIntent.getBroadcast(
                context, sessionId, statusIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(statusReceiver.intentSender)
        }
        AppLogger.i(TAG, "PackageInstaller session committed")
        true
    }.getOrElse { e ->
        AppLogger.e(TAG, "PackageInstaller session failed: ${e.message}", e)
        false
    }
}
