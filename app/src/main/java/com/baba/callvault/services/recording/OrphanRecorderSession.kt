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
import com.baba.callvault.server.DaemonSessionOwnership
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.services.recording.OrphanSessionPolicy.Action
import com.baba.callvault.services.recording.aftercall.AfterCallPolicy
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.storage.CutOffRescueWorker
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.OrphanStop
import com.baba.callvault.system.storage.StagingNote
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * Finds and settles a recorder session that no running CallVault process owns (review C2, N4).
 *
 * Asked when a (re)started app has its recorder, after every call ends (phone IDLE, app-call end, the cut-off
 * rescue check), and just before a call's own recording starts. [OrphanSessionPolicy] decides; this reads the
 * facts, carries the decision out and logs every step, to the always-on [CallEndLog] too, because the reports
 * that need it usually come with debug logging off.
 *
 * Stopping finalises the orphan's staged file; [CutOffRescueWorker] then publishes it under its own call's name
 * from its [StagingNote]. That is the existing rescue path, unchanged.
 *
 * Three facts outlive a process kill here, in one small preferences file:
 * - which staged file the app handed to the recorder for its current session ([noteSessionStarted]), so the
 *   orphan's start time is read from the file the recorder is actually writing, not from whichever note is newest;
 * - when the call in progress began ringing or was dialled ([noteCallBegan]); a restart mid-call keeps the value
 *   the killed process wrote, which is what tells the live call's own recording (Q25) from an earlier call's;
 * - the last moment no call at all was in progress ([noteNoCallIfFree]).
 */
object OrphanRecorderSession {

    private const val TAG = "CV:OrphanSession"

    /** The trigger of the settle that follows a phone call ending. Only a name for the log: "saved in full" is decided from the call state at stop time. */
    const val TRIGGER_PHONE_CALL_ENDED = "phone call ended"
    private const val PREFS = "orphan_session"
    private const val KEY_LAST_NO_CALL_AT = "last_no_call_at"
    private const val KEY_LIVE_CALL_BEGAN_AT = "live_call_began_at"
    private const val KEY_SESSION_STAGING = "session_staging"
    private const val KEY_LAST_NO_SESSION_AT = "last_no_session_at"

    /** The recorder calls this needs; a fake in tests. */
    interface Recorder {
        /** Null when the recorder could not be asked. */
        fun isRecording(): Boolean?
        fun stopRecording()
    }

    /** Everything else the decision reads or does; a fake in tests. */
    interface Environment {
        fun ownedHere(): Boolean
        /** Any phone or app call ringing or up, by telephony, Telecom or the audio mode. Null when unreadable. */
        fun callInProgress(): Boolean?
        /** When the orphan's recording started; see [OrphanSessionPolicy.orphanStartFrom]. */
        fun orphanStartedAt(exclude: File?): Long?
        fun lastNoCallAt(): Long
        fun liveCallBeganAt(): Long?
        /** No call is in progress now: remember it, and forget the last call's beginning. */
        fun markNoCall()
        /** The recorder is not recording now: no session can be orphaned from before this moment. */
        fun markNoSession()
        fun scheduleRescue()
        /** The staged file the orphan was writing, when known; null when it cannot be told (then no instant rescue). */
        fun orphanStagingFile(exclude: File?): File? = null
        /**
         * Marks [file] as stopped by the recorder ([callEnded]: and its call's end is inside it), so whichever worker
         * publishes it knows. False when it could not be marked (no readable note).
         */
        fun markOrphanStopped(file: File, callEnded: Boolean): Boolean = false
        /** Queue the short-guard rescue of the marked [file]: published once it has been quiet for a few seconds. */
        fun scheduleRescueNow(file: File) = scheduleRescue()
        /**
         * Whether the call log has a call that began after [sinceMillis] (an orphan's start plus the ring slack), i.e.
         * another call the file may span. Null when the log cannot be read. Used only to downgrade a "saved in full".
         */
        fun callLoggedAfter(sinceMillis: Long): Boolean? = null
        fun record(line: String)
    }

    /** What happened, for the caller. [stillRecording] is the recorder's answer after a stop, null when none was asked. */
    data class Outcome(val action: Action, val stillRecording: Boolean?)

    private val lock = Any()

    /** The core, free of Android. Serialised: two triggers must never both stop, or decide on a half-stopped session. */
    fun resolve(recorder: Recorder?, env: Environment, trigger: String, exclude: File? = null): Outcome = synchronized(lock) {
        if (recorder == null) return Outcome(Action.NONE, null)
        val recording = recorder.isRecording()
        if (recording == null) {
            env.record("orphan session [$trigger]: could not ask the recorder whether it is recording; leaving it")
            return Outcome(Action.NONE, null)
        }
        if (!recording) {
            env.markNoSession()
            return Outcome(Action.NONE, null)
        }
        if (env.ownedHere()) return Outcome(Action.OWNED, null)
        val readCall = env.callInProgress()
        if (readCall == null) env.record("orphan session [$trigger]: could not read the call state; treating it as a call in progress")
        val inCall = readCall ?: true // unknown: keeping a session costs a late stop, cutting a live one costs the call
        val startedAt = env.orphanStartedAt(exclude)
        val began = env.liveCallBeganAt()
        val ended = OrphanSessionPolicy.orphanCallEnded(startedAt, env.lastNoCallAt(), began)
        val action = OrphanSessionPolicy.decide(true, false, inCall, ended)
        val stagingFile = if (action == Action.STOP_ORPHAN) env.orphanStagingFile(exclude) else null
        val facts = "callInProgress=$inCall orphanStartedAt=${startedAt ?: "unknown"} liveCallBeganAt=${began ?: "unknown"} orphanCallEnded=$ended"
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
                    env.markNoSession()
                    env.record("orphan session [$trigger]: stopped; its file goes to the cut-off rescue under its own call's name")
                    // Finalised, call over: publish after a short guard (the recorder answers before the trailer is
                    // written), not after the 2-minute quiet wait. Only the phone-call-ended stop is "saved in full".
                    if (stagingFile != null) {
                        // Evidence, not the trigger's name: no call at all now, and the orphan's call known to be over
                        // (a call-free moment after it started). A new call being up (N4, ADOPT spans) leaves it STOPPED.
                        var callEnded = !inCall && ended
                        // Downgrade only (it can never cut a live call): a call logged after the orphan began means the file
                        // may span two calls (call B's start went unseen); then it is not "the whole call". Unreadable log: same.
                        if (callEnded && startedAt != null) {
                            val other = env.callLoggedAfter(startedAt + OrphanSessionPolicy.SAME_CALL_LOG_SLACK_MS)
                            if (other != false) {
                                callEnded = false
                                env.record("orphan session [$trigger]: not marked saved-in-full, " +
                                    if (other == true) "a later call is in the call log (the file may span two calls)" else "the call log could not be read")
                            }
                        }
                        if (env.markOrphanStopped(stagingFile, callEnded)) {
                            env.record("orphan session [$trigger]: instant rescue queued for ${CallEndLog.safeName(stagingFile.name)} (savedInFull=$callEnded)")
                            env.scheduleRescueNow(stagingFile)
                        } else {
                            env.record("orphan session [$trigger]: could not mark ${CallEndLog.safeName(stagingFile.name)} as stopped (no readable note); it waits for the quiet-wait rescue")
                        }
                    }
                }
                // Scheduled either way: the quiet-wait request is the safety net, a failed stop is looked at again.
                env.scheduleRescue()
                Outcome(action, still)
            }
        }
    }

    /**
     * Records a call-free moment, only when no call of either kind is ringing or up right now (review C2 second
     * pass). A phone IDLE during a live WhatsApp call, or an app call ending under a phone call, is not one.
     * @return whether it was recorded.
     */
    fun noteNoCallIfFree(env: Environment): Boolean {
        val inCall = env.callInProgress()
        if (inCall != false) return false
        env.markNoCall()
        return true
    }

    /** Android entry point. Blocking (a stop waits for the recorder to finalise the file): never on the main thread. */
    fun resolve(context: Context, trigger: String, exclude: File? = null): Outcome =
        resolve(RecorderConnection.service?.let { BinderRecorder(it) }, AndroidEnvironment(context.applicationContext), trigger, exclude)

    /** [noteNoCallIfFree] then [resolve]; blocking, for a background thread after a call ended. */
    fun settle(context: Context, trigger: String): Outcome {
        val env = AndroidEnvironment(context.applicationContext)
        noteNoCallIfFree(env)
        return resolve(RecorderConnection.service?.let { BinderRecorder(it) }, env, trigger)
    }

    /** [settle] on a background thread, for triggers on the main thread. */
    fun settleAsync(context: Context, trigger: String) {
        val app = context.applicationContext
        Thread {
            runCatching { settle(app, trigger) }
                .onFailure { AppLogger.e(TAG, "[$trigger] orphan check failed: ${it.message}", it) }
        }.apply { isDaemon = true; name = "cv-orphan-check" }.start()
    }

    /**
     * Cheap, lock-free and safe on the main thread: whether a start would find the recorder busy with a session
     * nobody here owns, i.e. whether [resolve] has anything to settle. One binder call, no stop.
     */
    fun needsSettling(): Boolean {
        if (DaemonSessionOwnership.isOwnedHere) return false
        return runCatching { RecorderConnection.service?.isRecording() == true }.getOrDefault(false)
    }

    /** When the call in progress began, as noted by whichever process saw it begin (kept across a kill); null = unknown. */
    fun liveCallBeganAt(context: Context): Long? = AndroidEnvironment(context.applicationContext).liveCallBeganAt()

    /** The call in progress began now (it rang, or was dialled), seen by this process. */
    fun noteCallBegan(context: Context, atMillis: Long = System.currentTimeMillis()) {
        edit(context) { putLong(KEY_LIVE_CALL_BEGAN_AT, atMillis) }
    }

    /** The app handed [staging] to the recorder for a session it now owns. */
    fun noteSessionStarted(context: Context, staging: File?) {
        synchronized(sessionRecordLock) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                    if (staging != null) putString(KEY_SESSION_STAGING, staging.absolutePath) else remove(KEY_SESSION_STAGING)
                }.commit()
            }.onFailure { AppLogger.w(TAG, "Could not update the orphan-session facts: ${it.message}") }
        }
    }

    /**
     * The app's own session on [staging] is over (stopped or never started). The record is removed only while it
     * still names [staging]: an app call ending late on another thread must not wipe the record of the phone call's
     * session that started meanwhile (gate review item 3).
     */
    fun noteSessionEnded(context: Context, staging: File?) {
        synchronized(sessionRecordLock) {
            runCatching {
                val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val recorded = prefs.getString(KEY_SESSION_STAGING, null)
                prefs.edit().apply {
                    if (OrphanSessionPolicy.endsRecordedSession(recorded, staging?.absolutePath)) remove(KEY_SESSION_STAGING)
                    putLong(KEY_LAST_NO_SESSION_AT, System.currentTimeMillis())
                }.commit()
            }.onFailure { AppLogger.w(TAG, "Could not update the orphan-session facts: ${it.message}") }
        }
    }

    private val sessionRecordLock = Any()

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply(block).apply()
        }.onFailure { AppLogger.w(TAG, "Could not update the orphan-session facts: ${it.message}") }
    }

    private class BinderRecorder(private val service: com.baba.callvault.server.IRecorderService) : Recorder {
        override fun isRecording(): Boolean? = runCatching { service.isRecording() }
            .onFailure { AppLogger.w(TAG, "isRecording failed: ${it.message}") }.getOrNull()

        override fun stopRecording() = service.stopRecording()
    }

    private class AndroidEnvironment(private val context: Context) : Environment {
        private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun ownedHere(): Boolean = DaemonSessionOwnership.isOwnedHere

        // READ_PHONE_STATE is a core permission of the app; a missing grant throws and reads as unknown.
        @SuppressLint("MissingPermission")
        override fun callInProgress(): Boolean? = runCatching {
            @Suppress("DEPRECATION")
            val carrier = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }.getOrNull()
            val am = context.getSystemService(AudioManager::class.java)
            val mode = runCatching { am?.mode }.getOrNull()
            val telecom = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull()
            val voice = runCatching { am?.let { VoipEndProbe.capture(it) } }.getOrNull()
            OrphanSessionPolicy.isCallInProgress(
                carrierState = carrier,
                audioModeIsCall = mode?.let(AfterCallPolicy::isCallAudioMode),
                telecomInCall = telecom,
                appCallRecording = VoipRecordingCoordinator.isRecording,
                voiceCommPlayers = voice?.voiceCallPlayers,
                voiceCommRecorders = voice?.voiceCommRecorders,
            )
        }.onFailure { AppLogger.w(TAG, "Could not read the call state: ${it.message}") }.getOrNull()

        override fun orphanStartedAt(exclude: File?): Long? {
            val sessionStart = prefs.getString(KEY_SESSION_STAGING, null)
                ?.let(::File)?.takeIf { it.absolutePath != exclude?.absolutePath }
                ?.let(::noteStart)
            val dir = SafHelper.stagingDirOrNull(context)
            val candidates = if (sessionStart != null || dir == null) emptyList() else dir.listFiles().orEmpty()
                .filter { SafHelper.isStagedRecording(it) && it.absolutePath != exclude?.absolutePath }
                .mapNotNull(::noteStart)
            return OrphanSessionPolicy.orphanStartFrom(sessionStart, candidates, prefs.getLong(KEY_LAST_NO_SESSION_AT, 0L))
        }

        private fun noteStart(staged: File): Long? = StagingNote.fileFor(staged).takeIf { it.exists() }
            ?.let { runCatching { StagingNote.decode(it.readText())?.startedAtMillis }.getOrNull() }

        override fun lastNoCallAt(): Long = runCatching { prefs.getLong(KEY_LAST_NO_CALL_AT, 0L) }.getOrDefault(0L)

        override fun liveCallBeganAt(): Long? =
            runCatching { prefs.getLong(KEY_LIVE_CALL_BEGAN_AT, 0L) }.getOrDefault(0L).takeIf { it > 0L }

        override fun markNoCall() = edit(context) {
            putLong(KEY_LAST_NO_CALL_AT, System.currentTimeMillis())
            remove(KEY_LIVE_CALL_BEGAN_AT)
        }

        override fun markNoSession() = edit(context) { putLong(KEY_LAST_NO_SESSION_AT, System.currentTimeMillis()) }

        override fun scheduleRescue() = CutOffRescueWorker.schedule(context)

        override fun orphanStagingFile(exclude: File?): File? {
            val path = prefs.getString(KEY_SESSION_STAGING, null)
            val file = path?.let(::File)
            val why = when {
                file == null -> "no session file was recorded"
                file.absolutePath == exclude?.absolutePath -> "the recorded session file is the starting call's own"
                !file.exists() -> "the recorded session file is gone"
                else -> null
            }
            if (why != null) record("orphan session: no instant rescue, $why; the quiet-wait rescue covers it")
            return if (why == null) file else null
        }

        override fun callLoggedAfter(sinceMillis: Long): Boolean? {
            if (!com.baba.callvault.system.permissions.PermissionChecks.hasCallLogPermission(context)) return null
            return runCatching { com.baba.callvault.data.health.CallLogReader.entriesSince(context, sinceMillis).isNotEmpty() }.getOrNull()
        }

        override fun markOrphanStopped(file: File, callEnded: Boolean): Boolean =
            StagingNote.markOrphanStop(file, if (callEnded) OrphanStop.CALL_ENDED else OrphanStop.STOPPED)

        override fun scheduleRescueNow(file: File) = CutOffRescueWorker.scheduleConfirmed(context, file)

        override fun record(line: String) = CallEndLog.record(context, line)
    }
}
