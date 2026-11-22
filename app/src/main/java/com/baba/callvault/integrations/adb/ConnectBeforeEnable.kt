/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Rules for the quick look at a connect port that runs when `adb_wifi_enabled` reads off or absent, before
 * anything is written (C115, GitHub #40).
 *
 * On Android 17 the read can be 0 to an app while Wireless debugging is on, and the enable write needs a
 * grant CallVault only receives after its first connect — so trusting the read deadlocked a Pixel 7. The
 * look never writes the switch and never records ownership of it, so a user who really switched Wireless
 * debugging off gets the same outcome as before: nothing answers, and the existing enable logic (with its
 * user-off rules) runs.
 */
internal object ConnectBeforeEnable {
    private const val LOOPBACK = "127.0.0.1"

    /** How long the look waits for `_adb-tls-connect` before giving up. */
    const val LOOK_MDNS_MS = 2_000L

    /**
     * A stopped adbd answers nothing, and Wireless debugging serves nothing without Wi-Fi, so in both there is
     * nothing to look for and the 2 s of mDNS would only delay the failure that follows.
     */
    fun shouldLook(adbd: AdbdState, wifi: WifiState): Boolean =
        adbd != AdbdState.STOPPED && wifi != WifiState.NOT_CONNECTED

    /** Loopback first; the address mDNS resolved only as a fallback, and only when it differs. */
    fun hostsToTry(mdnsHost: String?): List<String> =
        listOfNotNull(LOOPBACK, mdnsHost?.takeIf { it.isNotBlank() && it != LOOPBACK })

    /**
     * One connect budget for the whole look, shared by every host (M3): two 8 s handshakes in a row would hold
     * the connect lock for 16 s on top of the mDNS window.
     */
    const val CONNECT_BUDGET_MS = 8_000L

    /** After an mDNS look found nothing, it is not repeated for this long in the same Wi-Fi session (M2). */
    const val MDNS_MISS_RETRY_MS = 5 * 60_000L

    /**
     * Dials [hostsToTry] in order, each with whatever is left of [totalBudgetMs]; returns the host that connected, or
     * null. [connect] gets the host and its budget. The resolved address is only ever one mDNS accepted, and
     * [MdnsServiceVerdict] drops any that is not one of this phone's own addresses (L2).
     */
    fun tryHosts(
        mdnsHost: String?,
        port: Int,
        totalBudgetMs: Long,
        now: () -> Long,
        connect: (host: String, budgetMs: Long) -> Boolean,
        log: (String) -> Unit,
    ): String? {
        val startedAt = now()
        for (host in hostsToTry(mdnsHost)) {
            val left = totalBudgetMs - (now() - startedAt)
            if (left <= 0) {
                log("connect budget of ${totalBudgetMs}ms used up; not trying $host:$port")
                return null
            }
            if (connect(host, left)) return host
            log("connect to $host:$port did not complete")
        }
        return null
    }
}
