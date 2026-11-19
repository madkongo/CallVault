/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wi-Fi coming and going, for Home's status card (review C24).
 *
 * Registers with Android only while collected and unregisters when the collector goes away. The first version
 * registered from HomeViewModel's `init` into a property declared below it; Android calls onAvailable at once
 * when Wi-Fi is up, before that property existed, and Home crashed on every open (OP9, rf2, 2026-11-19).
 */
object WifiChanges {

    private const val TAG = "WifiChanges"

    fun of(cm: ConnectivityManager?): Flow<Unit> = callbackFlow {
        if (cm == null) {
            AppLogger.w(TAG, "No connectivity service: Home's status card will not follow Wi-Fi changes")
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val registered = runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { AppLogger.w(TAG, "Could not watch Wi-Fi for the status card: ${it.message}") }
            .isSuccess
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { cm.unregisterNetworkCallback(callback) }
                .onFailure { AppLogger.w(TAG, "Could not stop watching Wi-Fi: ${it.message}") }
        }
    }
}
