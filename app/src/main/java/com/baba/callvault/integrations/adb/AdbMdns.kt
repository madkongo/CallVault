/*
 * AdbMdns — mDNS discovery for ADB wireless-debugging services.
 *
 * Ported from RikkaApps/Shizuku (Apache-2.0):
 *   manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
 * Adapted: LiveData Observer<Int> → a plain (Int) -> Unit callback, and namespaced
 * into the CallVault production integrations package. Behaviour (NsdManager discovery + resolve +
 * local-interface match + port-availability probe) is unchanged.
 *
 * KEY POINT vs libadb-android's bundled AdbMdns: the service type passed to
 * NsdManager is the FULL form `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`
 * (with leading underscore and `._tcp`). libadb's constants are the bare
 * `adb-tls-pairing`, which NsdManager never matches — that is why discovery found
 * nothing in the first spike iteration.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.baba.callvault.utils.AppLogger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * Discovers an ADB mDNS service of [serviceType] and reports the resolved port via
 * [onPort]. A port of `-1` means the previously-found service was lost.
 *
 * Call [start] to begin discovery and [stop] when done. Discovery should be running
 * BEFORE the pairing service appears (i.e. before/while the user opens the phone's
 * "Pair device with pairing code" dialog), which is why callers keep it alive in a
 * service rather than firing it once on demand.
 */
class AdbMdns(
    context: Context,
    private val serviceType: String,
    /** True while a run of failed searches is being kept quiet: the "Discovering" line is skipped too. */
    private val quiet: Boolean = false,
    private val onPort: (Int) -> Unit,
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            if (!quiet) AppLogger.d(TAG, "Discovering $serviceType")
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        AppLogger.d(TAG, "Found ${info.serviceName} ($serviceType); resolving")
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) onPort(-1)
    }

    /**
     * Takes the service, or says why not.
     *
     * Every outcome is logged, including the drops. This used to be one silent `if`, which is fine on
     * a phone you are holding and useless on a reporter's — see [MdnsServiceVerdict].
     */
    @Suppress("DEPRECATION")
    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (!running) return
        val host = resolvedService.host?.hostAddress
        val port = resolvedService.port
        val verdict = MdnsServiceVerdict.of(
            hostAddress = host,
            isOneOfOurAddresses = { isOneOfOurAddresses(host) },
            isLoopbackPortTaken = { isLoopbackPortTaken(port) },
        )
        if (verdict != MdnsServiceVerdict.ACCEPTED) {
            AppLogger.w(TAG, "Dropped ${resolvedService.serviceName} at ${host ?: "no address"}:$port — $verdict")
            return
        }
        AppLogger.i(TAG, "Accepted ${resolvedService.serviceName} at $host:$port")
        serviceName = resolvedService.serviceName
        onPort(port)
    }

    /** Whether the resolved address belongs to this phone — i.e. it is our adb, not the neighbour's. */
    private fun isOneOfOurAddresses(hostAddress: String?): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .any { networkInterface ->
                networkInterface.inetAddresses.asSequence().any { it.hostAddress == hostAddress }
            }
    }.getOrElse {
        AppLogger.w(TAG, "Could not enumerate this device's addresses: ${it.message}")
        false
    }

    /**
     * Whether something is already listening on `127.0.0.1:port` — which for an advertised adb port
     * means a genuine local `adbd`. The probe is a bind that we EXPECT to fail.
     *
     * A ROM whose `adbd` binds only the Wi-Fi address makes this bind succeed, and the real service
     * then reads as fake ([MdnsServiceVerdict.NOTHING_LISTENING_ON_LOOPBACK]).
     */
    private fun isLoopbackPortTaken(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    private class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = adbMdns.onDiscoveryStart()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLogger.w(TAG, "Discovery could not start for $serviceType (error $errorCode)")
        }
        override fun onDiscoveryStopped(serviceType: String) = adbMdns.onDiscoveryStop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLogger.w(TAG, "Discovery could not stop for $serviceType (error $errorCode)")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = adbMdns.onServiceFound(serviceInfo)
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = adbMdns.onServiceLost(serviceInfo)
    }

    @Suppress("DEPRECATION")
    private class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            AppLogger.w(TAG, "Could not resolve ${nsdServiceInfo.serviceName} (error $i)")
        }
        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) =
            adbMdns.onServiceResolved(nsdServiceInfo)
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "CV:AdbMdns"

        /** A failed search is reported at most this often while it keeps failing (issue #65's log). */
        private const val NOTHING_FOUND_LOG_INTERVAL_MS = 5 * 60_000L
        private val nothingFound = java.util.concurrent.ConcurrentHashMap<String, RepeatedWarning>()

        /**
         * Blocks (up to [timeoutMs]) until an mDNS service of [serviceType] is found,
         * returning its port or null on timeout. Must be called off the main thread.
         */
        fun discoverPort(context: Context, serviceType: String, timeoutMs: Long): Int? {
            val portRef = java.util.concurrent.atomic.AtomicInteger(-1)
            val latch = java.util.concurrent.CountDownLatch(1)
            val warning = nothingFound.getOrPut(serviceType) { RepeatedWarning(NOTHING_FOUND_LOG_INTERVAL_MS) }
            val quiet = warning.isQuiet(android.os.SystemClock.elapsedRealtime())
            val mdns = AdbMdns(context, serviceType, quiet) { port ->
                if (port > 0) {
                    portRef.set(port)
                    latch.countDown()
                }
            }
            mdns.start()
            return try {
                if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    // The line the reporter of #23 needed and did not have: the search ended with
                    // nothing, which is a different failure from a service found and rejected (logged
                    // above) and from a refused connection (logged by the caller).
                    // Reported once per few minutes while it keeps failing, with the count of quiet repeats.
                    warning.next(android.os.SystemClock.elapsedRealtime())?.let { repeats ->
                        val more = if (repeats > 0) " ($repeats more times since the last report)" else ""
                        AppLogger.w(TAG, "No $serviceType service accepted within ${timeoutMs}ms$more")
                    }
                    null
                } else {
                    warning.reset()
                    portRef.get().takeIf { it > 0 }
                }
            } finally {
                mdns.stop()
            }
        }
    }
}
