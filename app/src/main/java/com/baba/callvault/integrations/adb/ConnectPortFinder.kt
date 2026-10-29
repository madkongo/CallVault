/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger

/**
 * Finds Wireless debugging's connect port, fully automatically (issue #65).
 *
 * The chain: the last port that worked → mDNS → a search of the loopback ports. Some ROMs never advertise
 * `_adb-tls-connect._tcp` (adbd announces it through the old `mdnsd`, which they do not run) while pairing,
 * advertised by system_server, works — so mDNS alone left those phones with no recorder at all.
 *
 * - **Last good port** first, with one quick probe: it holds until Wireless debugging or adbd restarts, so on
 *   most connects it saves the mDNS wait; when it is stale the probe is refused in about a millisecond.
 * - **mDNS** as before, with its timeout unchanged.
 * - **Loopback search** only when mDNS found nothing, adbd is not stopped, and no search has already failed in
 *   this Wireless-debugging session ([ConnectPortScanGate]). Bounded by [SCAN_BUDGET_MS].
 *
 * Callers have already made sure Wireless debugging reads on.
 */
internal object ConnectPortFinder {
    private const val TAG = "CV:ConnectPort"
    private const val LOOPBACK = "127.0.0.1"

    /** Workers for the search. Loopback connects are cheap; this is about thread cost, not the network. */
    private const val SCAN_THREADS = 48

    /** The search gives up after this, found or not. 📐 Not measured: ~28k refused loopback connects / 48 threads. */
    private const val SCAN_BUDGET_MS = 5_000L

    /** A loopback connect either succeeds or is refused at once; this only bounds a wedged stack. */
    private const val SCAN_CONNECT_TIMEOUT_MS = 150
    private const val SCAN_READ_TIMEOUT_MS = 500

    /** The quick look at the last good port before mDNS. */
    private const val LAST_GOOD_CONNECT_TIMEOUT_MS = 200
    private const val LAST_GOOD_READ_TIMEOUT_MS = 400

    enum class Source(val label: String) {
        LAST_GOOD("the last good port"),
        MDNS("mDNS"),
        SCAN("loopback search"),
    }

    data class Found(val port: Int, val source: Source)

    private val gate = ConnectPortScanGate()

    /** One line for the debug report: how the last search in this process ended. */
    @Volatile var lastOutcome: String = "not run in this session"
        private set

    /**
     * Finds the port, or null. Blocking: up to the mDNS timeout plus [SCAN_BUDGET_MS]; call off the main thread.
     *
     * @param excludePorts adbd's classic `tcpip` listeners (ours and whatever `service.adb.tcp.port` says):
     *   they answer the probe too, but they are not the Wireless-debugging port.
     */
    fun find(context: Context, mdnsTimeoutMs: Long, excludePorts: Set<Int>, adbd: AdbdState): Found? {
        val prefs = AppPreferences(context)
        val testSearch = prefs.isDebugSkipMdnsConnect() && prefs.isLoggingEnabled()
        val lastGood = prefs.getLastAdbConnectPort()?.takeIf { it !in excludePorts }
        if (testSearch) {
            AppLogger.i(TAG, "Debug switch on: skipping the last good port and mDNS, searching loopback ports")
        } else {
            if (lastGood != null && probe(lastGood, LAST_GOOD_CONNECT_TIMEOUT_MS, LAST_GOOD_READ_TIMEOUT_MS) == PortProbe.ADB_TLS) {
                return found(lastGood, Source.LAST_GOOD, "")
            }
            // mDNS's port needs no second check: AdbMdns already accepts only a port something listens on at
            // 127.0.0.1 (MdnsServiceVerdict, the bind-must-fail probe), and the last good port was probed above.
            AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, mdnsTimeoutMs)?.let {
                return found(it, Source.MDNS, "")
            }
        }
        // Which failure this is: mdnsd running means it advertises but something drops it; stopped or "(unset)"
        // (no such service on this ROM) means adbd had nobody to advertise through — #65's suspected cause.
        val mdnsd = runCatching { AdbShell.mdnsdStateForReport() }.getOrDefault("?")
        if (adbd == AdbdState.STOPPED) {
            val outcome = "mDNS found nothing (init.svc.mdnsd: $mdnsd); adbd is stopped, so no loopback search"
            if (outcome != lastOutcome) AppLogger.w(TAG, outcome) // once per change, not on every retry
            lastOutcome = outcome
            return null
        }
        val session = sessionKey(context)
        val now = SystemClock.elapsedRealtime()
        if (!testSearch && !gate.mayScan(session, now)) {
            lastOutcome = "mDNS found nothing (init.svc.mdnsd: $mdnsd); loopback search skipped (it already failed in this Wireless-debugging session)"
            if (gate.shouldLogSkip()) AppLogger.i(TAG, "mDNS found no connect port (init.svc.mdnsd: $mdnsd); not searching loopback again: it found nothing earlier in this Wireless-debugging session")
            return null
        }
        return search(if (testSearch) null else lastGood, excludePorts, session, now, mdnsd, testSearch)
    }

    /** A TLS connect on [port] worked: remember it, and let the next miss search again. */
    fun recordSuccess(context: Context, port: Int) {
        runCatching { AppPreferences(context).setLastAdbConnectPort(port) }
        gate.reset()
    }

    /** Wireless debugging was switched (by anyone): adbd's port is new, so a failed search no longer holds. */
    fun onWirelessDebuggingChanged() = gate.reset()

    private fun search(
        lastGood: Int?,
        excludePorts: Set<Int>,
        session: String,
        startedAt: Long,
        mdnsd: String,
        testSearchActive: Boolean,
    ): Found? {
        val ports = ConnectPortScan.order(lastGood, exclude = excludePorts)
        val why = if (testSearchActive) "Debug switch" else "mDNS found no connect port"
        AppLogger.i(TAG, "$why (init.svc.mdnsd: $mdnsd); searching ${ports.size} loopback ports" +
            (lastGood?.let { ", starting at the last good port :$it" } ?: ""))
        val result = ConnectPortScan.scan(ports, SCAN_THREADS, SCAN_BUDGET_MS) {
            probe(it, SCAN_CONNECT_TIMEOUT_MS, SCAN_READ_TIMEOUT_MS)
        }
        val detail = "${result.open} ports open, ${result.probed} asked, took ${result.tookMs} ms" +
            (if (result.plainAdbPorts.isNotEmpty()) ", classic adb listeners skipped: ${result.plainAdbPorts}" else "")
        result.port?.let { return found(it, Source.SCAN, " ($detail)") }
        gate.recordFailure(session, startedAt)
        val failure = if (result.incomplete) "budget of ${SCAN_BUDGET_MS} ms ran out" else "no port answered as Wireless debugging"
        lastOutcome = "mDNS (init.svc.mdnsd: $mdnsd) and loopback search found nothing ($failure; $detail)"
        AppLogger.w(TAG, "Loopback search found no Wireless-debugging port: $failure ($detail). Not searching again until Wireless debugging or the Wi-Fi network changes")
        return null
    }

    private fun found(port: Int, source: Source, detail: String): Found {
        lastOutcome = "connect port :$port from ${source.label}$detail"
        // Every source is worth a line except mDNS, whose own "Accepted …" line already says it.
        if (source != Source.MDNS) AppLogger.i(TAG, "Connect port :$port from ${source.label}$detail")
        return Found(port, source)
    }

    private fun probe(port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): PortProbe =
        AdbWireProbe.probe(LOOPBACK, port, connectTimeoutMs, readTimeoutMs)

    /** The Wi-Fi network(s) the phone is on: Wireless debugging's port belongs to one of these sessions. */
    @Suppress("DEPRECATION") // allNetworks: same reason as WifiState.of.
    private fun sessionKey(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "?"
        cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .map { it.toString() }
            .sorted()
            .joinToString(",")
    }.getOrDefault("?")
}
