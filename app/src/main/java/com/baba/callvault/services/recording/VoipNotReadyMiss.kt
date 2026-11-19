/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.services.recording.VoipMissPolicy.NotReadyVerdict
import java.io.File

/**
 * An app call whose start found the recorder not connected, from that start to the verdict at its end (review N18).
 *
 * Calls are keyed by the coordinator's call counter, so a record whose end never arrived can never decide a later call:
 * a new not-ready start replaces it, and a start of another call that returns early forgets it. [onNotReadyStart],
 * [forgetOtherCalls], [clear] and [takeAtEnd] are cheap and run under the coordinator's lock; [settle] does the binder
 * call, the folder listing and the library read, and runs off the caller's thread.
 */
class VoipNotReadyMiss {

    /** What the decision reads and does; the phone in the app, a fake in tests. */
    interface Env {
        fun now(): Long
        /** [CallEvidence.looksLikeACall]. */
        fun looksLikeACall(): Boolean
        /** When the call in progress began, as noted by the process that saw it begin (kept across a kill); null = unknown. */
        fun liveCallBeganAt(): Long?
        /** The recorder is recording a session this process does not own; null when it could not be asked. */
        fun recorderRecordingUnowned(): Boolean?
        /** The staging folder's files. */
        fun stagedFiles(): List<File>
        /** [com.baba.callvault.system.storage.LiveStagingFiles.isLive]. */
        fun isLiveStaging(file: File): Boolean
        /** The library's recordings as (name, start); null when the library could not be read. */
        fun catalogued(): List<Pair<String, Long?>>?
        /** The always-on log. */
        fun record(line: String)
        /** Tell the user the call was not recorded. */
        fun reportMiss()
    }

    data class Pending(val call: Int, val atMillis: Long, val callBeganAt: Long?, val lookedLikeCall: Boolean)

    data class Ended(val pending: Pending, val endedAtMillis: Long)

    private var pending: Pending? = null

    /** A start of [call] found no recorder. The first such start of a call stands; any other call's record is replaced. */
    @Synchronized
    fun onNotReadyStart(call: Int, env: Env) {
        val current = pending
        if (current != null && current.call == call) return
        if (current != null) env.record("app call not-ready check: dropping the record of an earlier call whose end never came")
        pending = Pending(call, env.now(), env.liveCallBeganAt(), env.looksLikeACall())
    }

    /** A start of [call] began: a record of any other call is stale (its end never came) and is dropped. */
    @Synchronized
    fun forgetOtherCalls(call: Int, env: Env) {
        val current = pending ?: return
        if (current.call == call) return
        pending = null
        env.record("app call not-ready check: dropping the record of an earlier call whose end never came")
    }

    /** This call's not-ready start no longer stands ([why]): it was recorded after all, or it is not a miss. */
    @Synchronized
    fun clear(why: String, env: Env) {
        if (pending == null) return
        pending = null
        env.record("app call not-ready check: cleared, $why")
    }

    /** [call] ended: its record, to [settle], or null when it has none. */
    @Synchronized
    fun takeAtEnd(call: Int, env: Env): Ended? {
        val current = pending ?: return null
        pending = null
        if (current.call != call) {
            env.record("app call not-ready check: dropping the record of an earlier call whose end never came")
            return null
        }
        return Ended(current, env.now())
    }

    /** The verdict, logged with what it rests on (which file or library entry matched); reports a real miss. */
    fun settle(ended: Ended, env: Env): NotReadyVerdict {
        val p = ended.pending
        val until = ended.endedAtMillis
        val unowned = env.recorderRecordingUnowned()
        val staged = if (unowned == true) null else VoipMissPolicy.stagedRecordingOfCall(env.stagedFiles(), p.atMillis, until, env::isLiveStaging)
        // The killed process's recording began with the call, before this process's start; never after the call ended.
        val from = minOf(p.callBeganAt ?: p.atMillis, p.atMillis) - OrphanSessionPolicy.SAME_CALL_MARGIN_MS
        val library = if (unowned == true || staged != null) null else env.catalogued()
        val catalogued = library?.let { VoipMissPolicy.cataloguedOfCall(it, from, until) }
        val found: Boolean? = when {
            staged != null || catalogued != null -> true
            unowned == true -> false
            library == null -> null
            else -> false
        }
        val verdict = VoipMissPolicy.notReadyAtCallEnd(p.lookedLikeCall, unowned, found)
        env.record(
            "app call not-ready check at the call's end: recorderRecordingUnowned=${unowned ?: "unknown"} " +
                "staged=${staged?.name ?: "none"} library=${catalogued ?: if (library == null && unowned != true && staged == null) "unreadable" else "none"} " +
                "lookedLikeCall=${p.lookedLikeCall} callBeganAt=${p.callBeganAt ?: "unknown"} -> $verdict"
        )
        if (verdict == NotReadyVerdict.REPORT) env.reportMiss()
        return verdict
    }
}
