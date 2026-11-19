/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.IRecorderService
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.Executors

/**
 * Keeps the recorder's Wi-Fi join watch armed exactly while Shizuku mode's approval is wanted (review N15).
 *
 * The watch lives in the Shizuku-hosted recorder ([com.baba.callvault.server.WifiJoinMonitor]): it runs as shell,
 * stays up, and is not frozen by OEM app freezers. An earlier version registered a ConnectivityManager request held
 * by a PendingIntent from the app; Android sends such a request once and then releases it, so it heard one join only
 * (OP9, 2026-11-19). A recorder's watch dies with its process, so this runs on every recorder connection
 * ([RecorderConnection.onDaemonReady]), at app start, on the setting and on a mode switch; the recorder keeps one
 * registration however often it is armed.
 *
 * Backup: when the recorder answers that it is not watching (it could not register, or it is from an older build),
 * the app checks the current access point once itself ([WifiJoinApprovalWorker]) and says so in the always-on log.
 */
object WifiJoinWatch {

    private const val TAG = "CV:WifiApproveShizuku"

    /** One sync at a time, in order, off the caller's thread (arming is a binder call). */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "cv-wifi-join-watch").apply { isDaemon = true } }

    enum class SyncResult { WATCHING, NOT_WANTED, NO_RECORDER, FALLBACK }

    fun shouldWatch(mode: PrivilegedMode, settingOn: Boolean): Boolean = mode.needsShizuku && settingOn

    /** Arms or disarms the recorder's watch to match the mode and setting. Returns at once; safe on any thread. */
    fun sync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { syncNow(app, RecorderConnection.service) }
                .onFailure { AppLogger.w(TAG, "Wi-Fi join watch sync failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** Blocking; [fallback] runs the app's own one-off check. */
    fun syncNow(
        context: Context,
        recorder: IRecorderService?,
        fallback: (Context) -> Unit = WifiJoinApprovalWorker::enqueue,
        nowMs: Long = System.currentTimeMillis(),
    ): SyncResult {
        val prefs = AppPreferences(context)
        val wanted = shouldWatch(prefs.getPrivilegedMode(), prefs.isWifiNetworkAutoApproveEnabled())
        if (recorder == null) {
            if (wanted) AppLogger.i(TAG, "No recorder connected yet; its Wi-Fi join watch is armed when it connects")
            return SyncResult.NO_RECORDER
        }
        val keys = if (wanted) prefs.getApprovedAccessPointKeys(nowMs).toTypedArray() else emptyArray()
        val salt = if (wanted) prefs.getAccessPointKeySalt() else ""
        val armed = runCatching { recorder.armWifiJoinWatch(wanted, keys, salt) }
        // Lines first (the recorder's own "armed" or "could not watch, because…"), then the app's conclusion.
        runCatching { recorder.drainWifiJoinEvents() }
            .onSuccess { WifiJoinEvents.record(context, it, nowMs) }
            .onFailure { AppLogger.w(TAG, "Could not read the recorder's Wi-Fi watch lines: ${it.message}") }
        if (!wanted) return SyncResult.NOT_WANTED
        if (armed.getOrDefault(false)) return SyncResult.WATCHING

        val why = armed.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "it answered not watching"
        val line = "${WifiJoinEvents.PREFIX}the recorder is not watching Wi-Fi joins ($why); checking the current access point from the app once instead"
        AppLogger.w(TAG, line)
        runCatching { TransportTimeline.event(context, line) }
            .onFailure { AppLogger.w(TAG, "Could not write the transport timeline: ${it.message}") }
        fallback(context)
        return SyncResult.FALLBACK
    }
}
