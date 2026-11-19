/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Process
import com.baba.callvault.utils.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line answers to "what state was the phone in" for the always-on Drive journal ([DriveCopyJournal]).
 *
 * Every function here is total: it never throws and never returns an empty string, because a diagnostic that
 * can itself fail silently is exactly what the drive1 build shipped. A failed read says so in the line.
 */
object DriveDiagnostics {

    /** Name of the copier process's suffix as the system reports it. */
    private const val COPIER = DriveCopyProtocol.PROCESS_SUFFIX

    /** A short stable tag for a tree URI: enough to tell two folders apart, nothing that names one. */
    fun uriTag(uri: Uri?): String =
        if (uri == null) "none" else "${uri.authority}#${Integer.toHexString(uri.toString().hashCode())}"

    /**
     * Whether this process holds a persisted grant on [tree], and whether the system accepts it for this uid.
     * Asked in the app's main process AND inside `:drive`: a grant that exists in one and not the other is
     * the most likely reason the copier process could not list a folder the app could.
     */
    fun permission(context: Context, tree: Uri?): String = runCatching {
        if (tree == null) return@runCatching "no folder set"
        val held = context.contentResolver.persistedUriPermissions
        val match = held.firstOrNull { it.uri == tree }
        val check = context.checkUriPermission(
            tree, Process.myPid(), Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val checkText = if (check == android.content.pm.PackageManager.PERMISSION_GRANTED) "granted" else "denied"
        if (match != null) {
            "persisted read=${match.isReadPermission} write=${match.isWritePermission} (${held.size} grants held), system check=$checkText"
        } else {
            "NO persisted grant for this folder (${held.size} grants held), system check=$checkText"
        }
    }.getOrElse { "permission state unreadable (${it.javaClass.simpleName}: ${it.message})" }

    /** Free memory and the system's low-memory flag, plus this process's own heap. */
    fun memory(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        "avail=${info.availMem shr 20}MB of ${info.totalMem shr 20}MB, lowMemory=${info.lowMemory} (threshold ${info.threshold shr 20}MB), " +
            "heap=${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20}MB"
    }.getOrElse { "memory unreadable (${it.javaClass.simpleName}: ${it.message})" }

    /** The active network's kind and whether the system has validated it. */
    fun network(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return@runCatching "no active network"
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching "active network has no capabilities"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        "$kind internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "metered=${!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}"
    }.getOrElse { "network unreadable (${it.javaClass.simpleName}: ${it.message})" }

    /** True/false whether the copier process is running right now; "unknown" when the system will not say. */
    fun copierAlive(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = am.runningAppProcesses ?: return@runCatching "unknown (no process list)"
        procs.any { it.processName?.endsWith(COPIER) == true }.toString()
    }.getOrElse { "unknown (${it.javaClass.simpleName}: ${it.message})" }

    /** What the process list says about the copier process: running (with its id), not running, or unknown. */
    sealed interface CopierProcess {
        data class Running(val pid: Int) : CopierProcess
        data object None : CopierProcess
        /** The process list could not be read; [why] says how. Never to be taken for [None]. */
        data class Unknown(val why: String) : CopierProcess
    }

    /** The copier process as the system's process list shows it; [CopierProcess.Unknown] when that list cannot be read. */
    fun copierProcess(context: Context): CopierProcess = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val procs = am.runningAppProcesses ?: return@runCatching CopierProcess.Unknown("the system gave no process list")
        procs.firstOrNull { it.processName?.endsWith(COPIER) == true }?.let { CopierProcess.Running(it.pid) } ?: CopierProcess.None
    }.getOrElse {
        AppLogger.w("CV:DriveDiagnostics", "Could not read the process list: ${it.javaClass.simpleName}: ${it.message}")
        CopierProcess.Unknown("${it.javaClass.simpleName}: ${it.message}")
    }

    /**
     * The system's own record of how the copier process last ended, newest first (at most [limit]).
     * This is the only place a kill by Android shows: the process cannot write its own death.
     */
    fun copierExits(context: Context, limit: Int = 3): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val rows = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .filter { it.processName?.endsWith(COPIER) == true }
            .take(limit)
        if (rows.isEmpty()) "no exit record for the copier process"
        else rows.joinToString("; ") {
            "${stamp.format(Date(it.timestamp))} ${exitReason(it.reason)} status=${it.status} importance=${it.importance} " +
                "rss=${it.rss shr 20}MB desc=${it.description ?: "-"}"
        }
    }.getOrElse { "exit records unreadable (${it.javaClass.simpleName}: ${it.message})" }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "reason#$reason"
    }
}
