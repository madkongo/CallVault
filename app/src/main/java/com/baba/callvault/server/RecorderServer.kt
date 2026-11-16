/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import com.baba.callvault.utils.AppLogger

/**
 * CallVault Plan 5, Tasks 1–2 — PRODUCTION persistent privileged recorder daemon.
 *
 * A bare `app_process` entrypoint (shell uid 2000, NO Android Activity) launched DETACHED so it
 * survives Wireless debugging being turned OFF (proven by the spike's setsid-detach technique). It
 * exposes an [IRecorderService] over binder and PUSHES that binder to the app's exported
 * [RecorderBinderProvider] (authority [RecorderBinderProvider.AUTHORITY]) the same way the Shizuku
 * server pushes its binder to a client app. The app then drives recording over that binder with NO
 * ADB — even while WD is OFF.
 *
 * **This class is only the way in.** What the daemon can actually do lives in [RecorderServiceImpl],
 * which is deliberately host-agnostic so a Shizuku user service can offer exactly the same recorder
 * without a second copy of it (`docs/dev-notes/2026-08-24-shizuku-support-plan.md`).
 *
 * FQCN app_process invokes (its `static void main(String[])`):
 *   `com.baba.callvault.server.RecorderServer`
 *
 * Ported from the proven spike:
 *  • persistserver/BinderDebugDaemon — looper prep, [IRecorderService.Stub] pattern, binder delivery
 *    (extracted to [BinderDelivery]).
 *  • persistserver/AudioCaptureDaemon — scrcpy child + CLIENT LocalSocket + ScrcpyClient → muxer
 *    (extracted to [RecorderSession]).
 *  • Shizuku — RikkaApps/Shizuku — server/.../ShizukuService for the overall command-channel shape.
 *
 * Args (positional): `[apkPath, (optional) authority]`. `apkPath` is the daemon's own APK
 * (`applicationInfo.sourceDir`), used to self-extract scrcpy ([ScrcpyJarExtractor]).
 */
@Keep
object RecorderServer {

    private const val TAG = "CV:RecorderServer"

    /**
     * app_process entrypoint. Prepares a looper (binder transactions dispatch on it and the
     * system-context delivery fallback's ContentResolver expects a Looper thread — mirrors Shizuku's
     * server thread), builds + delivers the stub, then loops until SIGTERM.
     *
     * @param args `[apkPath, (optional) authority]`.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = Process.myPid()
        val uid = Process.myUid()

        // Pre-detach logs: visible in the launching adb shell BEFORE the pipe closes; after detach
        // stdio is /dev/null. AppLogger.* still reaches logcat while WD is ON.
        println("RecorderServer starting pid=$pid uid=$uid args=${args.joinToString(",")}")
        AppLogger.i(TAG, "RecorderServer starting pid=$pid uid=$uid args=${args.joinToString(",")}")

        if (args.isEmpty()) {
            AppLogger.e(TAG, "Expected at least 1 arg: <apkPath> [authority]")
            return
        }
        val apkPath = args[0]
        val authority = args.getOrNull(1) ?: RecorderBinderProvider.AUTHORITY

        try {
            // A main looper is required (binder dispatch + system-context ContentResolver fallback).
            Looper.prepareMainLooper()

            // NOTE: scrcpy is NOT extracted here anymore — the direct AudioRecord path needs no jar, and
            // the scrcpy fallback re-extracts on demand ([RecorderServiceImpl.startWithFallback]). Keeps
            // daemon boot fast so a relaunch after an Athena reap is ready sooner (the cold-start that a
            // call races).

            val stub = RecorderServiceImpl(apkPath)
            val delivered = BinderDelivery.deliverBinderToApp(stub.asBinder(), authority)
            AppLogger.i(TAG, "Binder delivery finished ok=$delivered; entering Looper.loop()")
            // So an app process that starts later (after the app was stopped) can reuse this daemon (BabApps item 115).
            AppStartBinderSender.register(stub.asBinder(), authority, apkPath)

            // Keep the process + binder alive so the app can call us back over IPC, possibly while WD
            // is OFF. Ends on SIGTERM or destroy().
            // Enlarge the main log buffer once (BabApps #95), off the delivery path and only if the shell may.
            Thread {
                val out = runCatching { DiagnosticDumps.run("logcat_grow_host", null) }.getOrNull()
                AppLogger.i(TAG, "logcat main buffer enlarged to 16M: " + (if (out == null) "refused or failed" else out.trim().ifEmpty { "ok" }))
            }.apply { isDaemon = true; name = "cv-logcat-grow" }.start()
            Looper.loop()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "RecorderServer fatal: ${t.message}", t)
        }
    }
}
