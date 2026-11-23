/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Pure helpers for the always-on transport trace (a reporter build must never come back unexplained):
 * wording, de-duplication and rate limiting. Kept free of Android so they can be tested directly.
 */
object TraceText {
    private const val MAX_MESSAGE = 200

    /** Why a connect did not work: `Class: message`, or an explicit "no exception" when it simply returned false. */
    fun failureCause(e: Throwable?): String {
        if (e == null) return "returned false (no exception)"
        val message = e.message?.takeIf { it.isNotBlank() }?.let { scrubHosts(it).take(MAX_MESSAGE) } ?: "(no message)"
        return "${e.javaClass.simpleName}: $message"
    }

    /** What `adb_wifi_enabled` read as right after we wrote 0; null when the read threw. */
    fun readBack(value: Int?): String = "adb_wifi_enabled read-back=${value ?: "unreadable"}"

    /** Placeholder for a LAN address in an always-on line (these lines end up in reports people attach to public issues). */
    const val LAN_PLACEHOLDER = "<lan-ip>"

    private val IPV4 = Regex("(?<![0-9A-Za-z.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9A-Za-z])")
    private val IPV6 = Regex("(?<![0-9A-Za-z:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Za-z:])")

    /**
     * Replaces IPv4 and IPv6 literals with [LAN_PLACEHOLDER]; loopback (127.0.0.1, ::1) is kept because "loopback or
     * not" is exactly what a connect trace needs. Ports and everything else stay. A colon group counts as IPv6 only
     * with "::" or all eight groups, so clock times such as 12:34:56 are left alone.
     */
    fun scrubHosts(text: String): String {
        val v4 = IPV4.replace(text) { if (it.value == "127.0.0.1") it.value else LAN_PLACEHOLDER }
        return IPV6.replace(v4) {
            val v = it.value
            val looksV6 = v.contains("::") || v.count { c -> c == ':' } == 7
            if (!looksV6 || v == "::1") v else LAN_PLACEHOLDER
        }
    }

    /** A raw system-property value, with blank made visible. */
    fun raw(value: String): String = value.trim().ifEmpty { "(empty)" }
}

/**
 * Decides whether a trace line is written: not when its [admit] key equals the previous one, and not more than
 * [maxPerWindow] lines per [windowMs], so a stuck phone cannot flood the timeline. [always] lines (cycle start and
 * end, which the revival back-off already spaces out) skip both rules.
 */
class TraceGate(
    private val now: () -> Long,
    private val maxPerWindow: Int = DEFAULT_MAX_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private var lastKey: String? = null
    private var windowStart = Long.MIN_VALUE
    private var inWindow = 0
    private var alwaysWindowStart = Long.MIN_VALUE
    private var alwaysInWindow = 0

    /**
     * Keys are compared with every run of digits folded to `#`, so a countdown, a port or a duration in the key can
     * never defeat the de-duplication. The line text may carry the numbers; only the key is normalised.
     */
    @Synchronized
    fun admit(rawKey: String, always: Boolean = false): Boolean {
        val key = DIGITS.replace(rawKey, "#")
        if (always) {
            // Not de-duplicated, but still bounded: a stuck phone must not be able to flood through these either.
            val t = now()
            if (alwaysWindowStart == Long.MIN_VALUE || t - alwaysWindowStart > ALWAYS_WINDOW_MS) {
                alwaysWindowStart = t
                alwaysInWindow = 0
            }
            if (alwaysInWindow >= ALWAYS_MAX_PER_WINDOW) return false
            alwaysInWindow++
            lastKey = key
            return true
        }
        if (key == lastKey) return false
        val t = now()
        if (windowStart == Long.MIN_VALUE || t - windowStart > windowMs) {
            windowStart = t
            inWindow = 0
        }
        if (inWindow >= maxPerWindow) return false
        inWindow++
        lastKey = key
        return true
    }

    companion object {
        private val DIGITS = Regex("\\d+")
        const val ALWAYS_MAX_PER_WINDOW = 30
        const val ALWAYS_WINDOW_MS = 60 * 60_000L
        const val DEFAULT_MAX_PER_WINDOW = 20
        const val DEFAULT_WINDOW_MS = 10 * 60_000L
    }
}

/** True on the first reading of each down period, false while it stays down and while it is up. */
class OncePerDownPeriod {
    private var wasDown = false

    @Synchronized
    fun shouldWrite(down: Boolean): Boolean {
        val first = down && !wasDown
        wasDown = down
        return first
    }
}

/** Says yes the first time it sees each key (bounded), so a read that keeps throwing is logged once, not every tick. */
class OnceLog(private val maxKeys: Int = 32) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(key: String): Boolean {
        if (key in seen) return false
        if (seen.size >= maxKeys) seen.remove(seen.first())
        seen.add(key)
        return true
    }
}
