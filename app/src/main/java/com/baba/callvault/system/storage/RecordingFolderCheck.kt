/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.os.UserManager
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.UsbDebuggingEvents
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Measures whether the recording folder can be used, with evidence, off the main thread (review C98, N5).
 *
 * A field report (Motorola, Android 16): the user deleted the recording folder by accident, three Telegram calls
 * were refused with "the recording folder is missing", and Home still said "Ready to record". A stored folder
 * address said nothing about whether the folder was still there.
 *
 * Each check looks at the folder the same way a call does ([SafHelper.isFolderValid]). Only when that fails does it
 * look further, to tell a folder that is really gone from one that is briefly out of reach: is the user's storage
 * unlocked yet, does CallVault still hold the write grant, is the folder's volume (an SD card) mounted, and what did
 * the provider answer. [FolderCheckPolicy] then decides what counts. The result is kept, with its time, so readiness
 * ([com.baba.callvault.data.health.SetupPrerequisites.missing]) can read it without doing any I/O.
 *
 * Every reading that is not "usable", and every change, is logged; changes go to the always-on events file too, so a
 * report says what was seen even with debug logging off.
 */
object RecordingFolderCheck {

    private const val TAG = "CV:FolderCheck"
    private const val PREFS = "recording_folder_check"
    private const val KEY_STATE = "state"

    /** A provider that has not answered within this is counted as slow (transient), not as "folder gone". */
    const val PROVIDER_TIMEOUT_MS = 5_000L

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary"

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cv-folder-check").apply { isDaemon = true } }

    /**
     * One thread for provider queries, and one query at a time: a provider that hangs keeps this thread, and every
     * check meanwhile reads "a previous probe is still waiting" instead of starting another thread (review 2026-11-20:
     * the cached pool leaked a thread per check on a hung provider).
     */
    private val providerCalls = Executors.newSingleThreadExecutor { r -> Thread(r, "cv-folder-probe").apply { isDaemon = true } }
    @Volatile private var outstandingProbe: Future<*>? = null
    private val lock = Any()

    /** Always-on `folder:` lines: at most 12 an hour, so a flapping provider cannot flood the file. */
    private val alwaysOnLimit = AlwaysOnRateLimit(maxPerWindow = 12, windowMs = 60 * 60_000L)

    private var pendingRecheck: ScheduledFuture<*>? = null

    /** Called after every check with its result; set once by the app (the alert lives outside this file). */
    @Volatile var onChecked: ((Context, FolderCheckPolicy.Step) -> Unit)? = null

    private val _changes = MutableStateFlow(0L)

    /**
     * Counts every stored result, whatever stored it (a re-check, the keep-alive tick, the periodic job, a call's end,
     * a folder picked). Home collects it while shown and reads [cached] again on each change, so its status follows
     * the stored answer without a pull-to-refresh (OnePlus 9 Pro, 2026-11-20: the 20 s re-check confirmed the folder
     * gone and alerted, and Home still said "Ready to record"). Its current value is not a change.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun stored() = _changes.update { it + 1 }

    /** The last stored result, or null. A SharedPreferences read: no provider I/O. */
    fun cached(context: Context): FolderCheckState? = runCatching {
        prefs(context).getString(KEY_STATE, null)?.let(FolderCheckState::decode)
    }.onFailure { AppLogger.w(TAG, "Could not read the stored folder check: ${it.message}") }.getOrNull()

    /** Whether the folder set now is known to be gone, from the stored result alone. Never does I/O. */
    fun isKnownUnusable(context: Context, currentUri: Uri?): Boolean =
        FolderCheckPolicy.isUnusable(cached(context), currentUri?.toString(), System.currentTimeMillis())

    /**
     * Looks now, on a background thread; with [skipDuringCall], not while a call is up ([CallGuard]). A skipped check
     * with [retryIfSkippedMs] is tried once more after that delay (the audio mode can lag a hang-up: F2).
     */
    fun checkAsync(context: Context, trigger: String, skipDuringCall: Boolean = false, retryIfSkippedMs: Long? = null) {
        val app = context.applicationContext
        runCatching {
            worker.execute {
                if (!(skipDuringCall && CallGuard.shouldSkip(app, trigger))) {
                    checkNow(app, trigger)
                } else if (retryIfSkippedMs != null) {
                    runCatching { worker.schedule({ checkAsync(app, "$trigger (again)", skipDuringCall = true) }, retryIfSkippedMs, TimeUnit.MILLISECONDS) }
                }
            }
        }
            .onFailure { AppLogger.w(TAG, "Could not start the folder check ($trigger): ${it.message}") }
    }

    /**
     * Looks, on a background thread, only when the stored answer is stale ([FolderWatchPlan.isStale]: older than
     * [maxAgeMs] when good, backing off when bad; [foreground] for the user's own actions, which look at a bad answer
     * again within seconds) and no call is up. No timer of its own: it rides whatever called it. [then] runs on that thread with the result (null: skipped
     * during a call, failed, or another check measured first so the stored answer is fresh already). Cheap to call
     * often: a preference read when nothing is due. Returns whether a look was queued.
     */
    fun checkIfStale(
        context: Context,
        trigger: String,
        maxAgeMs: Long,
        foreground: Boolean = false,
        then: ((FolderCheckPolicy.Step?) -> Unit)? = null,
    ): Boolean {
        val app = context.applicationContext
        fun stale() = FolderWatchPlan.isStale(cached(app), System.currentTimeMillis(), goodMaxAgeMs = maxAgeMs, foreground = foreground)
        if (!stale()) return false
        return runCatching {
            worker.execute {
                // Two callers close together (Home coming into view and its list pass) measure once: the second
                // finds the first one's fresh answer here. Never a provider query while a call is up (review 2026-11-20).
                val step = if (!stale() || CallGuard.shouldSkip(app, trigger)) null else checkNow(app, trigger)
                runCatching { then?.invoke(step) }
            }
        }.onFailure { AppLogger.w(TAG, "Could not start the folder check ($trigger): ${it.message}") }.isSuccess
    }

    /**
     * Measures, decides and stores. Blocking (provider queries): never on the main thread.
     * Never throws; a failure is logged and leaves the stored result as it was.
     */
    fun checkNow(context: Context, trigger: String): FolderCheckPolicy.Step? = runCatching {
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        val reading = measure(app)
        val step = synchronized(lock) {
            val previous = cached(app)
            val step = FolderCheckPolicy.next(previous, reading, startedAt)
            if (step.stale) {
                // Two checks overlapped and the newer one already stored its answer.
                AppLogger.d(TAG, "check ($trigger): a newer reading is already stored; dropping this one (${reading.kind})")
                return@runCatching step
            }
            prefs(app).edit().putString(KEY_STATE, step.state.encode()).apply()
            stored()
            log(app, trigger, previous, reading, step)
            scheduleRecheck(app, step.recheckInMs)
            step
        }
        runCatching { onChecked?.invoke(app, step) }
            .onFailure { AppLogger.w(TAG, "Acting on the folder check failed: ${it.message}") }
        step
    }.onFailure { AppLogger.e(TAG, "The folder check ($trigger) failed: ${it.message}", it) }.getOrNull()

    private fun scheduleRecheck(context: Context, inMs: Long?) {
        pendingRecheck?.cancel(false)
        pendingRecheck = inMs?.let { delay ->
            runCatching { worker.schedule({ checkNow(context, "re-check") }, delay, TimeUnit.MILLISECONDS) }
                .onFailure { AppLogger.w(TAG, "Could not schedule the folder re-check: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * One look at the folder, with what was seen. Blocking. One measurement at a time (second review 2026-11-20, N2):
     * a second caller waits for the first instead of reading its probe, still running, as "hung".
     */
    internal fun measure(context: Context): FolderReading = synchronized(measureLock) { measureLocked(context) }

    private val measureLock = Any()

    private fun measureLocked(context: Context): FolderReading {
        val uri = AppPreferences(context).getRecordingFolderUri()
            ?: return FolderReading(null, FolderReading.Kind.NOT_SET, "no folder set")
        val u = uri.toString()
        val cloud = SafHelper.isCloudFolder(uri)
        fun reading(kind: FolderReading.Kind, evidence: String) =
            FolderReading(u, kind, if (cloud) "cloud folder; $evidence" else evidence, cloud)
        outstandingProbe?.takeIf { !it.isDone }?.let {
            return reading(FolderReading.Kind.TRANSIENT, "a previous probe is still waiting on the folder's provider")
        }
        // The same test a call makes, so "usable" here means exactly what it means when a call starts; and, only when
        // it fails, what the provider says about the folder document. One task, on the one probe thread.
        val probe = providerCalls.submit<Pair<Boolean, String?>> {
            val ok = SafHelper.isFolderValid(context, uri)
            ok to (if (ok) null else providerAnswer(context, uri))
        }
        outstandingProbe = probe
        val (usable, answer) = try {
            probe.get(PROVIDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return reading(FolderReading.Kind.TRANSIENT, "the folder's provider did not answer within $PROVIDER_TIMEOUT_MS ms")
        } catch (e: Exception) {
            return reading(FolderReading.Kind.TRANSIENT, "the usability test threw ${describe(e.cause ?: e)}")
        }
        if (usable) return reading(FolderReading.Kind.OK, "exists and writable")

        val unlocked = runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull()
        if (unlocked == false) return reading(FolderReading.Kind.TRANSIENT, "storage is still locked after a reboot")
        val detail = answer ?: "$PROVIDER_FAILED no answer"
        val grantHeld = runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }.getOrNull()
        val volume = volumeState(context, uri)
        val relativePath = primaryRelativePath(uri)
        val base = FolderCheckPolicy.NotUsableEvidence(
            unlocked = unlocked,
            grantHeld = grantHeld,
            volume = volume,
            providerFailed = detail.startsWith(PROVIDER_FAILED),
            localPrimary = !cloud && relativePath != null,
        )
        // A provider that gave no answer about a folder on the phone's own storage gets a second opinion from the
        // path itself (OnePlus 9 Pro, 2026-11-20: a deleted folder there answers with a null cursor).
        val pathResult = if (FolderCheckPolicy.shouldLookAtPath(base) && relativePath != null) {
            FolderPathLook.look(Environment.getExternalStorageDirectory(), relativePath)
        } else {
            null
        }
        val evidence = base.copy(path = pathResult?.look ?: PathLook.NOT_LOOKED)
        val pathNote = pathResult?.let { "; path: ${it.look} (${it.evidence})" }.orEmpty()
        val kind = FolderCheckPolicy.classify(evidence)
        return when {
            kind == FolderReading.Kind.GRANT_MISSING ->
                reading(kind, "CallVault no longer holds write access to the folder; provider: $detail")
            volume != null && volume != Environment.MEDIA_MOUNTED ->
                reading(kind, "the folder's storage is $volume; provider: $detail")
            else -> reading(kind, "grant held=${grantHeld ?: "?"}, storage=${volume ?: "n/a"}; provider: $detail$pathNote")
        }
    }

    /** The folder's path below the primary storage root, when it is on the phone's own storage provider; else null. */
    private fun primaryRelativePath(treeUri: Uri): String? = runCatching {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching null
        FolderPathLook.relativePathOnPrimary(DocumentsContract.getTreeDocumentId(treeUri))
    }.getOrNull()

    private const val PROVIDER_FAILED = "failed:"

    /**
     * What the provider says about the folder document itself, for the log and to separate "no such folder" (a
     * definite answer) from a provider that failed (which may pass). Starts with [PROVIDER_FAILED] for the latter.
     */
    private fun providerAnswer(context: Context, treeUri: Uri): String = try {
        run {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            context.contentResolver.query(
                doc, arrayOf(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
            ).use { c ->
                when {
                    c == null -> "${PROVIDER_FAILED} no answer (null cursor)"
                    !c.moveToFirst() -> "no such folder (empty answer)"
                    else -> {
                        val flags = c.getInt(0)
                        val isDir = c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                        val canCreate = flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
                        "present, directory=$isDir, can create files=$canCreate"
                    }
                }
            }
        }
    } catch (e: Exception) {
        val cause = e.cause ?: e
        val text = describe(cause)
        // ExternalStorageProvider reports a deleted folder as "Missing file for …" (FileNotFoundException), often
        // wrapped in an IllegalArgumentException. Anything else is a provider failure, not proof the folder is gone.
        if (cause is java.io.FileNotFoundException || text.contains("FileNotFoundException") || text.contains("Missing file")) {
            "no such folder ($text)"
        } else {
            "$PROVIDER_FAILED $text"
        }
    }

    /**
     * The mount state of the volume holding a folder served by the phone's own storage provider, "absent" when that
     * volume is not attached at all, or null for any other provider (nothing to say).
     */
    private fun volumeState(context: Context, treeUri: Uri): String? = runCatching {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching null
        val volumeId = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        if (volumeId.equals(PRIMARY_VOLUME, ignoreCase = true)) return@runCatching Environment.getExternalStorageState()
        val volumes = context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
        volumes.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }?.state ?: "absent"
    }.getOrNull()

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": " + it.take(MAX_MESSAGE).replace('\n', ' ') } ?: "")

    private const val MAX_MESSAGE = 160

    private fun log(context: Context, trigger: String, previous: FolderCheckState?, reading: FolderReading, step: FolderCheckPolicy.Step) {
        val verdict = when {
            step.alert -> "GONE (confirmed now)"
            step.state.confirmedUnusable -> "gone (confirmed earlier)"
            step.cleared -> "usable again"
            reading.kind == FolderReading.Kind.OK || reading.kind == FolderReading.Kind.NOT_SET -> "usable"
            else -> "not confirmed yet, looking again in ${step.recheckInMs?.let { "${it / 1000} s" } ?: "?"}"
        }
        val line = "check ($trigger): ${reading.kind} [${reading.evidence}] -> $verdict"
        val changed = previous?.kind != reading.kind || previous.confirmedUnusable != step.state.confirmedUnusable
        if (reading.kind == FolderReading.Kind.OK && !changed) {
            AppLogger.d(TAG, line)
            return
        }
        AppLogger.i(TAG, line)
        if (changed || step.alert) recordAlwaysOn(context, line, important = step.alert || step.cleared || step.state.confirmedUnusable)
    }

    /** A line in the always-on events file (kept with debug logging off), redacted like every other one there. */
    fun recordAlwaysOn(context: Context, line: String, important: Boolean = false) {
        runCatching {
            // A confirmed loss, a cleared one and every alert are never dropped (second review 2026-11-20, N1); the limit
            // is for a provider that flaps.
            val dropped = if (important) 0 else synchronized(alwaysOnLimit) { alwaysOnLimit.take(SystemClock.elapsedRealtime()) } ?: return
            val note = if (dropped > 0) " ($dropped earlier folder lines dropped by the rate limit)" else ""
            UsbDebuggingEvents.record(context, "folder: " + AppLogger.redactForReport(line.replace(Regex("[\\r\\n]+"), " ")) + note)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Waits until the check's thread has run everything queued so far (the app's own "app start" check among it): for
     * tests, so that check cannot overwrite what a test arranges.
     */
    internal fun awaitIdleForTest(timeoutMs: Long = 10_000L) {
        FolderProtection.awaitInstalledForTest(timeoutMs)
        worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Stores a result as if measured: for tests. */
    internal fun storeForTest(context: Context, state: FolderCheckState) {
        prefs(context).edit().putString(KEY_STATE, state.encode()).commit()
        stored()
    }

    /** Forgets the stored result: for tests. */
    internal fun clearForTest(context: Context) {
        prefs(context).edit().clear().commit()
        stored()
    }
}
