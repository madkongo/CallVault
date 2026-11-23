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
        val message = e.message?.takeIf { it.isNotBlank() }?.take(MAX_MESSAGE) ?: "(no message)"
        return "${e.javaClass.simpleName}: $message"
    }

    /** What `adb_wifi_enabled` read as right after we wrote 0; null when the read threw. */
    fun readBack(value: Int?): String = "adb_wifi_enabled read-back=${value ?: "unreadable"}"

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

    @Synchronized
    fun admit(key: String, always: Boolean = false): Boolean {
        if (always) {
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
