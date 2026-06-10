/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import com.kitsumed.shizucallrecorder.server.RecorderConnection
import com.kitsumed.shizucallrecorder.server.RecorderServerLauncher
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.FileWriter

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0a).
 *
 * Exported debug-only receiver so the device-driver can trigger the detached daemon launch from a
 * laptop adb shell without any UI. Trigger:
 *   adb shell am broadcast -n com.kfir.callvault/com.kitsumed.shizucallrecorder.persistserver.PersistDebugReceiver \
 *       -a com.kitsumed.shizucallrecorder.persist.LAUNCH
 *
 * Exported is acceptable here because this is a throwaway spike receiver, not production surface.
 * The launch is offloaded to a background thread because [PersistDaemonLauncher.launch] performs
 * ADB network I/O and must not run on the main thread.
 */
class PersistDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_LAUNCH -> {
                AppLogger.i(TAG, "PersistDebugReceiver triggered; launching heartbeat daemon off main thread")
                Thread {
                    runCatching { PersistDaemonLauncher.launch(appContext) }
                        .onFailure { AppLogger.e(TAG, "Launch thread failed: ${it.message}", it) }
                    AppLogger.i(TAG, "PersistDebugReceiver heartbeat launch thread finished")
                }.apply { isDaemon = true }.start()
            }

            ACTION_LAUNCH_AUDIO -> {
                AppLogger.i(TAG, "PersistDebugReceiver triggered; launching audio-capture daemon off main thread")
                Thread {
                    runCatching { PersistDaemonLauncher.launchAudioCapture(appContext) }
                        .onFailure { AppLogger.e(TAG, "Audio launch thread failed: ${it.message}", it) }
                    AppLogger.i(TAG, "PersistDebugReceiver audio launch thread finished")
                }.apply { isDaemon = true }.start()
            }

            ACTION_LAUNCH_RECORDER_SERVER -> {
                AppLogger.i(RECORDER_TAG, "PersistDebugReceiver triggered LAUNCH_RECORDER_SERVER; running off main thread")
                Thread {
                    runCatching {
                        val ok = RecorderServerLauncher.ensureServerRunning(appContext)
                        AppLogger.i(
                            RECORDER_TAG,
                            "ensureServerRunning returned=$ok ; RecorderConnection.isConnected=${RecorderConnection.isConnected}"
                        )
                    }.onFailure { AppLogger.e(RECORDER_TAG, "Recorder server launch thread failed: ${it.message}", it) }
                    AppLogger.i(RECORDER_TAG, "PersistDebugReceiver recorder-server launch thread finished")
                }.apply { isDaemon = true }.start()
            }

            ACTION_BINDER_PING -> {
                AppLogger.i(BINDER_TAG, "PersistDebugReceiver triggered BINDER_PING; running off main thread")
                Thread {
                    runCatching { runBinderPing(appContext) }
                        .onFailure { AppLogger.e(BINDER_TAG, "Binder ping thread failed: ${it.message}", it) }
                    AppLogger.i(BINDER_TAG, "PersistDebugReceiver binder ping thread finished")
                }.apply { isDaemon = true }.start()
            }

            ACTION_SET_PERSISTENT -> {
                // Debug-only: flip the isPersistentServerEnabled gate from adb without the (Task 5) UI.
                val enabled = intent.getBooleanExtra("enabled", true)
                com.kitsumed.shizucallrecorder.data.AppPreferences(appContext).setPersistentServerEnabled(enabled)
                AppLogger.i(RECORDER_TAG, "SET_PERSISTENT -> isPersistentServerEnabled=$enabled")
            }
        }
    }

    /**
     * THROWAWAY de-risk spike (CallVault Plan 5, Task 0c) — drives the BINDER COMMAND CHANNEL test
     * from the APP side (binder IPC, NO ADB; works even while WD is OFF).
     *
     * Reads the daemon interface the provider stored in [RecorderBinderDebugHolder]; if absent, logs
     * "no binder yet" (the daemon hasn't delivered yet). Otherwise it: pings, reads the daemon uid,
     * and runs the risk #4 test — opens a read/write [ParcelFileDescriptor] to an app-owned file the
     * shell-uid daemon could NOT create itself, has the daemon write into it, then reads it back to
     * confirm. Every step is logged under [BINDER_TAG] and appended to [STATUS_PATH] so the device-
     * driver can read results after toggling WD back on.
     */
    private fun runBinderPing(context: Context) {
        val service = RecorderBinderDebugHolder.service
        if (service == null) {
            AppLogger.w(BINDER_TAG, "no binder yet (provider has not received the daemon binder)")
            appendStatus("APP_PING_NO_BINDER ${System.currentTimeMillis()}\n")
            return
        }

        // 1. ping + daemon uid (proves bidirectional binder IPC, app -> daemon, no ADB).
        val pong = service.ping("hi")
        val daemonUid = service.myUid()
        AppLogger.i(BINDER_TAG, "ping reply: $pong ; daemon myUid=$daemonUid")
        appendStatus("APP_PING ${System.currentTimeMillis()} reply=$pong daemonUid=$daemonUid\n")

        // 2. risk #4: pass a fd to an app-owned file the daemon (shell uid) cannot create; it writes.
        val outFile = File(context.filesDir, PFD_TEST_FILE)
        runCatching { outFile.delete() }
        // Ensure the file exists before opening read-write, then hand the fd to the daemon.
        outFile.createNewFile()
        val payload = "written-by-daemon-${System.currentTimeMillis()}"
        val wrote = ParcelFileDescriptor.open(
            outFile,
            ParcelFileDescriptor.MODE_READ_WRITE
        ).use { pfd -> service.writeToPfd(pfd, payload) }

        val readBack = runCatching { outFile.readText() }.getOrElse { "<read-failed: ${it.message}>" }
        AppLogger.i(
            BINDER_TAG,
            "writeToPfd returned=$wrote ; app file ${outFile.absolutePath} now contains: '$readBack' " +
                "(daemonUid=$daemonUid) (risk #4 ${if (wrote && readBack == payload) "PASS" else "CHECK"})"
        )
        appendStatus(
            "APP_PFD ${System.currentTimeMillis()} wrote=$wrote daemonUid=$daemonUid " +
                "expected='$payload' got='$readBack' file=${outFile.absolutePath}\n"
        )
    }

    /** Appends [line] to the shared 0c status file so the driver can read results after WD-on. */
    private fun appendStatus(line: String) {
        runCatching {
            FileWriter(File(STATUS_PATH), /* append = */ true).use { w ->
                w.write(line)
                w.flush()
            }
        }
    }

    companion object {
        private const val TAG = "SCR:Persist"

        /** 0c log tag (matches the daemon + provider) for the binder command-channel test. */
        private const val BINDER_TAG = "SCR:Binder"

        /** Task 4 log tag (matches RecorderServerLauncher) for the production recorder-server launch. */
        private const val RECORDER_TAG = "SCR:RecorderLauncher"

        /** Durable 0c evidence file shared with [BinderDebugDaemon]; driver reads it after WD-on. */
        private const val STATUS_PATH = "/data/local/tmp/cv_binder_status.txt"

        /** App-owned file (under filesDir) the shell-uid daemon writes via the passed fd (risk #4). */
        private const val PFD_TEST_FILE = "cv_pfd_test.txt"

        const val ACTION_LAUNCH = "com.kitsumed.shizucallrecorder.persist.LAUNCH"

        /** 0b: triggers the detached audio-capture daemon launch. */
        const val ACTION_LAUNCH_AUDIO = "com.kitsumed.shizucallrecorder.persist.LAUNCH_AUDIO"

        /** 0c: drives the app-side binder command-channel test (ping + pfd write). */
        const val ACTION_BINDER_PING = "com.kitsumed.shizucallrecorder.persist.BINDER_PING"

        /** Task 4: launches the PRODUCTION recorder server (RecorderServerLauncher.ensureServerRunning). */
        const val ACTION_LAUNCH_RECORDER_SERVER = "com.kitsumed.shizucallrecorder.persist.LAUNCH_RECORDER_SERVER"

        /** Debug-only: set the isPersistentServerEnabled gate (boolean extra "enabled", default true). */
        const val ACTION_SET_PERSISTENT = "com.kitsumed.shizucallrecorder.persist.SET_PERSISTENT"
    }
}
