/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.baba.callvault.utils.AppLogger
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Runs inside the recorder daemon: sends the daemon's binder to the app again whenever the app's process starts
 * (BabApps item 115).
 *
 * Without it the binder is delivered once, when the daemon is launched. If the app is stopped (Active apps -> Stop)
 * the daemon survives, but the next call starts a new app process that has no binder, so it killed the live daemon
 * and launched another over ADB, which cannot happen away from Wi-Fi: the call was lost with a recorder running.
 *
 * Mirrors Shizuku's `BinderSender` (RikkaApps/Shizuku, server/.../BinderSender.java): the server registers an
 * activity-manager uid observer and, when the client app's uid becomes active, calls its provider's `sendBinder`.
 * Shizuku subclasses the hidden `IUidObserver.Stub`; this runs from a bare `app_process` with no hidden-API stubs, so
 * the observer is a [Binder] answering the interface's one transaction we asked for (see [register]) and a [Proxy]
 * of the interface that hands the activity manager that binder. Everything is reflection, and any failure only means
 * the app falls back to launching over ADB, as it did before.
 */
internal object AppStartBinderSender {

    private const val TAG = "CV:RecorderServer"
    private const val OBSERVER_DESCRIPTOR = "android.app.IUidObserver"

    /** ActivityManager.UID_OBSERVER_ACTIVE: ask only for "a uid became active", so every call we get carries a uid first. */
    private const val UID_OBSERVER_ACTIVE_FALLBACK = 1 shl 3

    /** ActivityManager.PROCESS_STATE_UNKNOWN: no process-state cut point. */
    private const val NO_CUTPOINT = -1

    /** A hand-over starts the app's process itself, which fires the observer again; ignore that echo. */
    private const val ECHO_WINDOW_MS = 4_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cv-binder-sender").apply { isDaemon = true } }

    @Volatile
    private var lastSentAt = 0L

    /** Keeps the observer from being collected: the activity manager only holds a weak reference to our side. */
    @Volatile
    private var observer: Binder? = null

    /**
     * Starts watching. Returns whether the observer was registered; the outcome is logged either way, so the log
     * says whether this daemon can hand its binder over again.
     */
    fun register(binder: IBinder, authority: String, apkPath: String): Boolean = try {
        val appUid = resolveAppUid(authority)
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            ?: error("ActivityManager.getService() returned null")
        val observerBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Only onUidActive(int uid) is subscribed, and every IUidObserver call starts with the uid.
                return try {
                    data.enforceInterface(OBSERVER_DESCRIPTOR)
                    if (data.readInt() == appUid) onAppActive(binder, authority, apkPath)
                    true
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "uid observer call ignored: ${t.message}")
                    super.onTransact(code, data, reply, flags)
                }
            }
        }
        val observerType = Class.forName(OBSERVER_DESCRIPTOR)
        // The activity manager's proxy only asks the argument for asBinder(), so a Proxy of the interface will do.
        val observerProxy = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { _, method, _ ->
            if (method.name == "asBinder") observerBinder else null
        }
        val flags = runCatching { Class.forName("android.app.ActivityManager").getField("UID_OBSERVER_ACTIVE").getInt(null) }
            .getOrDefault(UID_OBSERVER_ACTIVE_FALLBACK)
        val register = am.javaClass.methods.firstOrNull { it.name == "registerUidObserver" && it.parameterTypes.size == 4 }
            ?: error("registerUidObserver(IUidObserver,int,int,String) not found")
        register.invoke(am, observerProxy, flags, NO_CUTPOINT, "com.android.shell")
        observer = observerBinder
        AppLogger.i(TAG, "Will hand the binder to the app again whenever its process starts (uid=$appUid)")
        true
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Cannot watch for the app's process start; after the app is stopped this recorder cannot be reused: ${t.message}", t)
        false
    }

    private fun onAppActive(binder: IBinder, authority: String, apkPath: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSentAt < ECHO_WINDOW_MS) return
        lastSentAt = now
        worker.execute {
            val ok = runCatching { BinderDelivery.redeliverBinderToApp(binder, authority, apkPath) }.getOrDefault(false)
            AppLogger.i(TAG, "App process started: handed the binder over again, delivered=$ok")
        }
    }

    /** The uid of the app that owns [authority], from the package manager: the one uid whose start we care about. */
    private fun resolveAppUid(authority: String): Int {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val pmBinder = serviceManager.getMethod("getService", String::class.java).invoke(null, "package") as? IBinder
            ?: error("no package service")
        val pm = Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, pmBinder)
        val resolve = pm.javaClass.methods.firstOrNull { it.name == "resolveContentProvider" && it.parameterTypes.size == 3 }
            ?: error("resolveContentProvider not found")
        // The flags argument is an int up to Android 12 and a long from 13.
        val flags: Any = if (resolve.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        val info = resolve.invoke(pm, authority, flags, 0) ?: error("no provider for $authority")
        val appInfo = info.javaClass.getField("applicationInfo").get(info) ?: error("provider has no applicationInfo")
        return appInfo.javaClass.getField("uid").getInt(appInfo)
    }
}
