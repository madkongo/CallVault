/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.app.usage.UsageStatsManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baba.callvault.data.health.CallLogReader
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.services.recording.aftercall.AfterCallDecision
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.data.health.CallLogEntry
import com.baba.callvault.services.recording.OrphanRecorderSession
import com.baba.callvault.utils.AppLogger
import java.io.File
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/**
 * Saves the part of a call that was recorded before the phone killed CallVault (backlog #4 + #5).
 *
 * A recording is staged in private storage and published only when the call ends. If the app process dies
 * mid-call, the publish never happens: a tester's OnePlus killed CallVault 13 minutes into a call on
 * 2026-09-28 and the audio sat where he could not reach it, with no word that anything was lost.
 *
 * Runs [DELAY_MS] after the app starts and after every call ends — the app can be restarted *during* the
 * call it lost, so a start-up check alone would see the file still in use and skip it for good.
 * [CutOffRescuePolicy] decides; this only does the file work. It never deletes a file that holds audio:
 * a failed publish leaves the staged file where it is, for the next run.
 */
class CutOffRescueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        inputData.getString(KEY_OBSERVE_NAME)?.let { return retryObserve(it) }
        val requestedPath = inputData.getString(KEY_CONFIRMED_FILE)
        // Only a request made by scheduleConfirmed carries KEY_ATTEMPT. The ordinary quiet-wait request does not, and must
        // never restart the 15 s chain (that made an endless cycle through a long next call).
        val confirmedRun = inputData.keyValueMap.containsKey(KEY_ATTEMPT)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        // N37: an app idle in the rare or restricted bucket can lose network, loopback included. Say which bucket the
        // job ran in, so a report of a rescue that could not reach the recorder host can be read against it.
        logStandbyBucket(applicationContext)
        // First: a recorder still recording for a process that is gone keeps its file "in use" for ever, so
        // nothing here could ever save it (C2). Stopped when its call is over; kept when it may be live (Q25).
        runCatching { com.baba.callvault.services.recording.OrphanRecorderSession.settle(applicationContext, settleTrigger(inputData.getBoolean(KEY_CALL_END_CHECK, false))) }
            .onFailure { AppLogger.e(TAG, "Orphan recorder check failed: ${it.message}", it) }
        sweepStaleTemps(applicationContext)
        val dir = SafHelper.stagingDirOrNull(applicationContext) ?: return Result.success()
        val files = dir.listFiles().orEmpty()
        // A note whose recording is gone was left by a normal finish (callers delete only the staged file).
        files.filter { it.name.endsWith(StagingNote.SUFFIX) && !File(dir, it.name.removeSuffix(StagingNote.SUFFIX)).exists() }
            .forEach { runCatching { it.delete() } }
        // A note write cut short by a kill leaves its temp; a live write lasts milliseconds, so a minute-old one is stray.
        files.filter { StagingNote.isStrayNoteTemp(it) && System.currentTimeMillis() - it.lastModified() > STRAY_NOTE_TEMP_MS }
            .forEach { runCatching { it.delete() } }
        val staged = files.filter { SafHelper.isStagedRecording(it) }
        if (requestedPath != null && staged.none { sameFile(it, requestedPath) }) {
            CallEndLog.record(
                applicationContext,
                "cut-off rescue: the file queued for an instant rescue (${CallEndLog.safeName(File(requestedPath).name)}) is no longer staged; " +
                    "another run published it, or it is gone",
            )
        }
        if (staged.isEmpty()) {
            clearBusy(applicationContext)
            return Result.success()
        }
        val callActive = isCallActive()
        var waiting = false
        var markedWaiting = false
        var waitingForCall = false
        staged.forEach { file ->
            val result = rescue(file, callActive, logDeferral = confirmedRun && attempt == 0)
            if (result.decision == RescueDecision.WAIT) {
                if (result.marked) markedWaiting = true else waiting = true
                if (callActive) waitingForCall = true
            }
            // Outside the lock: the library entry, Drive copy and transcription may be slow and need no exclusion.
            result.published?.let { p -> afterPublished(applicationContext, p.fileName, p.uri, p.mimeType, p.bytes, p.startedAt, p.cutAt, p.savedInFull, p.ranOnSeconds, p.appCall) }
        }
        // A "call up" that never ends (a stuck audio mode) must not wake the phone every two minutes for ever: after an
        // hour the ordinary request backs off to 30 minutes, until the next call end (schedule()) resets it.
        val delayMs = busyDelay(applicationContext, waitingForCall)
        // Something may still be in use; look again once it has had time to go quiet.
        if (waiting) requeueQuiet(applicationContext, delayMs)
        if (markedWaiting) {
            when {
                !confirmedRun -> requeueQuiet(applicationContext, delayMs) // an ordinary run only ever queues the ordinary request
                CutOffRescuePolicy.handOverToQuietWait(attempt) -> {
                    CallEndLog.record(applicationContext, "cut-off rescue: a stopped recording still looks busy after ${attempt + 1} re-checks; the ordinary quiet-wait rescue takes over")
                    requeueQuiet(applicationContext, delayMs)
                }
                else -> scheduleConfirmed(applicationContext, null, attempt + 1)
            }
        }
        return Result.success()
    }

    /** A saved-in-full call whose call-log entry was not there yet (log lag): look again, bounded. */
    private fun retryObserve(name: String): Result {
        val started = inputData.getLong(KEY_OBSERVE_STARTED, -1L).takeIf { it >= 0 }
        val cutAt = inputData.getLong(KEY_OBSERVE_CUT, 0L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        val call = runCatching { matchCall(applicationContext, started, cutAt, completeCall = true) }.getOrNull()
        if (call != null) {
            runCatching { SetupHealthStore(applicationContext).recordSavedInFull(call.startedAt, call.startedAt + call.durationSeconds * 1_000L) }
            CallEndLog.record(applicationContext, "cut-off rescue: the call of ${CallEndLog.safeName(name)} showed in the call log on re-check $attempt; marked observed")
        } else if (attempt < OBSERVE_MAX_ATTEMPTS) {
            scheduleObserve(applicationContext, name, started, cutAt, attempt + 1)
        } else {
            CallEndLog.record(applicationContext, "cut-off rescue: no call-log entry for ${CallEndLog.safeName(name)} after $attempt re-checks; giving up")
        }
        return Result.success()
    }

    private fun sameFile(file: File, path: String): Boolean =
        runCatching { file.canonicalPath == File(path).canonicalPath }.getOrDefault(file.absolutePath == path)

    private class Published(
        val fileName: String, val uri: Uri, val mimeType: String, val bytes: Long, val startedAt: Long?, val cutAt: Long, val savedInFull: Boolean,
        val ranOnSeconds: Int = 0, val appCall: Boolean = false,
    )

    private class RescueResult(val decision: RescueDecision, val marked: Boolean, val published: Published? = null)

    /**
     * Check, publish and delete of one file, one run at a time: the instant request and the quiet-wait one may run side
     * by side and the same file must never be published twice. Only this step is locked, never the hand-offs.
     */
    private suspend fun rescue(file: File, callActive: Boolean, logDeferral: Boolean): RescueResult {
        if (!runLock.tryLock()) {
            CallEndLog.record(applicationContext, "cut-off rescue: waiting for another rescue run to finish with ${CallEndLog.safeName(file.name)}")
            runLock.lock()
        }
        try {
            return rescueLocked(file, callActive, logDeferral)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun rescueLocked(file: File, callActive: Boolean, logDeferral: Boolean): RescueResult {
        if (!file.exists()) return RescueResult(RescueDecision.DISCARD_EMPTY, marked = false) // published by the other run
        val noteFile = StagingNote.fileFor(file)
        val note = noteFile.takeIf { it.exists() }?.let { runCatching { StagingNote.decode(it.readText()) }.getOrNull() }
        val marked = note != null && note.orphanStop != OrphanStop.NONE
        val owned = LiveStagingFiles.isLive(file)
        val ageMs = System.currentTimeMillis() - file.lastModified()
        val decision = CutOffRescuePolicy.decide(
            hasNote = note != null,
            sizeBytes = file.length(),
            ageMs = ageMs,
            callActive = callActive,
            ownedByLiveRecording = owned,
            confirmedStopped = marked,
        )
        var published: Published? = null
        when (decision) {
            RescueDecision.WAIT -> {
                AppLogger.d(
                    TAG,
                    if (owned) "${file.name} belongs to a recording still running here (paused, or a call with no call mode); leaving it"
                    else "${file.name} may still be in use; looking again later",
                )
                // Once per confirmed chain, and once more when "a call is up" first becomes the reason: every re-check
                // logging it would push a live call's lines out of the log.
                val firstCallReason = marked && callActive && !owned && !logDeferral && markCallDeferralLogged(applicationContext)
                if (marked && (logDeferral || firstCallReason)) CallEndLog.record(
                    applicationContext,
                    "cut-off rescue: the stopped recording ${CallEndLog.safeName(file.name)} is deferred " + when {
                        owned -> "because a live recording here owns it"
                        callActive -> "because a call is up"
                        else -> "until it has been quiet ${CutOffRescuePolicy.CONFIRMED_QUIET_MS / 1000} s (quiet ${ageMs / 1000} s so far)"
                    },
                )
            }
            RescueDecision.DISCARD_EMPTY -> {
                AppLogger.i(TAG, "${file.name} is empty; removing it")
                CallEndLog.record(
                    applicationContext,
                    "cut-off rescue: ${CallEndLog.safeName(file.name)} holds no audio (${file.length()} bytes, under ${CutOffRescuePolicy.NO_AUDIO_BELOW_BYTES}); removed, not published",
                )
                runCatching { file.delete(); noteFile.delete() }
            }
            RescueDecision.SAVE_NAMED -> note?.let {
                published = publish(file, noteFile, it, savedInFull = it.orphanStop == OrphanStop.CALL_ENDED)
            }
            // No note beside it: we cannot tell an interrupted recording from a normally-finished call's
            // orphaned temp, so we say nothing and touch nothing. Left in place (may hold old-build audio).
            RescueDecision.LEAVE_UNATTRIBUTED ->
                AppLogger.d(TAG, "${file.name} has no recovery note; leaving it, not reporting a cut-off")
        }
        return RescueResult(decision, marked, published)
    }

    /** Publishes [file] and removes it; null when it did not reach the folder (kept, or moved into waiting storage). */
    private suspend fun publish(file: File, noteFile: File, note: StagingNote, savedInFull: Boolean): Published? {
        val bytes = file.length()
        val fileName = note.fileName
        val uri = publishStaged(applicationContext, note.folderUri.toUri(), fileName, note.mimeType, file)
        if (uri == null) {
            // Review 2026-11-20: retrying the folder its note names for ever is what kept a call from a lost folder out
            // of reach. Moved into waiting storage instead, it is saved into whichever folder is usable later, counted on
            // Home and in the alert. If the move fails it stays here for the next try, as before. The note, with its
            // saved-in-full mark, moves along (WaitingRecordings.adopt copies it).
            if (WaitingRecordings.adopt(applicationContext, file, fileName, note.mimeType, "cut-off recording", finished = true) != null) {
                AppLogger.w(TAG, "Could not save the cut-off recording ${file.name} as '$fileName'; moved into waiting storage")
                return null
            }
            AppLogger.e(TAG, "Could not save the cut-off recording ${file.name} as '$fileName'; keeping it for the next try")
            return null
        }
        AppLogger.w(TAG, "Saved a recording cut off when CallVault was closed: '$fileName' ($bytes bytes) -> $uri")
        val cutAt = file.lastModified()
        runCatching { file.delete(); noteFile.delete() }
        return Published(fileName, uri, note.mimeType, bytes, note.startedAtMillis, cutAt, savedInFull, note.ranOnSeconds, note.appCall)
    }

    private fun isCallActive(): Boolean = runCatching {
        val mode = applicationContext.getSystemService(AudioManager::class.java)?.mode
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        val carrier = applicationContext.getSystemService(TelephonyManager::class.java)?.callState
        inCall || carrier == TelephonyManager.CALL_STATE_OFFHOOK
    }.getOrDefault(true) // unknown reads as "in a call": waiting costs nothing, touching a live file could

    companion object {
        private const val TAG = "CV:CutOffRescue"
        private const val STRAY_NOTE_TEMP_MS = 60_000L

        /** The Drive copy, details-file copy and transcription a normal call gets after it is saved. A seam for tests. */
        internal val defaultHandOffs: (Context, String, Uri, String) -> Unit = AfterCallDecision::runHeldHandOffs
        internal var handOffs: (Context, String, Uri, String) -> Unit = defaultHandOffs

        /**
         * Everything that follows a successful publish. Into the library (Home's list follows the catalog live), into
         * the health card (the call this belongs to is no longer "not recorded", it was cut off), and then the hand-offs
         * every other saved call gets: without them a rescued recording in Drive mode was never copied and was later
         * aged out as the only copy, and never transcribed (review C46). Each hand-off is on its own inside
         * [AfterCallDecision.runHeldHandOffs]; a failure here is logged and never undoes the save.
         */
        internal suspend fun afterPublished(
            context: Context, fileName: String, uri: Uri, mimeType: String, bytes: Long, startedAt: Long?, cutAt: Long,
            completeCall: Boolean = false, ranOnSeconds: Int = 0, appCall: Boolean = false,
        ) {
            RecordingCatalog.recordLocal(context, fileName, uri, bytes, startedAt ?: cutAt)
            RescuedDuration.store(context, fileName, uri)
            recordSaved(context, fileName, startedAt, cutAt, completeCall, ranOnSeconds, appCall)
            runCatching { handOffs(context, fileName, uri, mimeType) }
                .onSuccess { CallEndLog.record(context, "cut-off rescue: '$fileName' handed to Drive copy and transcription") }
                .onFailure {
                    CallEndLog.record(context, "cut-off rescue: hand-off of '$fileName' FAILED ${CallEndLog.describe(it)}; it stays in the library")
                    AppLogger.w(TAG, "Hand-off of the rescued recording failed: ${it.message}")
                }
        }

        /**
         * Whether the staging folder holds anything the rescue could act on: a staged recording, a leftover note, or
         * (R34) a merge temp old enough to remove, in staging or in [mergeTempDir].
         */
        internal fun hasStagedWork(stagingDir: File?, mergeTempDir: File? = null, nowMs: Long = System.currentTimeMillis()): Boolean =
            stagingDir?.listFiles().orEmpty().any { SafHelper.isStagedRecording(it) || it.name.endsWith(StagingNote.SUFFIX) } ||
                StaleTempSweep.hasStale(stagingDir, mergeTempDir, nowMs)

        /** What the sweep leaves behind a killed merge, and the always-on line saying what it did (R34). Never throws. */
        internal fun sweepStaleTemps(context: Context) {
            runCatching {
                val r = StaleTempSweep.run(SafHelper.stagingDirOrNull(context), mergeTempDir(context), System.currentTimeMillis())
                if (r.anythingToSay) {
                    CallEndLog.record(
                        context,
                        "temp sweep: removed ${r.mergeStageDeleted} merge temp(s) from staging and ${r.mergeTempDeleted} merge download(s) " +
                            "from cache (${r.bytesFreed} bytes); ${r.noteLessStagedCount} staged recording(s) with no note kept " +
                            "(${r.noteLessStagedBytes} bytes)",
                    )
                }
            }.onFailure {
                CallEndLog.record(context, "temp sweep FAILED ${CallEndLog.describe(it)}")
                AppLogger.w(TAG, "Temp sweep failed: ${it.message}")
            }
        }

        private fun mergeTempDir(context: Context): File? = File(context.cacheDir, "merge-temp").takeIf { it.isDirectory }

        /**
         * App start (review R17): queue the rescue only when something is staged. Queuing it unconditionally made a
         * phone that kills cached apps within minutes restart CallVault every two minutes, for a job with nothing to
         * do. Call end and the orphan-recorder check still queue it themselves. If the folder cannot be read, queue
         * as before.
         */
        fun scheduleAtStartIfNeeded(context: Context) {
            val needed = runCatching { hasStagedWork(SafHelper.stagingDirOrNull(context), mergeTempDir(context)) }.getOrDefault(true)
            if (needed) schedule(context) else AppLogger.d(TAG, "Nothing staged at start; rescue check not queued")
        }

        internal fun standbyBucketName(bucket: Int): String = when (bucket) {
            5 -> "EXEMPTED"
            10 -> "ACTIVE"
            20 -> "WORKING_SET"
            30 -> "FREQUENT"
            40 -> "RARE"
            45 -> "RESTRICTED"
            50 -> "NEVER"
            else -> "unknown($bucket)"
        }

        private fun logStandbyBucket(context: Context) {
            val bucket = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runCatching "n/a"
                standbyBucketName(context.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            }.getOrElse { "unreadable" }
            CallEndLog.record(context, "cut-off rescue job started (standby bucket $bucket)")
        }

        /**
         * After a cut-off recording is in the library: the health card (the call it belongs to was cut off, not "not
         * recorded") and the notice. Shared with the waiting publisher, which saves a cut-off the same way (F3).
         */
        fun recordSaved(
            context: Context, fileName: String, startedAt: Long?, cutAt: Long, completeCall: Boolean = false,
            ranOnSeconds: Int = 0, appCall: Boolean = false,
        ) {
            val call = matchCall(context, startedAt, cutAt, completeCall)
            AppLogger.i(TAG, "The cut-off recording belongs to ${call?.let { "the call at ${it.startedAt}" } ?: "no call in the call log"}")
            if (call == null) {
                CallEndLog.record(
                    context,
                    "cut-off rescue: ${CallEndLog.safeName(fileName)} matches no call in the call log" + (if (appCall) " (an app call never does)" else "") +
                        if (completeCall) "; saved in full, so the status card gets no update for it yet" else "",
                )
                // The call log may lag the call's end: look again a few times (saved in full only; the cut-off path is as before).
                // Not for an old file (a rescue long after its call): log lag is minutes, a re-check could not match it.
                if (completeCall && System.currentTimeMillis() - cutAt <= OBSERVE_MAX_AGE_MS) scheduleObserve(context, fileName, startedAt, cutAt, 1)
            }
            val callEndedAt = call?.let { it.startedAt + it.durationSeconds * 1_000L }
            runCatching {
                val store = SetupHealthStore(context)
                if (completeCall) store.recordSavedInFull(call?.startedAt, callEndedAt)
                else store.recordCutOff(System.currentTimeMillis(), call?.startedAt, callEndedAt, call?.label, callAt = call?.startedAt ?: startedAt ?: cutAt)
            }.onFailure { AppLogger.w(TAG, "Could not update the status card: ${it.message}") }
            if (!completeCall) {
                SilentFailureNotifier.noteCutOffRecordingSaved(context, fileName)
                return
            }
            // The recorder kept going through the app's death and the phone call's end is inside the file: say so.
            SilentFailureNotifier.noteCallSavedAfterRestart(context, fileName, ranOnSeconds)
        }
        private const val WORK_NAME = "cut_off_rescue"
        private const val STATE_PREFS = "cut_off_rescue_state"
        private const val KEY_BUSY_SINCE = "busy_since"
        private const val KEY_BACKED_OFF = "backed_off"
        private const val KEY_CALL_REASON_LOGGED = "call_reason_logged"

        private fun state(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        /** True the first time in a busy spell; false after. */
        private fun markCallDeferralLogged(context: Context): Boolean = runCatching {
            val s = state(context)
            if (s.getBoolean(KEY_CALL_REASON_LOGGED, false)) false else { s.edit().putBoolean(KEY_CALL_REASON_LOGGED, true).apply(); true }
        }.getOrDefault(false)

        private fun clearBusy(context: Context) {
            runCatching { state(context).edit().remove(KEY_BUSY_SINCE).remove(KEY_BACKED_OFF).remove(KEY_CALL_REASON_LOGGED).apply() }
        }

        /** The ordinary request's delay for this run, tracking how long files have waited for a call. Never throws. */
        internal fun busyDelay(context: Context, waitingForCall: Boolean): Long = runCatching {
            if (!waitingForCall) { clearBusy(context); return@runCatching DELAY_MS }
            val s = state(context)
            val now = System.currentTimeMillis()
            val since = s.getLong(KEY_BUSY_SINCE, 0L).takeIf { it > 0L } ?: now.also { s.edit().putLong(KEY_BUSY_SINCE, it).apply() }
            if (!CutOffRescuePolicy.shouldBackOff(since, now)) return@runCatching DELAY_MS
            if (!s.getBoolean(KEY_BACKED_OFF, false)) {
                s.edit().putBoolean(KEY_BACKED_OFF, true).apply()
                CallEndLog.record(context, "cut-off rescue: files have waited for a call to end for over an hour; checking every ${CutOffRescuePolicy.BUSY_BACKOFF_DELAY_MS / 60_000} min until the next call ends")
            }
            CutOffRescuePolicy.BUSY_BACKOFF_DELAY_MS
        }.getOrDefault(DELAY_MS)

        internal fun isBackedOff(context: Context): Boolean = state(context).getBoolean(KEY_BACKED_OFF, false)
        private const val WORK_NAME_OBSERVE = "cut_off_rescue_observe"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_OBSERVE_NAME = "observe_name"
        private const val KEY_OBSERVE_STARTED = "observe_started"
        private const val KEY_OBSERVE_CUT = "observe_cut"
        private const val OBSERVE_MAX_ATTEMPTS = 3
        private const val OBSERVE_RETRY_MS = 2 * 60_000L
        private const val OBSERVE_MAX_AGE_MS = 30 * 60_000L
        private const val WORK_NAME_CONFIRMED = "cut_off_rescue_confirmed"
        private const val KEY_CONFIRMED_FILE = "confirmed_file"
        private val runLock = Mutex()

        /** Writes a staged file into its folder; a seam for tests. */
        internal val defaultPublishStaged: (Context, Uri, String, String, File) -> Uri? = SafHelper::publishStagedRecording
        internal var publishStaged: (Context, Uri, String, String, File) -> Uri? = defaultPublishStaged

        /** How far back the call log is read to find the cut-off call: longer than any plausible call. */
        private const val CALL_LOOKBACK_MS = 12 * 60 * 60_000L

        /** A little over [CutOffRescuePolicy.QUIET_MS], so a file abandoned just now is quiet by the time we look. */
        private const val DELAY_MS = CutOffRescuePolicy.QUIET_MS + 30_000L

        /**
         * Queues the short-guard rescue of a file the orphan check stopped and marked ([OrphanStop]). Its own unique
         * name, appended, so no later call end (which replaces the quiet-wait request) can push it back.
         *
         * Delayed [CutOffRescuePolicy.CONFIRMED_QUIET_MS] and therefore NOT expedited: WorkManager rejects an expedited
         * request with an initial delay, and the delay is the point (the recorder answers "not recording" before the
         * muxer trailer is written). The file is published only once it has been quiet that long.
         */
        fun scheduleConfirmed(context: Context, file: File?, attempt: Int = 0) {
            runCatching {
                val builder = OneTimeWorkRequestBuilder<CutOffRescueWorker>()
                    .setInitialDelay(CutOffRescuePolicy.CONFIRMED_QUIET_MS, TimeUnit.MILLISECONDS)
                val data = Data.Builder().putInt(KEY_ATTEMPT, attempt)
                if (file != null) data.putString(KEY_CONFIRMED_FILE, file.absolutePath)
                builder.setInputData(data.build())
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_CONFIRMED, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
            }.onFailure {
                // Enqueue failures are mostly asynchronous (they never reach here); the stop path has already queued the
                // quiet-wait run, so this synchronous fallback is only a second line of defence. Kept simple on purpose.
                CallEndLog.record(context, "cut-off rescue: could not queue the instant rescue ${CallEndLog.describe(it)}; the ordinary quiet-wait rescue is queued instead")
                AppLogger.w(TAG, "Could not schedule the instant rescue: ${it.message}")
                requeueQuiet(context, DELAY_MS)
            }
        }

        private fun scheduleObserve(context: Context, name: String, startedAt: Long?, cutAt: Long, attempt: Int) {
            runCatching {
                val data = Data.Builder().putString(KEY_OBSERVE_NAME, name).putLong(KEY_OBSERVE_STARTED, startedAt ?: -1L)
                    .putLong(KEY_OBSERVE_CUT, cutAt).putInt(KEY_ATTEMPT, attempt).build()
                val request = OneTimeWorkRequestBuilder<CutOffRescueWorker>()
                    .setInitialDelay(OBSERVE_RETRY_MS, TimeUnit.MILLISECONDS).setInputData(data).build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_OBSERVE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { CallEndLog.record(context, "cut-off rescue: could not queue the call-log re-check for ${CallEndLog.safeName(name)} ${CallEndLog.describe(it)}") }
        }

        /** A saved-in-full file is matched on "started with the recording, ended before the cut"; a cut-off on "up at the cut". */
        private fun matchCall(context: Context, startedAt: Long?, cutAt: Long, completeCall: Boolean): CallLogEntry? {
            val entries = CallLogReader.entriesSince(context, cutAt - CALL_LOOKBACK_MS)
            return if (completeCall) CutOffRescuePolicy.matchCompleteCall(entries, startedAt, cutAt)
            else CutOffRescuePolicy.matchCall(entries, startedAt, cutAt)
        }

        /** The orphan-check trigger a run uses: the short safety net's own name, else the ordinary rescue's. */
        internal fun settleTrigger(callEndCheck: Boolean): String =
            if (callEndCheck) OrphanRecorderSession.TRIGGER_CALL_END_CHECK else "rescue check"

        /** How long after a phone call ends the safety net runs, should the in-process 3 s check never have run (frozen app). */
        internal const val CALL_END_CHECK_DELAY_MS = 20_000L
        private const val WORK_NAME_CALL_END = "cut_off_rescue_call_end"
        private const val KEY_CALL_END_CHECK = "call_end_check"

        /**
         * A phone call ended with a recording nobody here owns: a second orphan check ~20 s later, from WorkManager, which
         * runs even when the app process was frozen at +3 s (OnePlus 9, 2026-11-22). The 2-minute rescue stays as is.
         */
        fun scheduleCallEndCheck(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CutOffRescueWorker>()
                    .setInitialDelay(CALL_END_CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putBoolean(KEY_CALL_END_CHECK, true).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME_CALL_END, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { CallEndLog.record(context, "cut-off rescue: could not queue the call-end check ${CallEndLog.describe(it)}") }
        }

        /** Looks for cut-off recordings shortly. Safe to call often: the latest request replaces earlier ones. */
        fun schedule(context: Context) {
            clearBusy(context) // a call end, an app start or an orphan stop: back to the normal pace
            requeueQuiet(context, DELAY_MS)
        }

        /** The worker's own re-queue: like [schedule] but keeps the busy tracking (and its back-off). */
        private fun requeueQuiet(context: Context, delayMs: Long) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CutOffRescueWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { AppLogger.w(TAG, "Could not schedule the cut-off recording check: ${it.message}") }
        }
    }
}
