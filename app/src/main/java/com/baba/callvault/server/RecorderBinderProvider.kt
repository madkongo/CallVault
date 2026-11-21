/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.baba.callvault.services.recording.WifiJoinEvents
import com.baba.callvault.services.recording.WifiJoinWatch
import com.baba.callvault.services.recording.handoff.HandoffReceiver
import com.baba.callvault.services.recording.handoff.TrackAProbe
import com.baba.callvault.utils.AppLogger

/**
 * CallVault Plan 5 — PRODUCTION exported [ContentProvider] that RECEIVES the privileged recorder
 * daemon's [IBinder].
 *
 * The daemon ([RecorderServer], shell uid 2000, no Activity) cannot bind to us the normal way, so —
 * exactly like Shizuku — it resolves THIS provider as a non-app process and calls
 * `call("sendBinder", null, Bundle{BinderContainer})`. The marshalled binder survives the hop, after
 * which the app can issue binder IPC to the daemon with NO ADB (even while Wireless debugging is OFF).
 *
 * Ported from the proven spike (persistserver/RecorderBinderDebugProvider.kt). Mirrors Shizuku-API:
 *   RikkaApps/Shizuku-API — provider/src/main/java/rikka/shizuku/ShizukuProvider.java
 *     METHOD_SEND_BINDER = "sendBinder"; EXTRA_BINDER; call(): extras.setClassLoader(...); switch(method)
 *     -> handleSendBinder(extras): container = extras.getParcelable(EXTRA_BINDER);
 *        Shizuku.onBinderReceived(container.binder, ...)
 *
 * Exported=true is REQUIRED (and intentional in production): the shell-uid daemon is a non-app
 * process and can only reach an exported provider — this mirrors ShizukuProvider, which is likewise
 * exported so the privileged server can deliver its binder. The provider is a pure binder-delivery
 * channel (only `call("sendBinder")` is honoured); no app data is exposed.
 */
class RecorderBinderProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        AppLogger.i(TAG, "RecorderBinderProvider onCreate (pid=${Process.myPid()} uid=${Process.myUid()})")
        return true
    }

    /**
     * Entry point the daemon invokes. We only honour [METHOD_SEND_BINDER]; everything else returns an
     * empty Bundle. The classloader MUST be set before reading the [BinderContainer] or the Parcelable
     * unmarshals against the framework classloader and returns null (the classic Shizuku gotcha).
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (extras == null) { AppLogger.w(TAG, "Provider.call ignored method='$method' (null extras)"); return Bundle() }
        when (method) {
            METHOD_SEND_BINDER -> handleSendBinder(extras)
            METHOD_SEND_HANDOFF -> handleSendHandoff(extras)
            METHOD_WIFI_JOIN_EVENTS -> return handleWifiJoinEvents(extras)
            METHOD_WIFI_JOIN_APPROVAL_CONFIG -> return handleWifiJoinApprovalConfig()
            // Diagnostic only, and deliberately shell/self-only: it runs IN THE APP PROCESS, which is
            // the whole point — the question is what the APP's uid is allowed to do.
            METHOD_TRACK_A_PROBE, METHOD_TRACK_A_ARM, METHOD_TRACK_A_MEASURE -> {
                val uid = Binder.getCallingUid()
                if (!RecorderCallerPolicy.isTrusted(uid)) {
                    AppLogger.w(TAG, "trackAProbe rejected: untrusted caller uid=$uid")
                    return Bundle()
                }
                return when (method) {
                    METHOD_TRACK_A_ARM -> TrackAProbe.arm()
                    METHOD_TRACK_A_MEASURE -> TrackAProbe.measure(context!!, arg?.toIntOrNull() ?: 15)
                    else -> TrackAProbe.run()
                }
            }
            else -> AppLogger.w(TAG, "Provider.call ignored method='$method'")
        }
        return Bundle()
    }

    /** Resilient recording (Option B): receive the daemon-extracted IAudioRecord IBinder (+ cblk fd) and
     * hand it to the app-side capture controller, which holds the binder (keep-alive) + reads the ring. */
    private fun handleSendHandoff(extras: Bundle) {
        // Only the privileged shell-uid daemon (or system) may deliver a handoff: onReceived feeds the
        // cblk fd + geometry into native mmap/drain code, so an untrusted 3rd-party app must never reach
        // it. A rejected caller simply means no handoff arrives → the engine falls back to daemon mode.
        val callingUid = Binder.getCallingUid()
        if (!RecorderCallerPolicy.isTrusted(callingUid)) {
            AppLogger.w(TAG, "sendHandoff rejected: untrusted caller uid=$callingUid")
            return
        }
        extras.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION")
        val binder: IBinder? = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder
        @Suppress("DEPRECATION")
        val cblk = extras.getParcelable<android.os.ParcelFileDescriptor>(EXTRA_CBLK_FD)
        val frameCount = extras.getInt(EXTRA_FRAME_COUNT, 0)
        val sampleRate = extras.getInt(EXTRA_SAMPLE_RATE, 16000)
        val channels = extras.getInt(EXTRA_CHANNELS, 1)
        if (binder == null) { AppLogger.e(TAG, "sendHandoff: IAudioRecord binder null"); return }
        HandoffReceiver.onReceived(binder, cblk, frameCount, sampleRate, channels)
    }

    /**
     * The recorder's Wi-Fi watch lines, for the always-on log (review N15). Only from our recorder hosts: the lines
     * land in the log a report is made from, and may add an access point key.
     */
    private fun handleWifiJoinEvents(extras: Bundle): Bundle {
        val callingUid = Binder.getCallingUid()
        if (!RecorderCallerPolicy.isTrusted(callingUid)) {
            AppLogger.w(TAG, "wifiJoinEvents rejected: untrusted caller uid=$callingUid")
            return Bundle()
        }
        val ctx = context ?: return Bundle()
        WifiJoinEvents.record(ctx, extras.getStringArray(EXTRA_WIFI_JOIN_EVENTS))
        return Bundle()
    }

    /**
     * The recorder asks, right before each approval, whether it is still wanted (review N15): the answer is read from
     * the app's settings now, so a setting switched off while the recorder was not connected is honoured. Only our
     * recorder hosts get an answer; anyone else gets an empty Bundle, which the recorder reads as "not confirmed".
     */
    private fun handleWifiJoinApprovalConfig(): Bundle {
        val callingUid = Binder.getCallingUid()
        if (!RecorderCallerPolicy.isTrusted(callingUid)) {
            AppLogger.w(TAG, "wifiJoinApprovalConfig rejected: untrusted caller uid=$callingUid")
            return Bundle()
        }
        val ctx = context ?: return Bundle()
        return ApprovalConfigBundle.write(WifiJoinWatch.approvalConfig(ctx))
    }

    /** Mirrors `ShizukuProvider.handleSendBinder`: read the container, store + link-to-death the binder. */
    private fun handleSendBinder(extras: Bundle) {
        // Only our own recorder hosts may hand us a recorder. Any other app could otherwise swap in its
        // own and receive the output file of every new recording (see [RecorderCallerPolicy]).
        val callingUid = Binder.getCallingUid()
        if (!RecorderCallerPolicy.isTrusted(callingUid)) {
            AppLogger.w(TAG, "sendBinder rejected: untrusted caller uid=$callingUid")
            return
        }
        // CRITICAL (Shizuku gotcha): set the classloader so BinderContainer deserialises to OUR class.
        extras.classLoader = BinderContainer::class.java.classLoader

        @Suppress("DEPRECATION")
        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER)
        val binder: IBinder? = container?.binder
        if (binder == null) {
            AppLogger.e(TAG, "sendBinder: BinderContainer or its IBinder was null (classloader/SELinux?)")
            return
        }

        // Only our standalone daemon delivers through this provider (Shizuku's recorder is bound by ShizukuBackend). One
        // that arrives in Shizuku mode is a launch that was still running when the user switched: it must neither become
        // the recorder nor stay up to clear the Shizuku one (C107). A failed read of the mode keeps the old behaviour.
        if (!isForCurrentMode()) {
            AppLogger.w(TAG, "sendBinder: a standalone daemon arrived while in Shizuku mode; refusing it and telling it to exit")
            runCatching { IRecorderService.Stub.asInterface(binder).destroy() }
                .onFailure { AppLogger.w(TAG, "Could not stop the refused daemon: ${it.message}") }
            return
        }

        // A daemon that was already running handed its binder over because our process started (BabApps item 115).
        // Take it only when it is the right recorder for this phone; otherwise leave it, and the launcher replaces it.
        if (extras.getBoolean(EXTRA_REDELIVERY, false) && !acceptRedelivery(binder, extras.getString(EXTRA_HOST_APK))) return

        val service = IRecorderService.Stub.asInterface(binder)
        RecorderConnection.onBinderReceived(service)
        AppLogger.i(
            TAG,
            "received daemon binder uid=${Process.myUid()} pid=${Process.myPid()} alive=${binder.pingBinder()}"
        )

        // Clear the holder if the daemon dies so callers see a dead channel instead of an exception.
        runCatching {
            binder.linkToDeath(RecorderConnection.deathRecipient, 0)
        }.onFailure { AppLogger.w(TAG, "linkToDeath failed (binder may already be dead): ${it.message}") }
    }

    private fun isForCurrentMode(): Boolean {
        val ctx = context ?: return true
        return runCatching {
            BackendChoice.of(com.baba.callvault.data.AppPreferences(ctx).getPrivilegedMode()) == BackendChoice.ADB
        }.getOrDefault(true)
    }

    /** Decides a hand-over from a live daemon; every refusal is logged, so a report shows why it was not reused. */
    private fun acceptRedelivery(binder: IBinder, hostApk: String?): Boolean {
        val ctx = context ?: return false
        val prefs = com.baba.callvault.data.AppPreferences(ctx)
        val verdict = DaemonReuse.judgeRedelivery(
            standaloneMode = BackendChoice.of(prefs.getPrivilegedMode()) == BackendChoice.ADB,
            recordingStopped = prefs.getRecordingState() == com.baba.callvault.data.RecordingState.STOPPED,
            alreadyHeld = RecorderConnection.service?.asBinder() == binder,
            hostApkPath = hostApk,
            installedApkPath = ctx.applicationInfo.sourceDir,
        )
        if (verdict == DaemonReuse.Verdict.ACCEPT) {
            AppLogger.i(TAG, "A running recorder (apk=$hostApk) handed over its binder at app start; reusing it, no ADB launch")
        } else {
            AppLogger.i(TAG, "A running recorder handed over its binder at app start; not taking it: $verdict (its apk=$hostApk)")
        }
        return verdict == DaemonReuse.Verdict.ACCEPT
    }

    // ----- Unused ContentProvider surface: minimal stubs (we are a pure binder-delivery channel). -----

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "CV:RecorderConn"

        /** Provider authority registered in the manifest; the daemon resolves "<authority>". */
        const val AUTHORITY = "com.baba.callvault.recorder"

        /** Mirrors ShizukuProvider.METHOD_SEND_BINDER. */
        const val METHOD_SEND_BINDER = "sendBinder"

        /** Delivery of a handed-off capture (IAudioRecord binder + cblk fd); see HandoffReceiver. */
        const val METHOD_SEND_HANDOFF = "sendHandoff"

        /** Diagnostic entry point for the Track A permission probe. Not part of normal operation. */
        const val METHOD_TRACK_A_PROBE = "trackAProbe"
        const val METHOD_TRACK_A_ARM = "trackAProbeArm"
        const val METHOD_TRACK_A_MEASURE = "trackAProbeMeasure"

        /** The recorder's Wi-Fi watch lines for the always-on log (review N15); see WifiJoinEvent. */
        const val METHOD_WIFI_JOIN_EVENTS = "wifiJoinEvents"
        const val EXTRA_WIFI_JOIN_EVENTS = "com.baba.callvault.recorder.extra.WIFI_JOIN_EVENTS"

        /** The recorder asks whether a Wi-Fi approval is still wanted (review N15); see ApprovalConfigBundle. */
        const val METHOD_WIFI_JOIN_APPROVAL_CONFIG = "wifiJoinApprovalConfig"

        /** Bundle key for the [BinderContainer]; analogous to ShizukuProvider.EXTRA_BINDER. */
        const val EXTRA_BINDER = "com.baba.callvault.recorder.extra.BINDER"

        /** True when the daemon sends its binder again because the app's process started, not because we launched it. */
        const val EXTRA_REDELIVERY = "com.baba.callvault.recorder.extra.REDELIVERY"

        /** The APK the sending daemon runs from, so the app can tell a current daemon from one left by an old build. */
        const val EXTRA_HOST_APK = "com.baba.callvault.recorder.extra.HOST_APK"

        /** Bundle key for the cblk ParcelFileDescriptor. */
        const val EXTRA_CBLK_FD = "com.baba.callvault.recorder.extra.CBLK_FD"

        /** Bundle key for the authoritative ring frame count (AudioRecord.bufferSizeInFrames). */
        const val EXTRA_FRAME_COUNT = "com.baba.callvault.recorder.extra.FRAME_COUNT"

        /** Capture format keys, so the app-side drain + encoder match the daemon's AudioRecord. */
        const val EXTRA_SAMPLE_RATE = "com.baba.callvault.recorder.extra.SAMPLE_RATE"
        const val EXTRA_CHANNELS = "com.baba.callvault.recorder.extra.CHANNELS"
    }
}
