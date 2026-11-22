/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * When the launcher must leave a running recorder daemon alone instead of killing it before a launch (2026-11-22).
 *
 * A restarted app process that held no binder killed the daemon that was recording a call, and the rest of the call
 * was lost. The evidence that a daemon is mid-call is the app's own record of the session's staging file, and that
 * file is still growing; a call-state read or a process scan alone is not enough (an idle daemon left behind between
 * calls, or a VoIP app stuck in a call audio mode, must still be replaced).
 */
object StaleDaemonPolicy {

    /** The scan command ends with this line, so a scan that was cut short is told apart from one that found nothing. */
    const val END_MARKER = "cv-end"

    /** A staging file modified this recently is being written. */
    const val FRESH_WRITE_MS = 5_000L

    /** A session record whose file was last written longer ago than this is left over from an earlier call. */
    const val STALE_SESSION_MS = 30_000L

    enum class Action { SPARE, KILL_THEN_LAUNCH }

    sealed class Probe {
        /** The scan failed or was cut short: nothing is known. */
        object Unknown : Probe() { override fun toString() = "unknown" }

        /** A complete scan: the recorder hosts found (a launcher shell that merely mentions the class is not one). */
        data class Hosts(val pids: List<Int>) : Probe()
    }

    /** Reads the scan output: `<pid> <argv...>` per line, then [END_MARKER]. Without the marker the result is unknown. */
    fun parseProbe(output: String?): Probe {
        if (output == null) return Probe.Unknown
        val lines = output.lines().map { it.trim() }
        if (END_MARKER !in lines) return Probe.Unknown
        val pids = lines.mapNotNull { line ->
            val pid = line.substringBefore(' ').toIntOrNull() ?: return@mapNotNull null
            val cmdline = line.substringAfter(' ', "").trim().replace(' ', '\u0000')
            pid.takeIf { RecorderProcessFilter.isRecorderHost(cmdline) }
        }
        return Probe.Hosts(pids)
    }

    /**
     * Whether the staging file is being written: modified within [FRESH_WRITE_MS], or bigger on a second read taken a
     * moment after the first. [ageMs] null = the file is missing.
     */
    fun isGrowing(ageMs: Long?, sizeBefore: Long?, sizeAfter: Long?): Boolean = when {
        ageMs == null -> false
        ageMs > STALE_SESSION_MS -> false
        ageMs in 0..FRESH_WRITE_MS -> true
        sizeBefore != null && sizeAfter != null -> sizeAfter > sizeBefore
        else -> false
    }

    /**
     * Spare only when a session's file is growing, a recorder host exists (or the scan could not say), and no call-state
     * read says the call is over. A growing file after the call ended is the orphan itself, which is killed as before
     * (the app, not the launcher, is what stops it cleanly, once its binder is back).
     */
    fun decide(stagingGrowing: Boolean, probe: Probe, inCall: Boolean?): Action = when {
        !stagingGrowing -> Action.KILL_THEN_LAUNCH
        probe is Probe.Hosts && probe.pids.isEmpty() -> Action.KILL_THEN_LAUNCH
        inCall == false -> Action.KILL_THEN_LAUNCH
        else -> Action.SPARE
    }
}

/**
 * What this app process learned about sparing a recording daemon. A spare is a bet that the daemon's hand-over is on its
 * way, withdrawn when:
 * - this process refused a hand-over for a reason that does not pass with time (recording switched off, wrong mode).
 *   A hand-over from an OLD APK is NOT such a refusal: owner decision, that daemon finishes the call it is recording
 *   (the app refuses its binder, so it is never "held", yet it must not be killed mid-call);
 * - no hand-over came within [PATIENCE_MS] of the first spare AND a call is not known to be up. While a call is up
 *   and the file is growing the daemon is left alone however long it takes; the call's end (or the daemon's own
 *   finalising of the recording once the call is over) ends it. Trade-off: a daemon that runs on into the NEXT call
 *   (call A over, B started within the daemon's self-stop window) is spared and B lands in A's file; the daemon's
 *   35 s quiet-time self-stop and the launcher's kill-after-call keep that window short.
 */
class SpareMemory {
    @Volatile private var firstSparedAt: Long? = null
    @Volatile private var rejected = false

    fun noteSpared(now: Long) { if (firstSparedAt == null) firstSparedAt = now }
    fun noteRejected() { rejected = true }
    fun reset() { firstSparedAt = null; rejected = false }

    /** [callUp] is the call-state read at this launch (null = unreadable). */
    fun stillAllowed(now: Long, callUp: Boolean?): Boolean =
        !rejected && (callUp == true || (firstSparedAt?.let { now - it < PATIENCE_MS } ?: true))

    companion object {
        const val PATIENCE_MS = 30_000L

        fun refusalWithdrawsSpare(verdict: DaemonReuse.Verdict): Boolean =
            verdict != DaemonReuse.Verdict.ALREADY_HELD && verdict != DaemonReuse.Verdict.STALE_APK && verdict != DaemonReuse.Verdict.ACCEPT

        val shared = SpareMemory()
    }
}
