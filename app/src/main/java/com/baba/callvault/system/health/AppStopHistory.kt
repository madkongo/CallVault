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

    /**
     * [wasForceStoppedDuring] from the system's history, plus the user's own Stop. The start flag alone missed it:
     * Active apps -> Stop leaves no force-stop flag on the next start (measured on the emulator), only an exit of
     * reason USER_REQUESTED. Exits exist from Android 11, so this works below Android 15 too.
     */
    fun wasForceStoppedDuring(context: Context, callAtMillis: Long): Boolean = runCatching {
        wasForceStoppedDuring(starts(context), callAtMillis) ||
            wasStoppedByUserDuring(exits(context), starts(context), callAtMillis)
    }.getOrDefault(false)

    /** One recorded end of the app's process. */
    data class ExitRecord(val atMillis: Long, val reason: Int, val description: String?, val pid: Int = 0)

    /**
     * Whether an exit is the user stopping the app: Active apps -> Stop, or Settings -> Force stop. Both are
     * USER_REQUESTED with "stop" in the description. Swiping the app out of Recents is also USER_REQUESTED but
     * never says "stop", and does not end the recorder's host, so it is not counted.
     */
    fun isUserStop(exit: ExitRecord): Boolean =
        exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            exit.description?.contains("stop", ignoreCase = true) == true

    /** The newest user stop, or null. */
    fun latestUserStop(exits: List<ExitRecord>): ExitRecord? = exits.filter(::isUserStop).maxByOrNull { it.atMillis }

    /** How far back a stop still counts as "a call may have been lost in it" when starts are not known. */
    private const val STOP_REACH_MILLIS = 24L * 60 * 60 * 1000

    /** The wake for a call starts the app's process shortly before the call is logged; a start this close is that wake. */
    private const val WAKE_SLACK_MILLIS = 10L * 60 * 1000

    /**
     * Whether a call at [callAtMillis] fell in a stretch the user had stopped the app: the newest user stop before
     * it, with nothing in between that brought the app back. Where Android lists starts (15+), a start after the
     * stop that is not the call's own wake means the app was running again, so the stop did not cause the miss.
     * Without starts, any other exit after the stop, or a stop more than a day old, rules it out.
     */
    fun wasStoppedByUserDuring(exits: List<ExitRecord>, starts: List<AppStart>, callAtMillis: Long): Boolean {
        val stop = latestUserStop(exits.filter { it.atMillis <= callAtMillis }) ?: return false
        if (exits.any { it.atMillis > stop.atMillis && it.atMillis <= callAtMillis }) return false
        if (starts.isNotEmpty()) {
            return starts.none { it.atWallMillis > stop.atMillis && it.atWallMillis < callAtMillis - WAKE_SLACK_MILLIS }
        }
        return callAtMillis - stop.atMillis <= STOP_REACH_MILLIS
    }

    /** The recorded exits of this app, newest first. Android 11+; empty before it. */
    fun exits(context: Context): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY)
            .map { ExitRecord(it.timestamp, it.reason, it.description, it.pid) }
    }

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
        val crashes = com.baba.callvault.system.diagnostics.CrashJournal.readForReport(context).orEmpty()
        var traced = 0
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, HISTORY).flatMap {
            val line = exitLine(
                time.format(Date(it.timestamp)), it.processName, it.reason, it.importance, it.pid, it.status, it.description,
            )
            // What the crash or ANR said, for the newest few: "crash" alone sent the vivo report (2026-11-20) to the code.
            val detail = if (traced < MAX_TRACED_EXITS) exitDetail(it, crashes).also { d -> if (d.isNotEmpty()) traced++ } else emptyList()
            // Redacted: a trace is text the app did not write. The exit line itself is not, so its time stays readable.
            listOf(line) + detail.map { d -> "      " + com.baba.callvault.utils.AppLogger.redactForReport(d) }
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

    /** One exit as the report prints it. [status] is shown only where it means something: the signal or exit code. */
    fun exitLine(
        time: String, process: String, reason: Int, importance: Int, pid: Int, status: Int, description: String?,
    ): String {
        val quoted = description?.take(DESCRIPTION_MAX)?.let { " \"$it\"" } ?: ""
        val withStatus = reason in STATUS_REASONS && status != 0
        return "exit  $time $process ${exitReasonName(reason)} pid=$pid" +
            (if (withStatus) " status=$status" else "") + " importance=$importance$quoted"
    }

    /**
     * The part of a trace worth printing: from the main thread's header in an ANR dump (the thread that did not
     * answer), else the first lines. Blank lines end the main thread's block, so the next thread is not printed.
     */
    fun traceExcerpt(trace: String, maxLines: Int): List<String> {
        val lines = trace.lines()
        val main = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (main < 0) return lines.filter { it.isNotBlank() }.take(maxLines)
        return lines.drop(main).takeWhile { it.isNotBlank() }.take(maxLines)
    }

    /**
     * For a Java crash, the exception from the crash journal (Android keeps no trace of one); for an ANR, the trace
     * Android kept; for a native crash, whether it left a tombstone (a protobuf, not text).
     */
    private fun exitDetail(exit: ApplicationExitInfo, crashJournal: String): List<String> = runCatching {
        when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> listOf(
                com.baba.callvault.system.diagnostics.CrashJournal.summaryFor(crashJournal, exit.pid, exit.timestamp)
                    ?.let { "crash: $it (whole note under Crashes)" }
                    ?: "crash: no note in the crash journal (a version without it, or it could not write)",
            )
            ApplicationExitInfo.REASON_ANR -> exit.traceInputStream?.use { stream ->
                val head = ByteArray(TRACE_READ_BYTES)
                val read = stream.read(head).coerceAtLeast(0)
                traceExcerpt(String(head, 0, read), TRACE_LINES).map { "anr: $it" }
            } ?: listOf("anr: no trace kept")
            ApplicationExitInfo.REASON_CRASH_NATIVE -> listOf(
                if (exit.traceInputStream?.use { true } == true) "native crash: tombstone kept by Android (binary)"
                else "native crash: no tombstone kept",
            )
            else -> emptyList()
        }
    }.getOrElse { listOf("trace unreadable: ${it.javaClass.simpleName}") }

    private const val MAX_TRACED_EXITS = 3
    private const val TRACE_LINES = 15
    private const val TRACE_READ_BYTES = 64 * 1024
    private val STATUS_REASONS = setOf(
        ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_EXIT_SELF,
    )

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
