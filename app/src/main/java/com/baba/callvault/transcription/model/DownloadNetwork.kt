/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.NetworkType

/**
 * Which networks one model download may use. Chosen per download and never remembered.
 *
 * Downloads used to be Wi-Fi only, always. On mobile data they then sat at "Waiting for Wi-Fi" with
 * nothing saying why, so a tap on mobile data now asks (see [DownloadNetworkChoice]).
 */
enum class DownloadNetwork(val networkType: NetworkType) {
    /** The old and default behaviour: the download starts by itself once the phone is on Wi-Fi. */
    WIFI_ONLY(NetworkType.UNMETERED),

    /** The user said yes to mobile data for this one download. */
    ANY(NetworkType.CONNECTED)
}

/**
 * Whether tapping Download should ask about mobile data first.
 *
 * The decision is a pure function of two facts so it can be tested without a phone; [current]
 * reads those facts from [ConnectivityManager].
 */
object DownloadNetworkChoice {

    enum class Decision {
        /** On a metered network with internet: ask before spending the user's data. */
        ASK,

        /** On Wi-Fi, or offline: queue for an unmetered network as before, without asking. */
        QUEUE_UNMETERED
    }

    /**
     * Offline is not a reason to ask: there is nothing to download over yet, and queueing for Wi-Fi
     * is what the user would get from "wait" anyway.
     */
    fun decide(hasInternet: Boolean, isMetered: Boolean): Decision =
        if (hasInternet && isMetered) Decision.ASK else Decision.QUEUE_UNMETERED

    /** The decision for the phone's network right now. */
    fun current(context: Context): Decision {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return Decision.QUEUE_UNMETERED
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return Decision.QUEUE_UNMETERED
        return decide(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            // The same capability WorkManager's UNMETERED constraint waits for, so "not metered"
            // here means the Wi-Fi-only download would start straight away.
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
