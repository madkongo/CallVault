/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds Wireless debugging's connect port by asking every loopback port in the ephemeral range (issue #65).
 *
 * adbd binds its TLS listener to port 0, so the kernel picks it from `ip_local_port_range` — 32768–60999
 * on Android. A refused loopback connect costs well under a millisecond, so the whole range is a short
 * job for a few dozen threads; only the ports that answer get the adb [AdbWireProbe] question.
 *
 * Free of Android types: the probe is a lambda, so ordering, budget and cancellation are all unit-tested.
 */
object ConnectPortScan {

    /** Linux's default ephemeral range, which Android keeps. */
    val EPHEMERAL_RANGE: IntRange = 32_768..60_999

    /**
     * How far either side of the last good port to look first. adbd usually gets a nearby port when it
     * restarts, because the kernel hands them out in sequence; Stardust uses the same window.
     */
    const val NEAR_WINDOW = 240

    /**
     * The order to ask ports in: the last good port, then outward from it (+1, −1, +2, −2 …) up to
     * [window], then the rest of [range] from the bottom. No port twice; [exclude]d and out-of-range
     * ports never. A last good port outside [range] is still asked first (adbd's port is not obliged to
     * be ephemeral), but its window is clipped to [range].
     */
    fun order(
        lastGood: Int?,
        range: IntRange = EPHEMERAL_RANGE,
        window: Int = NEAR_WINDOW,
        exclude: Set<Int> = emptySet(),
    ): IntArray {
        val seen = LinkedHashSet<Int>(range.last - range.first + 2)
        fun add(port: Int, inRangeOnly: Boolean) {
            if (port !in 1..MAX_PORT || port in exclude) return
            if (inRangeOnly && port !in range) return
            seen.add(port)
        }
        if (lastGood != null) {
            add(lastGood, inRangeOnly = false)
            for (d in 1..window) {
                add(lastGood + d, inRangeOnly = true)
                add(lastGood - d, inRangeOnly = true)
            }
        }
        for (port in range) add(port, inRangeOnly = true)
        return seen.toIntArray()
    }

    /** What a scan saw. [port] is the Wireless-debugging port, or null when none answered. */
    data class Result(
        val port: Int?,
        /** Ports asked before the scan stopped. */
        val probed: Int,
        /** Ports with anything listening, adb or not. */
        val open: Int,
        /** adbd's classic `tcpip` listeners seen on the way; never handed to the TLS connect. */
        val plainAdbPorts: List<Int>,
        val tookMs: Long,
        /** True when the budget ran out (or the scan was cancelled) before every port was asked. */
        val incomplete: Boolean,
    )

    /**
     * Asks [ports] in order on [threads] workers until one answers [PortProbe.ADB_TLS], [budgetMs] runs out,
     * or [cancelled] is set. Workers take ports in order, so the early ports are asked first; the first TLS
     * answer wins. Returns by the budget even if a probe is stuck: workers are daemon threads, left to end
     * on their own probe timeouts.
     */
    fun scan(
        ports: IntArray,
        threads: Int,
        budgetMs: Long,
        now: () -> Long = System::currentTimeMillis,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        probe: (Int) -> PortProbe,
    ): Result {
        val startedAt = now()
        val deadline = startedAt + budgetMs
        val next = AtomicInteger(0)
        val probed = AtomicInteger(0)
        val open = AtomicInteger(0)
        val found = AtomicReference<Int?>(null)
        val plain = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val stop = AtomicBoolean(false)

        fun stopped() = stop.get() || cancelled.get() || found.get() != null || now() >= deadline

        val workers = (1..threads.coerceIn(1, ports.size.coerceAtLeast(1))).map { n ->
            Thread {
                while (!stopped()) {
                    val i = next.getAndIncrement()
                    if (i >= ports.size) return@Thread
                    val port = ports[i]
                    val answer = runCatching { probe(port) }.getOrDefault(PortProbe.CLOSED)
                    probed.incrementAndGet()
                    if (answer != PortProbe.CLOSED) open.incrementAndGet()
                    when (answer) {
                        PortProbe.ADB_TLS -> found.compareAndSet(null, port)
                        PortProbe.ADB_PLAIN -> plain.add(port)
                        else -> Unit
                    }
                }
            }.apply { isDaemon = true; name = "cv-port-scan-$n" }
        }
        workers.forEach { it.start() }
        for (worker in workers) {
            val remaining = deadline - now()
            if (remaining <= 0) break
            runCatching { worker.join(remaining) }
        }
        stop.set(true)
        val port = found.get()
        return Result(
            port = port,
            probed = probed.get(),
            open = open.get(),
            plainAdbPorts = plain.sorted(),
            tookMs = now() - startedAt,
            incomplete = port == null && next.get() < ports.size,
        )
    }

    private const val MAX_PORT = 65_535
}

/**
 * Remembers a scan that found nothing, so a phone whose adbd has no TLS port is not scanned again and again
 * (the #65 report retried a connect every few seconds for 25 minutes).
 *
 * A failed scan holds for one Wireless-debugging session: until the switch changes ([reset]), the Wi-Fi
 * network changes (a different [session] key), or [retryAfterMs] passes — the last as a backstop in case a
 * switch change went unseen. Thread-safe.
 */
class ConnectPortScanGate(private val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS) {
    private var failedSession: String? = null
    private var failedAtMs = 0L
    private var skipLogged = false

    /** Whether a scan may run now in [session]. */
    @Synchronized
    fun mayScan(session: String, nowMs: Long): Boolean {
        val failed = failedSession ?: return true
        if (failed != session || nowMs - failedAtMs >= retryAfterMs) {
            clear()
            return true
        }
        return false
    }

    /** A scan in [session] found nothing. */
    @Synchronized
    fun recordFailure(session: String, nowMs: Long) {
        failedSession = session
        failedAtMs = nowMs
        skipLogged = false
    }

    /** True the first time a skip is reported after a failure, so the log says it once, not every retry. */
    @Synchronized
    fun shouldLogSkip(): Boolean {
        if (skipLogged) return false
        skipLogged = true
        return true
    }

    /** Wireless debugging changed, or a connect succeeded: the next miss may scan again. */
    @Synchronized
    fun reset() = clear()

    private fun clear() {
        failedSession = null
        failedAtMs = 0L
        skipLogged = false
    }

    companion object {
        const val DEFAULT_RETRY_AFTER_MS = 30 * 60_000L
    }
}
