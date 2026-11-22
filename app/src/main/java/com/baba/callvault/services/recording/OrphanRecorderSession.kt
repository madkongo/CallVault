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
    internal const val PREFS = "orphan_session"
    internal const val KEY_LAST_NO_CALL_AT = "last_no_call_at"
    private const val KEY_LIVE_CALL_BEGAN_AT = "live_call_began_at"
    private const val KEY_SESSION_STAGING = "session_staging"
    private const val KEY_SESSION_APP_CALL = "session_app_call"
    private const val KEY_LAST_NO_SESSION_AT = "last_no_session_at"
    private const val KEY_CALL_ENDED_AT = "call_ended_at"

    /** The trigger of the short WorkManager safety net queued at a phone call's end. */
    const val TRIGGER_CALL_END_CHECK = "call end check"

    fun scheduledLine(delayMs: Long) = "orphan check scheduled for +${delayMs / 1_000} s"
    fun runningLine(lateMs: Long) = "orphan check running (late by $lateMs ms)"

    /** The phone call ended now (IDLE): kept across a kill, to tell later how long an orphan ran on. */
    fun noteCallEnded(context: Context, atMillis: Long = System.currentTimeMillis()) {
        edit(context) { putLong(KEY_CALL_ENDED_AT, atMillis) }
    }

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
        /** An orphan was stopped: forget the session record naming its file, unless it names [keep] (a live session). */
        fun clearSessionRecord(keep: File?) {}
        fun scheduleRescue()
        /** The staged file the orphan was writing, when known; null when it cannot be told (then no instant rescue). */
        fun orphanStagingFile(exclude: File?): File? = null
        /**
         * Marks [file] as stopped by the recorder ([callEnded]: and its call's end is inside it), so whichever worker
         * publishes it knows. False when it could not be marked (no readable note).
         */
        fun markOrphanStopped(file: File, callEnded: Boolean, ranOnSeconds: Int = 0, appCall: Boolean = false): Boolean = false
        /** When the phone call last ended (noted at IDLE), or null. Read to tell how long a stopped orphan ran on. */
        fun callEndedAt(): Long? = null
        /** When the daemon last finished a recording itself (its own word, asked over binder); null = none or unknown. */
        fun daemonSelfStopAt(): Long? = null
        /**
         * A staged file still named by the session record that the recorder is no longer writing and that no one marked:
         * the daemon finalised it on its own. Null when none, or when the facts do not show it ended after its call.
         */
        fun daemonFinalisedOrphan(exclude: File?): DaemonFinalised? = null
        fun now(): Long = System.currentTimeMillis()
        /** Queue the short-guard rescue of the marked [file]: published once it has been quiet for a few seconds. */
        fun scheduleRescueNow(file: File) = scheduleRescue()
        /**
         * Whether the call log has a call that began after [sinceMillis] (an orphan's start plus the ring slack), i.e.
         * another call the file may span. Null when the log cannot be read. Used only to downgrade a "saved in full".
         */
        fun callLoggedAfter(sinceMillis: Long): Boolean? = null
        /** The session was an app call (WhatsApp etc.): those never appear in the call log, so no log check can vouch for it. */
        fun orphanWasAppCall(): Boolean = false
        fun record(line: String)
    }

    /** [file] was finalised by the daemon's self-stop; [lastWriteAt] is its last write, [startedAt] the orphan's start. */
    data class DaemonFinalised(val file: File, val lastWriteAt: Long, val startedAt: Long?)

    /** What happened, for the caller. [stillRecording] is the recorder's answer after a stop, null when none was asked. */
    data class Outcome(val action: Action, val stillRecording: Boolean?)

    private val lock = Any()

    /** Files already told about as "not shown", so the line comes once per file, not at every call end. Guarded by [lock]. */
    private val notShownLogged = HashSet<String>()

    /** The core, free of Android. Serialised: two triggers must never both stop, or decide on a half-stopped session. */
    fun resolve(recorder: Recorder?, env: Environment, trigger: String, exclude: File? = null): Outcome = synchronized(lock) {
        if (recorder == null) {
            env.record("orphan session [$trigger]: no recorder connection; nothing to check")
            return Outcome(Action.NONE, null)
        }
        val recording = recorder.isRecording()
        if (recording == null) {
            env.record("orphan session [$trigger]: could not ask the recorder whether it is recording; leaving it")
            return Outcome(Action.NONE, null)
        }
        if (!recording) {
            env.record("orphan session [$trigger]: the recorder is not recording; no orphan")
            // Not while the app's own session is still winding up: stopRecording clears the recorder's latch at the start of a
            // ~4 s teardown, and the file is the app's to publish.
            if (env.ownedHere()) env.record("orphan session [$trigger]: not recording but the session is still owned here (teardown in progress); not claiming its file")
            else claimDaemonFinalised(env, trigger, exclude)
            env.markNoSession()
            return Outcome(Action.NONE, null)
        }
        if (env.ownedHere()) {
            env.record("orphan session [$trigger]: the recording is owned by this process; not an orphan")
            return Outcome(Action.OWNED, null)
        }
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
                // Note (owner decision 2026-11-22): the app start now runs DaemonKeepAliveService in standalone too
                // (CallVaultApplication.onCreate); a KEEP here does not start it separately.
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
                    env.clearSessionRecord(exclude)
                    env.record("orphan session [$trigger]: stopped; its file goes to the cut-off rescue under its own call's name")
                    // Finalised, call over: publish after a short guard (the recorder answers before the trailer is
                    // written), not after the 2-minute quiet wait. Only the phone-call-ended stop is "saved in full".
                    if (stagingFile != null) {
                        // Evidence, not the trigger's name: no call at all now, and the orphan's call known to be over
                        // (a call-free moment after it started). A new call being up (N4, ADOPT spans) leaves it STOPPED.
                        var callEnded = !inCall && ended
                        // Downgrade only (it can never cut a live call): a call logged after the orphan began means the file
                        // may span two calls (call B's start went unseen); then it is not "the whole call". Unreadable log: same.
                        // An app call's orphan is never "saved in full": the call log cannot show a second app call, so the
                        // guard below could not see a file spanning two. It keeps the cut-off wording.
                        if (callEnded && env.orphanWasAppCall()) {
                            callEnded = false
                            env.record("orphan session [$trigger]: not marked saved-in-full, the session was an app call (not in the call log)")
                        }
                        if (callEnded && startedAt != null) {
                            val other = env.callLoggedAfter(startedAt + OrphanSessionPolicy.SAME_CALL_LOG_SLACK_MS)
                            if (other != false) {
                                callEnded = false
                                env.record("orphan session [$trigger]: not marked saved-in-full, " +
                                    if (other == true) "a later call is in the call log (the file may span two calls)" else "the call log could not be read")
                            }
                        }
                        val ranOn = OrphanSessionPolicy.ranOnSeconds(env.now(), env.callEndedAt(), startedAt)
                        if (ranOn != null) env.record("orphan session [$trigger]: file outlasts its call by $ranOn s (stopped that long after the call ended)")
                        if (env.markOrphanStopped(stagingFile, callEnded, ranOn ?: 0, env.orphanWasAppCall())) {
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
     * The daemon stopped a recording itself after its call ended (the app was frozen or gone): the staged file is finished
     * but nothing marked it, so the rescue would call a whole call "cut off". Marked here, with the same guards as an
     * app-side stop (not an app call, no later call in the log), so it is published as saved in full.
     */
    private fun claimDaemonFinalised(env: Environment, trigger: String, exclude: File?) {
        val found = env.daemonFinalisedOrphan(exclude) ?: return
        val inCall = env.callInProgress()
        val callEndedAt = env.callEndedAt()
        if (!OrphanSessionPolicy.finalisedAfterCallEnd(found.startedAt, callEndedAt, found.lastWriteAt, inCall, env.daemonSelfStopAt())) {
            if (notShownLogged.add(found.file.absolutePath)) {
                env.record("orphan session [$trigger]: ${CallEndLog.safeName(found.file.name)} is not a recording the daemon finished after its call ended " +
                    "(no self-stop word, or the call end is not shown); left to the quiet-wait rescue")
            }
            return
        }
        var callEnded = !env.orphanWasAppCall()
        if (callEnded && found.startedAt != null && env.callLoggedAfter(found.startedAt + OrphanSessionPolicy.SAME_CALL_LOG_SLACK_MS) != false) callEnded = false
        val ranOn = OrphanSessionPolicy.ranOnSeconds(found.lastWriteAt, callEndedAt, found.startedAt) ?: 0
        if (ranOn > 0) env.record("orphan session [$trigger]: file outlasts its call by $ranOn s (the recorder stopped itself)")
        if (env.markOrphanStopped(found.file, callEnded, ranOn, env.orphanWasAppCall())) {
            env.clearSessionRecord(exclude)
            env.record("orphan session [$trigger]: the recorder stopped ${CallEndLog.safeName(found.file.name)} itself after the call ended; instant rescue queued (savedInFull=$callEnded)")
            env.scheduleRescueNow(found.file)
        } else {
            env.record("orphan session [$trigger]: could not mark ${CallEndLog.safeName(found.file.name)} as stopped (no readable note)")
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
        // A synchronous binder call; callers on the main thread (the phone IDLE handler) accept it for one isRecording read.
        if (DaemonSessionOwnership.isOwnedHere) return false
        return runCatching { RecorderConnection.service?.isRecording() == true }
            .onFailure { AppLogger.w(TAG, "needsSettling: could not ask the recorder (${it.javaClass.simpleName}: ${it.message}); treating it as nothing to settle") }
            .getOrDefault(false)
    }

    /** When the call in progress began, as noted by whichever process saw it begin (kept across a kill); null = unknown. */
    fun liveCallBeganAt(context: Context): Long? = AndroidEnvironment(context.applicationContext).liveCallBeganAt()

    /** The call in progress began now (it rang, or was dialled), seen by this process. */
    fun noteCallBegan(context: Context, atMillis: Long = System.currentTimeMillis()) {
        edit(context) { putLong(KEY_LIVE_CALL_BEGAN_AT, atMillis) }
    }

    /** The staging file of the session the app last handed to the recorder, or null (read by the daemon launcher). */
    fun sessionStagingPath(context: Context): String? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION_STAGING, null)
    }.getOrNull()

    /** Whether the staged session's own call is over (read by the daemon launcher before it spares a daemon). */
    fun sessionCallEnded(context: Context): Boolean = runCatching {
        val env = AndroidEnvironment(context.applicationContext)
        OrphanSessionPolicy.orphanCallEnded(env.orphanStartedAt(null), env.lastNoCallAt(), env.liveCallBeganAt())
    }.getOrDefault(false)

    /** The app handed [staging] to the recorder for a session it now owns. */
    fun noteSessionStarted(context: Context, staging: File?, isAppCall: Boolean = false) {
        synchronized(sessionRecordLock) {
            runCatching {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                    if (staging != null) putString(KEY_SESSION_STAGING, staging.absolutePath) else remove(KEY_SESSION_STAGING)
                    putBoolean(KEY_SESSION_APP_CALL, isAppCall && staging != null)
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

        override fun clearSessionRecord(keep: File?) {
            synchronized(sessionRecordLock) {
                val recorded = prefs.getString(KEY_SESSION_STAGING, null) ?: return
                if (keep != null && File(recorded).absolutePath == keep.absolutePath) return
                edit(context) { remove(KEY_SESSION_STAGING) }
            }
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
            // Null (unknown) for a missing permission, a failed query or a null cursor: never read as "no later call".
            return com.baba.callvault.data.health.CallLogReader.entriesSinceOrNull(context, sinceMillis)?.isNotEmpty()
        }

        override fun orphanWasAppCall(): Boolean = runCatching { prefs.getBoolean(KEY_SESSION_APP_CALL, false) }.getOrDefault(false)

        override fun markOrphanStopped(file: File, callEnded: Boolean, ranOnSeconds: Int, appCall: Boolean): Boolean =
            StagingNote.markOrphanStop(file, if (callEnded) OrphanStop.CALL_ENDED else OrphanStop.STOPPED, ranOnSeconds, appCall)

        override fun daemonFinalisedOrphan(exclude: File?): DaemonFinalised? {
            val file = prefs.getString(KEY_SESSION_STAGING, null)?.let(::File) ?: return null
            if (file.absolutePath == exclude?.absolutePath || !file.exists() || file.length() <= 0L) return null
            val note = StagingNote.fileFor(file).takeIf { it.exists() }
                ?.let { runCatching { StagingNote.decode(it.readText()) }.getOrNull() } ?: return null
            if (note.orphanStop != OrphanStop.NONE || com.baba.callvault.system.storage.LiveStagingFiles.isLive(file)) return null
            return DaemonFinalised(file, file.lastModified(), note.startedAtMillis)
        }

        override fun daemonSelfStopAt(): Long? = runCatching {
            RecorderConnection.service?.diagnosticDump(com.baba.callvault.server.RecorderServiceImpl.SELF_STOP_DUMP_KEY, null)
                ?.trim()?.removePrefix("self_stop_at=")?.toLongOrNull()
        }.getOrNull()

        override fun callEndedAt(): Long? = runCatching { prefs.getLong(KEY_CALL_ENDED_AT, 0L) }.getOrDefault(0L).takeIf { it > 0L }

        override fun scheduleRescueNow(file: File) = CutOffRescueWorker.scheduleConfirmed(context, file)

        override fun record(line: String) = CallEndLog.record(context, line)
    }
}
