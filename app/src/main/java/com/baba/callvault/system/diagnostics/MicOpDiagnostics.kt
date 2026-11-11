/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import android.content.Context
import android.media.AudioManager
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.RecorderConnection

/**
 * Always-on evidence for BabApps #95 (a shell microphone op that stays running after a capture was invalidated at its
 * start): at a few named moments of a handoff capture, one line in the transport timeline (written whether or not debug
 * logging is on, so it is in every debug report) with the three things that decide which explanation is true:
 *  - the shell RECORD_AUDIO op: running or not, and since when (from `dumpsys appops`),
 *  - the processes running as the shell user, with parent and start time (what else could hold the op, who launched
 *    whom, which one is the recorder host),
 *  - the audio mode.
 *
 * Small and off the call path: it runs on its own low-priority thread, reads through the recorder host (two bounded
 * dumps), and never throws.
 */
object MicOpDiagnostics {

    private const val MAX_PROCESS_LINES = 30

    /** Takes a snapshot on a background thread and writes it as `mic diag [label]: …`. */
    fun snapshotAsync(context: Context, label: String) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val service = RecorderConnection.service
                val appops = runCatching { service?.diagnosticDump("appops_mic", null) }.getOrNull()
                val ps = runCatching { service?.diagnosticDump("processes_times", null) }.getOrNull()
                val mode = runCatching { app.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
                TransportTimeline.event(app, format(label, mode, appops, ps))
            }
        }.apply { isDaemon = true; name = "cv-mic-diag"; priority = Thread.MIN_PRIORITY }.start()
    }

    /** The shell user's mic ops: "none running" or each op with the time it has been running. */
    fun shellOpsSummary(appopsDump: String?): String {
        if (appopsDump.isNullOrBlank()) return "unreadable"
        val shell = MicOpReport.heldMicOps(appopsDump).filter { it.isShell }
        return if (shell.isEmpty()) "none running" else shell.joinToString(", ") { "${it.op} running since ${it.startedAt}" }
    }

    /** The shell-user process lines of `ps -o USER,PID,PPID,STIME,ARGS`, capped, joined for one line. */
    fun shellProcessLines(psDump: String?): String {
        if (psDump.isNullOrBlank()) return "unreadable"
        val lines = psDump.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.startsWith("shell ") }.toList()
        if (lines.isEmpty()) return "none"
        return lines.take(MAX_PROCESS_LINES).joinToString(" | ") +
            if (lines.size > MAX_PROCESS_LINES) " | … ${lines.size - MAX_PROCESS_LINES} more" else ""
    }

    /** Only the lines that name a CallVault recorder or its launcher, for the bring-up line. */
    fun recorderProcessLines(psDump: String?): String {
        if (psDump.isNullOrBlank()) return "process list unreadable"
        val lines = psDump.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.contains("callvault") || it.contains("app_process") || it.contains("setsid") }
            .toList()
        return if (lines.isEmpty()) "no recorder-looking process" else lines.take(MAX_PROCESS_LINES).joinToString(" | ")
    }

    fun format(label: String, audioMode: Int?, appopsDump: String?, psDump: String?): String =
        "mic diag [$label]: audioMode=${audioMode ?: "?"} shellMicOps=${shellOpsSummary(appopsDump)} " +
            "shellProcesses=${shellProcessLines(psDump)}"
}
