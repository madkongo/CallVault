/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android's own record of when CallVault stopped and started, and why (#71).
 *
 * A force-stopped app gets no call broadcasts until the user opens it again, so every call in between is lost
 * without a trace on our side — the log just starts again at the next launch. On Android 15+ that launch arrives
 * with BOOT_COMPLETED and [ApplicationStartInfo.wasForceStopped]; on every version the exit before it is in
 * [ApplicationExitInfo]. Read on demand: the system keeps the history, so nothing is stored here.
 */
object AppStopHistory {

    /** One start of the app's main process, in wall-clock time. */
    data class AppStart(val atWallMillis: Long, val afterForceStop: Boolean)

    private const val HISTORY = 16
    private const val DESCRIPTION_MAX = 120

    /**
     * Whether a call at [callAtMillis] fell in a force-stopped stretch: the first start after it left a force-stop.
     * A later start says nothing — the app may have been running at the call and stopped afterwards.
     */
    fun wasForceStoppedDuring(starts: List<AppStart>, callAtMillis: Long): Boolean =
        starts.filter { it.atWallMillis > callAtMillis }.minByOrNull { it.atWallMillis }?.afterForceStop == true

    /** [wasForceStoppedDuring] from the system's history. Always false before Android 15, which does not say. */
    fun wasForceStoppedDuring(context: Context, callAtMillis: Long): Boolean =
        runCatching { wasForceStoppedDuring(starts(context), callAtMillis) }.getOrDefault(false)

    /** A start as Android stamps it: time since the boot it happened in — which boot, it does not say. */
    data class RawStart(val sinceBootMillis: Long, val afterForceStop: Boolean)

    /** How far a start may sit after a newer one and still belong to the same boot (seen: 40 s on the emulator). */
    private const val SAME_BOOT_REORDER_MILLIS = 60_000L

    /**
     * The starts of the current boot, in wall-clock time. Android lists starts newest first and stamps them
     * since boot, so an earlier boot's starts map onto made-up times today (the emulator showed October starts as
     * "13:30 today"). They show up as a start in the future or a jump forward; the run stops at the first one.
     */
    fun currentBootStarts(raw: List<RawStart>, nowSinceBootMillis: Long, nowWallMillis: Long): List<AppStart> {
        val kept = mutableListOf<RawStart>()
        for (start in raw) {
            if (start.sinceBootMillis > nowSinceBootMillis) break
            val newestKept = kept.maxOfOrNull { it.sinceBootMillis }
            if (newestKept != null && start.sinceBootMillis > newestKept + SAME_BOOT_REORDER_MILLIS) break
            kept += start
        }
        return kept.map { AppStart(nowWallMillis - (nowSinceBootMillis - it.sinceBootMillis), it.afterForceStop) }
    }

    private fun starts(context: Context): List<AppStart> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val raw = am.getHistoricalProcessStartReasons(HISTORY)
            .filter { it.processName == context.packageName }
            .mapNotNull { info -> sinceBootMillis(info)?.let { RawStart(it, info.wasForceStopped()) } }
        return currentBootStarts(raw, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    /** The earliest startup timestamp, in milliseconds since the boot it happened in. */
    private fun sinceBootMillis(info: ApplicationStartInfo): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return info.startupTimestamps.values.minOrNull()?.let { it / 1_000_000 }
    }

    /** The report section: recent exits (Android 11+) and starts (Android 15+), newest first. */
    fun reportLines(context: Context): List<String> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return listOf("unavailable")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY).map {
            val description = it.description?.take(DESCRIPTION_MAX)?.let { d -> " \"$d\"" } ?: ""
            "exit  ${time.format(Date(it.timestamp))} ${it.processName} ${exitReasonName(it.reason)}" +
                " importance=${it.importance}$description"
        }
        val starts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val infos = am.getHistoricalProcessStartReasons(HISTORY)
            val raw = infos.map { RawStart(sinceBootMillis(it) ?: Long.MAX_VALUE, it.wasForceStopped()) }
            val current = currentBootStarts(raw, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            infos.mapIndexed { i, info ->
                val at = current.getOrNull(i)?.let { time.format(Date(it.atWallMillis)) } ?: "earlier boot"
                "start $at ${info.processName} ${startReasonName(info.reason)}" +
                    (if (info.wasForceStopped()) " AFTER FORCE-STOP" else "")
            }
        } else {
            listOf("starts: needs Android 15")
        }
        (exits.ifEmpty { listOf("no exits recorded") }) + starts
    }.getOrElse { listOf("unavailable: ${it.javaClass.simpleName}") }

    fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
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
        else -> "reason $reason"
    }

    fun startReasonName(reason: Int): String = when (reason) {
        ApplicationStartInfo.START_REASON_ALARM -> "ALARM"
        ApplicationStartInfo.START_REASON_BACKUP -> "BACKUP"
        ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "BOOT_COMPLETE"
        ApplicationStartInfo.START_REASON_BROADCAST -> "BROADCAST"
        ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "CONTENT_PROVIDER"
        ApplicationStartInfo.START_REASON_JOB -> "JOB"
        ApplicationStartInfo.START_REASON_LAUNCHER -> "LAUNCHER"
        ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "LAUNCHER_RECENTS"
        ApplicationStartInfo.START_REASON_OTHER -> "OTHER"
        ApplicationStartInfo.START_REASON_PUSH -> "PUSH"
        ApplicationStartInfo.START_REASON_SERVICE -> "SERVICE"
        ApplicationStartInfo.START_REASON_START_ACTIVITY -> "START_ACTIVITY"
        else -> "reason $reason"
    }
}
