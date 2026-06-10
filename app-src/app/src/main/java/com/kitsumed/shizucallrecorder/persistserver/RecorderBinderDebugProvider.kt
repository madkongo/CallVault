/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.kitsumed.shizucallrecorder.utils.AppLogger

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0c) — risk #3.
 *
 * Exported [ContentProvider] that RECEIVES the detached shell-uid daemon's [IBinder]. The daemon
 * (no Activity, uid 2000) cannot bind to us the normal way, so — exactly like Shizuku — it resolves
 * THIS provider as a non-app process and calls `call("sendBinder", null, Bundle{BinderContainer})`.
 * If SELinux permits shell->app provider and the marshalled binder survives the hop, we have proven
 * risk #3, and the app can then issue binder IPC to the daemon with NO ADB (see [PersistDebugReceiver]).
 *
 * Mirrors Shizuku-API:
 *   RikkaApps/Shizuku-API — provider/src/main/java/rikka/shizuku/ShizukuProvider.java
 *     METHOD_SEND_BINDER = "sendBinder"; EXTRA_BINDER; call(): extras.setClassLoader(...); switch(method)
 *     -> handleSendBinder(extras): container = extras.getParcelable(EXTRA_BINDER);
 *        Shizuku.onBinderReceived(container.binder, ...)
 * The server side that calls us is RikkaApps/Shizuku — ShizukuService.sendBinderToUserApp (mirrored
 * by [BinderDebugDaemon.deliverBinderToApp]).
 *
 * Throwaway: exported is acceptable for a spike receiver. Remove with the persistserver/ package.
 */
class RecorderBinderDebugProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        AppLogger.i(TAG, "RecorderBinderDebugProvider onCreate (pid=${Process.myPid()} uid=${Process.myUid()})")
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

        val service = IPersistDebugService.Stub.asInterface(binder)
        RecorderBinderDebugHolder.onBinderReceived(service)
        AppLogger.i(
            TAG,
            "received binder uid=${Process.myUid()} pid=${Process.myPid()} alive=${binder.pingBinder()} (risk #3 PASS)"
        )

        // Clear the holder if the daemon dies so the app sees a dead channel instead of an ABE.
        runCatching {
            binder.linkToDeath({
                AppLogger.w(TAG, "daemon binder died; clearing holder")
                RecorderBinderDebugHolder.onBinderDied()
            }, 0)
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
        private const val TAG = "SCR:Binder"

        /** Provider authority registered in the manifest; the daemon resolves "<authority>". */
        const val AUTHORITY = "com.kfir.callvault.persistdebug"

        /** Mirrors ShizukuProvider.METHOD_SEND_BINDER. */
        const val METHOD_SEND_BINDER = "sendBinder"

        /** Bundle key for the [BinderContainer]; analogous to ShizukuProvider.EXTRA_BINDER. */
        const val EXTRA_BINDER = "com.kfir.callvault.persistdebug.extra.BINDER"
    }
}
