/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.provider.Settings
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.system.diagnostics.UsbOffLogWindow
import com.baba.callvault.utils.AppLogger

/**
 * What CallVault does when the phone switches USB debugging off by itself (Honor, Samsung — see
 * [UsbDebuggingRestorePolicy]).
 *
 * Every event is written to [UsbDebuggingEvents] with the phone's state, then either switched back on (screen off
 * or locked, so not the user) or left for the user's one tap on the notification. Once the recorder is back,
 * [captureLogsIfPending] saves what the phone logged around the moment, which names whoever wrote the switch.
 */
object UsbDebuggingRestorer {

    private const val TAG = "CV:UsbRestore"

    /** Lets a USB change that is still running finish before writing the switch back (see AdbShell's revive). */
    private const val SETTLE_MS = 1_500L
    private const val CONFIRM_WAIT_MS = 6_000L
    private const val POLL_MS = 250L

    /** Pending captures older than this have left the log ring anyway. */
    private const val CAPTURE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /**
     * Called the moment USB debugging is proven off. Blocks for a few seconds; call it off the main thread.
     *
     * @param needsAdb the recorder runs over CallVault's own ADB.
     * @return true when USB debugging is back on and `adbd` is running.
     */
    fun onUsbDebuggingOff(context: Context, needsAdb: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val snapshot = UsbOffSnapshot.capture(context)
        val decision = UsbDebuggingRestorePolicy.decide(
            needsAdb = needsAdb,
            hasGrant = AdbShell.hasWriteSecureSettings(context),
            screenOn = snapshot.screenOn,
            locked = snapshot.locked,
            recentRestores = UsbDebuggingEvents.recentRestores(context),
            now = now,
        )
        UsbDebuggingEvents.record(context, "USB debugging went OFF: $snapshot → $decision")
        UsbDebuggingEvents.addPending(context, now)
        AppLogger.i(TAG, "USB debugging went off ($snapshot); decision=$decision")
        if (decision != UsbRestore.RESTORE) return false

        Thread.sleep(SETTLE_MS)
        UsbDebuggingEvents.addRestore(context, now)
        return turnOn(context, why = "automatic")
    }

    /**
     * Writes USB debugging on and waits for `adbd`. [alsoChargingAdb] also switches on Honor/Huawei's
     * "Allow ADB debugging in 'Charge only' mode" — only on the user's own tap, since it is their Developer option.
     */
    fun turnOn(context: Context, why: String, alsoChargingAdb: Boolean = false): Boolean {
        val cr = context.contentResolver
        if (alsoChargingAdb && UsbOffSnapshot.chargingAdb(context) == 0) {
            val wrote = runCatching { Settings.Global.putInt(cr, UsbOffSnapshot.CHARGING_ADB, 1) }
                .onFailure { AppLogger.w(TAG, "Could not write allow_charging_adb: ${it.message}") }
                .getOrDefault(false)
            UsbDebuggingEvents.record(
                context,
                "  allow_charging_adb write=${if (wrote) "ok" else "refused"} now=${UsbOffSnapshot.chargingAdb(context)}",
            )
        }
        val wrote = runCatching { Settings.Global.putInt(cr, "adb_enabled", 1) }
            .onFailure { AppLogger.w(TAG, "Could not write adb_enabled: ${it.message}") }
            .getOrDefault(false)

        val deadline = System.currentTimeMillis() + CONFIRM_WAIT_MS
        var running = false
        while (System.currentTimeMillis() < deadline) {
            running = AdbShell.adbdState() == AdbdState.RUNNING
            if (running) break
            Thread.sleep(POLL_MS)
        }
        val setting = runCatching { Settings.Global.getInt(cr, "adb_enabled", -1) }.getOrDefault(-1)
        val line = "  turned back on ($why): write=${if (wrote) "ok" else "refused"} adb_enabled=$setting " +
            "adbd=${if (running) "running" else AdbShell.adbdState()}"
        UsbDebuggingEvents.record(context, line)
        AppLogger.i(TAG, line.trim())
        return running
    }

    /**
     * Saves the phone's own log lines from around each USB-debugging-off event not yet captured. Needs the
     * recorder (shell uid reads the whole log); call it when the recorder connects, off the main thread.
     */
    fun captureLogsIfPending(context: Context) {
        val pending = UsbDebuggingEvents.pending(context)
        if (pending.isEmpty()) return
        val service = RecorderConnection.service ?: return
        val now = System.currentTimeMillis()
        for (at in pending) {
            if (now - at > CAPTURE_MAX_AGE_MS) {
                UsbDebuggingEvents.clearPending(context, at)
                continue
            }
            // Too soon: the trailing part of the window has not been logged yet. Wait for it here (this runs on its
            // own thread) — a recorder that is back within seconds is the normal case, and nothing calls again later.
            val wait = UsbOffLogWindow.TRAIL_MS - (System.currentTimeMillis() - at)
            if (wait > 0) Thread.sleep(wait)
            val raw = runCatching { service.diagnosticDump("logcat_since", UsbOffLogWindow.startStamp(at)) }
                .onFailure { AppLogger.d(TAG, "log window read failed: ${it.message}") }
                .getOrNull() ?: continue
            val kept = UsbOffLogWindow.select(raw.lineSequence(), at)
            val names = UsbOffLogWindow.processNames(
                runCatching { service.diagnosticDump("processes", null) }.getOrNull(),
                UsbOffLogWindow.pidsOf(kept),
            )
            UsbDebuggingEvents.record(context, buildString {
                append("  phone log around the event (${kept.size} lines)")
                if (names.isNotEmpty()) append("; pids: ").append(names.entries.joinToString { "${it.key}=${it.value}" })
                kept.forEach { append("\n    ").append(AppLogger.redactForReport(it)) }
            })
            UsbDebuggingEvents.clearPending(context, at)
        }
    }
}
