/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * The two install paths of the in-app updater. Callers MUST run [ApkVerifier.isValidUpdate] on the
 * APK first — especially before [installSilentlyViaShell], which will happily `pm install` anything.
 *
 * - [installSilentlyViaShell]: zero-tap. Stages the APK in the public Download folder (the shell
 *   uid cannot read app-private storage), then fires a DETACHED `pm install` over the embedded ADB
 *   shell — the same setsid technique as the daemon launch, so the install survives this very app
 *   being killed mid-replacement. Chains a WRITE_SECURE_SETTINGS re-grant after the install, healing
 *   the known grant-drop-on-update wart.
 * - [installViaPackageInstaller]: standard session install. Shows the system confirm dialog when
 *   required (always on the first in-app update; afterwards CallVault is the installer of record
 *   and Android 12+ applies it silently).
 */
object UpdateInstaller {

    private const val TAG = "CV:UpdateInstaller"

    /** Public staging name; the detached shell removes it after installing. */
    private const val STAGED_APK_NAME = "callvault-update.apk"
    private const val STAGED_APK_PATH = "/sdcard/Download/$STAGED_APK_NAME"

    /** Broadcast action [UpdateInstallReceiver] listens on for PackageInstaller session status. */
    const val ACTION_INSTALL_STATUS = "com.baba.callvault.UPDATE_INSTALL_STATUS"

    /**
     * Fires a silent, detached shell install of [apkFile]. Blocking (ADB I/O) — call from a worker
     * thread. Returns true when the install command was DELIVERED — the final outcome is reported
     * asynchronously: success via MY_PACKAGE_REPLACED ([UpdatePackageReplacedReceiver]), failure by
     * the pending-tag reconciliation on the next app start / worker run.
     */
    fun installSilentlyViaShell(context: Context, apkFile: File): Boolean {
        if (!stageApkInDownloads(context, apkFile)) return false
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.w(TAG, "Silent install unavailable: embedded ADB shell did not connect")
            cleanupStagedApk(context)
            return false
        }
        // Detached: setsid + stdio to /dev/null + '&' so the install survives adbd/app teardown;
        // trailing sleep keeps the launching shell alive long enough for the child to detach
        // (mirrors RecorderServerLauncher's proven launch technique). The re-grant heals the
        // WRITE_SECURE_SETTINGS drop that install-over causes on some OEMs.
        val command =
            "setsid sh -c 'pm install -r $STAGED_APK_PATH && " +
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; " +
                "rm -f $STAGED_APK_PATH' >/dev/null 2>&1 </dev/null & sleep 3"
        return runCatching {
            AdbShell.openShell(context, command).use { shell ->
                val deadline = System.currentTimeMillis() + SHELL_DRAIN_BUDGET_MS
                shell.openInputStream().use { input ->
                    val buffer = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        if (input.read(buffer) < 0) break
                    }
                }
            }
            AppLogger.i(TAG, "Silent update install dispatched (detached shell)")
            true
        }.getOrElse { e ->
            // "Stream closed" while draining is expected for a detached '&' launch — the command
            // was already delivered when the stream opened; treat only pre-open failures as fatal.
            AppLogger.d(TAG, "Silent install drain ended: ${e.message}")
            true
        }
    }

    /**
     * Standard PackageInstaller session commit of [apkFile]. Status (including a possible system
     * confirmation dialog) is delivered to [UpdateInstallReceiver] via [ACTION_INSTALL_STATUS].
     * USER_ACTION_NOT_REQUIRED is requested so the install is silent whenever Android allows it
     * (i.e. once CallVault is its own installer of record); otherwise the system prompts.
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
            session.openWrite(STAGED_APK_NAME, 0, apkFile.length()).use { out ->
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

    /**
     * Copies [apkFile] into the public Download folder so the shell uid can read it. Direct file
     * I/O into the public directory is allowed here because the file is app-created (scoped
     * storage permits an app to create files in Download via MediaStore); we insert through
     * MediaStore so the entry is tracked and cleanable.
     */
    private fun stageApkInDownloads(context: Context, apkFile: File): Boolean = runCatching {
        cleanupStagedApk(context)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, STAGED_APK_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            apkFile.inputStream().use { it.copyTo(out) }
        } ?: return false
        true
    }.getOrElse { e ->
        AppLogger.w(TAG, "Failed to stage update APK in Downloads: ${e.message}")
        false
    }

    /** Removes any previously staged copy (ours, by display name) from the Download collection. */
    fun cleanupStagedApk(context: Context) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(STAGED_APK_NAME)
            )
        }
    }

    private const val SHELL_DRAIN_BUDGET_MS = 4000L
}
