/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.kitsumed.shizucallrecorder.utils.AppLogger

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
        if (method != METHOD_SEND_BINDER || extras == null) {
            AppLogger.w(TAG, "Provider.call ignored method='$method' (extras null=${extras == null})")
            return Bundle()
        }
        handleSendBinder(extras)
        return Bundle()
    }

    /** Mirrors `ShizukuProvider.handleSendBinder`: read the container, store + link-to-death the binder. */
    private fun handleSendBinder(extras: Bundle) {
        // CRITICAL (Shizuku gotcha): set the classloader so BinderContainer deserialises to OUR class.
        extras.classLoader = BinderContainer::class.java.classLoader

        @Suppress("DEPRECATION")
        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER)
        val binder: IBinder? = container?.binder
        if (binder == null) {
            AppLogger.e(TAG, "sendBinder: BinderContainer or its IBinder was null (classloader/SELinux?)")
            return
        }

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
        const val AUTHORITY = "com.kfir.callvault.recorder"

        /** Mirrors ShizukuProvider.METHOD_SEND_BINDER. */
        const val METHOD_SEND_BINDER = "sendBinder"

        /** Bundle key for the [BinderContainer]; analogous to ShizukuProvider.EXTRA_BINDER. */
        const val EXTRA_BINDER = "com.kfir.callvault.recorder.extra.BINDER"
    }
}
