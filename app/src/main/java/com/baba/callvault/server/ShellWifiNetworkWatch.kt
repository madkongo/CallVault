/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder

/**
 * The recorder's Wi-Fi watch (review N15): a `ConnectivityManager.NetworkCallback` for Wi-Fi, called on [handler].
 *
 * A callback, not a PendingIntent: ConnectivityService releases a PendingIntent request 5 s after its first
 * delivery (`onSendFinished` -> `releasePendingNetworkRequestWithDelay`, android14-release), so that form hears one
 * join only. A callback stays registered until unregistered or its process dies, and the app arms it again on every
 * recorder start.
 */
class ShellWifiNetworkWatch(
    private val handler: Handler,
    private val connectivity: () -> ConnectivityManager = ShellConnectivity::create,
) : WifiNetworkWatch {

    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onAvailable: (String) -> Unit) {
        if (callback != null) return
        val cm = connectivity()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable(network.toString())
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            cb,
            handler,
        )
        manager = cm
        callback = cb
    }

    /**
     * Unregisters, and forgets the callback only once Android no longer holds it: if the unregister fails for another
     * reason it is kept, so a later [start] does not register a second one beside it. Throws that failure.
     */
    @Synchronized
    override fun stop() {
        val cb = callback ?: return
        try {
            manager?.unregisterNetworkCallback(cb)
        } catch (notRegistered: IllegalArgumentException) {
            // Android says it does not hold it: nothing left to keep.
        }
        callback = null
        manager = null
    }
}

/**
 * A ConnectivityManager for the shell-uid recorder, which has no Context of its own that names the shell: built on
 * the `connectivity` service binder with a Context that presents `com.android.shell`, the package of uid 2000.
 * Registering a callback only reads the op package and attribution tag from it (`ConnectivityManager
 * .sendRequestForNetwork`, android14-release).
 */
object ShellConnectivity {

    private const val SHELL_PACKAGE = "com.android.shell"

    fun create(): ConnectivityManager {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder
            ?: throw IllegalStateException("no connectivity service")
        val serviceInterface = Class.forName("android.net.IConnectivityManager")
        val service = Class.forName("android.net.IConnectivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        return ConnectivityManager::class.java
            .getConstructor(Context::class.java, serviceInterface)
            .newInstance(ShellIdentityContext(systemContextOrNull()), service)
    }

    /** The process's system context when there is one (Shizuku's host has an ActivityThread); null otherwise. */
    private fun systemContextOrNull(): Context? = runCatching {
        val atCls = Class.forName("android.app.ActivityThread")
        val thread = atCls.getMethod("currentActivityThread").invoke(null) ?: return null
        atCls.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as? Context
    }.getOrNull()

    /** Presents the shell's package; anything else goes to [base] when there is one. */
    private class ShellIdentityContext(base: Context?) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
        override fun getSystemService(name: String): Any? = baseContext?.getSystemService(name)
    }
}
