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

    /** Why there is no value, for the transport timeline (review S4). Cleared by any stored value. */
    private enum class Miss(val label: String) {
        NOT_NEEDED("skip"),
        NO_RECORDER("?(no recorder)"),
        FAILED("?(failed)"),
        SWITCH_CHANGED("?(switch changed)"),
        DROPPED("?(dropped: switch changed mid-read)"),
    }

    @Volatile private var reading: Reading? = null
    @Volatile private var miss: Miss? = null

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
        miss = Miss.SWITCH_CHANGED
    }

    /** The setting reads 1, so nothing was asked. Recorded only so the timeline can say so. */
    fun markNotNeeded() {
        miss = Miss.NOT_NEEDED
    }

    /**
     * The value as the transport timeline prints it: `on` / `off` (the recorder's read), `on(r1)` (R1's proof), or why
     * it is unknown — `skip`, `?(no recorder)`, `?(failed)`, `?(switch changed)`, `?(dropped: …)`, `?(stale)`.
     */
    fun describe(now: Long = System.currentTimeMillis(), recorderAlive: Boolean): String {
        val r = reading
        if (r != null && now - r.atMs <= FRESH_MS) {
            return if (r.source == Source.R1) "on(r1)" else if (r.on) "on" else "off"
        }
        if (!recorderAlive) return Miss.NO_RECORDER.label
        miss?.let { return it.label }
        return if (r != null) "?(stale)" else "?"
    }

    /**
     * R1 proved USB debugging on (setting 0, adbd up, Wireless debugging off). Kept as a shell read would be, so a brief
     * adbd stop before it expires still answers ON. Dropped if a switch changed since [startedIn].
     */
    @Synchronized
    fun noteProvenOnByR1(startedIn: Long, now: Long = System.currentTimeMillis()) {
        if (startedIn != generation) return
        reading = Reading(true, now, Source.R1)
        miss = null
    }

    /** Reads it again through the recorder. Blocking; never on the main thread. Null when it cannot be read. */
    fun refresh(now: Long = System.currentTimeMillis()): Boolean? {
        val service = RecorderConnection.service ?: run { miss = Miss.NO_RECORDER; return null }
        return refreshWith(now) { service.diagnosticDump("setting_adb_enabled", null) }
    }

    /**
     * [refresh] with the read itself passed in, so the bookkeeping is testable without a recorder.
     *
     * A read still in flight when a switch changes ([forget]) saw the old switch: it is dropped rather than stored as
     * fresh, or it would override R1/R2 for up to [FRESH_MS] with a value that no longer holds (review S3).
     */
    internal fun refreshWith(now: Long, read: () -> String?): Boolean? {
        val startedIn = generation
        val on = parse(runCatching(read).getOrNull()?.trim())
        synchronized(this) {
            if (startedIn != generation) {
                miss = Miss.DROPPED
                return null
            }
            if (on == null) {
                miss = Miss.FAILED
                return null
            }
            reading = Reading(on, now, Source.SHELL)
            miss = null
        }
        return on
    }

    fun parse(text: String?): Boolean? = when (text) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
