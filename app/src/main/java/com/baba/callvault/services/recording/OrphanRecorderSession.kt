/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.baba.callvault.data.health.CallLogEntry
import com.baba.callvault.data.health.CallLogReader
import com.baba.callvault.server.DaemonSessionOwnership
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.OrphanSessionPolicy.Action
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.storage.CutOffRescueWorker
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StagingNote
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * Finds and settles a recorder session that no running CallVault process owns (review C2, N4).
 *
 * Asked at three moments: when a recorder hands its binder to a (re)started app, after every call ends
 * (phone IDLE, app-call end, the cut-off rescue check), and just before a call's own recording starts.
 * [OrphanSessionPolicy] decides; this reads the facts, carries the decision out and logs every step, to the
 * always-on [CallEndLog] too, because the reports that need it usually come with debug logging off.
 *
 * Stopping finalises the orphan's staged file; [CutOffRescueWorker] then publishes it under its own call's
 * name from its [StagingNote]. That is the existing rescue path, unchanged.
 */
object OrphanRecorderSession {

    private const val TAG = "CV:OrphanSession"
    private const val PREFS = "orphan_session"
    private const val KEY_LAST_NO_CALL_AT = "last_no_call_at"

    /** The recorder calls this needs; a fake in tests. */
    interface Recorder {
        /** Null when the recorder could not be asked. */
        fun isRecording(): Boolean?
        fun stopRecording()
    }

    /** Everything else the decision reads or does; a fake in tests. */
    interface Environment {
        fun ownedHere(): Boolean
        fun callInProgress(): Boolean
        /** When the newest note-bearing staged recording other than [exclude] started, or null. */
        fun orphanStartedAt(exclude: File?): Long?
        fun lastNoCallAt(): Long
        fun callLogAround(startedAt: Long): List<CallLogEntry>
        fun scheduleRescue()
        fun record(line: String)
    }

    /** What happened, for the caller. [stillRecording] is the recorder's answer after a stop, null when none was asked. */
    data class Outcome(val action: Action, val stillRecording: Boolean?) {
        val startVerdict: OrphanSessionPolicy.StartVerdict get() = OrphanSessionPolicy.forStart(action, stillRecording)
    }

    private val lock = Any()

    /**
     * The core, free of Android. Serialised: two triggers must never both stop, or decide on a half-stopped session.
     *
     * @param forCallStart a new call's recording is about to start. Only then does the call log count as proof that
     *   the orphan's call ended. Its match is by time and can take an earlier call that ended just before the
     *   orphan's for it; at a call start that costs a split (the new recording starts at once), while at a restart
     *   mid-call it would cut the live call with nothing to take over (Q25). A call-free moment is exact and counts always.
     */
    fun resolve(
        recorder: Recorder?,
        env: Environment,
        trigger: String,
        exclude: File? = null,
        forCallStart: Boolean = false,
    ): Outcome = synchronized(lock) {
        if (recorder == null) return Outcome(Action.NONE, null)
        val recording = recorder.isRecording()
        if (recording == null) {
            AppLogger.w(TAG, "[$trigger] could not ask the recorder whether it is recording; leaving it")
            return Outcome(Action.NONE, null)
        }
        if (!recording) return Outcome(Action.NONE, null)
        val owned = env.ownedHere()
        val inCall = if (owned) true else env.callInProgress()
        val startedAt = if (owned) null else env.orphanStartedAt(exclude)
        val ended = !owned && OrphanSessionPolicy.orphanCallEnded(
            startedAt, env.lastNoCallAt(), if (forCallStart) startedAt?.let { env.callLogAround(it) }.orEmpty() else emptyList(),
        )
        val action = OrphanSessionPolicy.decide(true, owned, inCall, ended)
        val facts = "callInProgress=$inCall orphanStartedAt=${startedAt ?: "unknown"} orphanCallEnded=$ended"
        return when (action) {
            Action.NONE, Action.OWNED -> Outcome(action, null)
            Action.KEEP_LIVE_CALL -> {
                env.record("orphan session [$trigger]: recorder is recording for a process that is gone, $facts; " +
                    "keeping it, it may be the live call's own recording (Q25)")
                Outcome(action, null)
            }
            Action.STOP_ORPHAN -> {
                env.record("orphan session [$trigger]: recorder is recording for a process that is gone, $facts; stopping it")
                val stopFailure = runCatching { recorder.stopRecording() }.exceptionOrNull()
                val still = recorder.isRecording()
                if (stopFailure != null || still != false) {
                    env.record("orphan session [$trigger]: stop FAILED (still recording=${still ?: "unknown"}" +
                        (stopFailure?.let { " " + CallEndLog.describe(it) } ?: "") + ")")
                } else {
                    env.record("orphan session [$trigger]: stopped; its file goes to the cut-off rescue under its own call's name")
                }
                // Scheduled either way: a stopped file is published once quiet, a failed stop is looked at again.
                env.scheduleRescue()
                Outcome(action, still)
            }
        }
    }

    /** Android entry point. Blocking (a stop waits for the recorder to finalise the file): never on the main thread. */
    fun resolve(context: Context, trigger: String, exclude: File? = null, forCallStart: Boolean = false): Outcome =
        resolve(
            RecorderConnection.service?.let { BinderRecorder(it) }, AndroidEnvironment(context.applicationContext),
            trigger, exclude, forCallStart,
        )

    /** [resolve] on a background thread, for triggers on the main thread or inside a binder call. */
    fun resolveAsync(context: Context, trigger: String) {
        val app = context.applicationContext
        Thread {
            runCatching { resolve(app, trigger) }
                .onFailure { AppLogger.e(TAG, "[$trigger] orphan check failed: ${it.message}", it) }
        }.apply { isDaemon = true; name = "cv-orphan-check" }.start()
    }

    /** Remembers that no call is in progress now (a phone IDLE, an app call's end). Read by [OrphanSessionPolicy.orphanCallEnded]. */
    fun noteNoCall(context: Context, atMillis: Long = System.currentTimeMillis()) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_NO_CALL_AT, atMillis).apply()
        }.onFailure { AppLogger.w(TAG, "Could not note the call-free moment: ${it.message}") }
    }

    private class BinderRecorder(private val service: com.baba.callvault.server.IRecorderService) : Recorder {
        override fun isRecording(): Boolean? = runCatching { service.isRecording() }
            .onFailure { AppLogger.w(TAG, "isRecording failed: ${it.message}") }.getOrNull()

        override fun stopRecording() = service.stopRecording()
    }

    private class AndroidEnvironment(private val context: Context) : Environment {
        override fun ownedHere(): Boolean = DaemonSessionOwnership.isOwnedHere

        // READ_PHONE_STATE is a core permission of the app; a missing grant throws and reads as "in a call" below.
        @SuppressLint("MissingPermission")
        override fun callInProgress(): Boolean = runCatching {
            @Suppress("DEPRECATION")
            val carrier = context.getSystemService(TelephonyManager::class.java)?.callState
            val mode = context.getSystemService(AudioManager::class.java)?.mode
            val telecom = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull()
            (carrier != null && carrier != TelephonyManager.CALL_STATE_IDLE) ||
                (mode != null && AfterCallPolicy.isCallAudioMode(mode)) ||
                telecom == true
        }.onFailure { AppLogger.w(TAG, "Could not read the call state; treating it as a call in progress: ${it.message}") }
            .getOrDefault(true) // unknown reads as "in a call": keeping a session costs a late stop, cutting a live one costs the call

        override fun orphanStartedAt(exclude: File?): Long? {
            val dir = SafHelper.stagingDirOrNull(context) ?: return null
            val newest = dir.listFiles().orEmpty()
                .filter { SafHelper.isStagedRecording(it) && it.absolutePath != exclude?.absolutePath }
                .filter { StagingNote.fileFor(it).exists() }
                .maxByOrNull { it.lastModified() } ?: return null
            return runCatching { StagingNote.decode(StagingNote.fileFor(newest).readText())?.startedAtMillis }.getOrNull()
        }

        override fun lastNoCallAt(): Long = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_NO_CALL_AT, 0L)
        }.getOrDefault(0L)

        override fun callLogAround(startedAt: Long): List<CallLogEntry> =
            CallLogReader.entriesSince(context, startedAt - OrphanSessionPolicy.MAX_RING_BEFORE_RECORDING_MS - 1)

        override fun scheduleRescue() = CutOffRescueWorker.schedule(context)

        override fun record(line: String) = CallEndLog.record(context, line)
    }
}
