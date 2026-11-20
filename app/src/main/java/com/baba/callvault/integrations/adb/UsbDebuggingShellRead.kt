/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import com.baba.callvault.server.RecorderConnection

/**
 * USB debugging as the recorder (shell uid) reads it — the one reader Android 17's redaction never lies to.
 *
 * Why: with Wireless debugging on, the app's own read cannot tell "USB debugging off" from "redacted", so it said
 * UNKNOWN. On the Honor BVL-N59 (2026-11-04) USB debugging was off for two hours, recording hung on Wi-Fi alone, and
 * the "paused off Wi-Fi" notice and its "Turn USB debugging on" button never showed — while the report's own shell
 * read said `adb_enabled as shell: 0`. [UsbDebuggingPolicy] now takes this as the answer when it is fresh.
 *
 * Refreshed off the main thread (it starts `settings get` in the recorder — a fork+exec charged to "Android System"),
 * on every change of the switch, on Wireless debugging changing, and on keep-alive ticks only where
 * [UsbDebuggingPolicy.shellReadNeed] says nothing cheaper answers. Where R1 answers (adbd up, Wireless debugging off)
 * the tick records that proof instead ([noteProvenOnByR1]), with the same freshness, so a brief adbd stop reads ON as
 * the last per-tick shell read did. Forgotten only on a switch change; otherwise it simply expires after [FRESH_MS].
 */
object UsbDebuggingShellRead {

    private const val FRESH_MS = 2 * 60_000L

    /** Where a value came from: the recorder's read, or R1's proof (adbd up with Wireless debugging off). */
    enum class Source { SHELL, R1 }

    private class Reading(val on: Boolean, val atMs: Long, val source: Source)

    @Volatile private var reading: Reading? = null

    /** Bumped by every [forget], i.e. every switch change. A proof or read begun before one is dropped. */
    @Volatile private var generation = 0L

    /** The last value if recent, else null (unknown). */
    fun current(now: Long = System.currentTimeMillis()): Boolean? = reading?.takeIf { now - it.atMs <= FRESH_MS }?.on

    fun generation(): Long = generation

    /** A switch changed: what was read or proven described the old switches. */
    @Synchronized
    fun forget() {
        reading = null
        generation++
    }

    /**
     * R1 proved USB debugging on (setting 0, adbd up, Wireless debugging off). Kept as a shell read would be, so a brief
     * adbd stop before it expires still answers ON. Dropped if a switch changed since [startedIn].
     */
    @Synchronized
    fun noteProvenOnByR1(startedIn: Long, now: Long = System.currentTimeMillis()) {
        if (startedIn != generation) return
        reading = Reading(true, now, Source.R1)
    }

    /** Reads it again through the recorder. Blocking; never on the main thread. Null when it cannot be read. */
    fun refresh(now: Long = System.currentTimeMillis()): Boolean? {
        val service = RecorderConnection.service ?: return null
        return refreshWith(now) { service.diagnosticDump("setting_adb_enabled", null) }
    }

    /** [refresh] with the read itself passed in, so the bookkeeping is testable without a recorder. */
    internal fun refreshWith(now: Long, read: () -> String?): Boolean? {
        val on = parse(runCatching(read).getOrNull()?.trim()) ?: return null
        synchronized(this) { reading = Reading(on, now, Source.SHELL) }
        return on
    }

    fun parse(text: String?): Boolean? = when (text) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
