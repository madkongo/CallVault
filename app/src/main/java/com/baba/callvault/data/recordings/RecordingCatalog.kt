/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import android.net.Uri
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.data.transcripts.TranscriptCascade
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * App-facing facade over the recordings catalog ([RecordingEntry] / Room). This is CallVault's source
 * of truth for which recordings exist and where each copy lives, so the Home list never has to query a
 * cloud provider's (Google Drive's) eventually-consistent folder listing.
 *
 * Lifecycle of a row:
 *  1. [recordLocal] — written when a recording finishes (the file is on the device).
 *  2. [markDrive]   — the post-call copy ([com.baba.callvault.system.storage.RecordingCopyWorker]) or the
 *                     scheduled sweep ([com.baba.callvault.system.storage.SyncSweepWorker]) stamps the
 *                     Drive copy onto the same row by name (clearing the local copy for DRIVE-only mode).
 *  3. [removeName] / [removeCopyByUri] — when the user deletes a recording (all copies, or one copy).
 *
 * For pre-existing recordings made before the catalog existed, [importIfEmpty] seeds the table once from
 * a folder scan. All operations are best-effort and never throw; on a DB failure they log and move on.
 */
object RecordingCatalog {

    private const val TAG = "CV:RecordingCatalog"

    private fun dao(context: Context) = RecordingDatabase.get(context).recordingDao()

    /**
     * Records (or refreshes) the device-folder copy of a finished recording, preserving any Drive copy
     * already stamped on the same-named row. Called at record-finish for every storage target.
     */
    suspend fun recordLocal(context: Context, displayName: String, localUri: Uri, sizeBytes: Long, lastModified: Long) {
        recordLocalReporting(context, displayName, localUri, sizeBytes, lastModified)
    }

    /**
     * [recordLocal], returning what went wrong: null when the row was written, else the exception that
     * [recordLocal] swallows. For the end-of-call log, which has to say whether the write really happened.
     */
    suspend fun recordLocalReporting(
        context: Context, displayName: String, localUri: Uri, sizeBytes: Long, lastModified: Long
    ): Throwable? {
        return runCatching {
            val dao = dao(context)
            val existing = dao.findByName(displayName)
            val merged = (existing ?: RecordingEntry(displayName)).copy(
                localUri = localUri.toString(),
                localSizeBytes = sizeBytes.takeIf { it > 0L },
                lastModified = lastModified.takeIf { it > 0L } ?: existing?.lastModified ?: 0L
            )
            dao.upsert(merged)
        }.onFailure { AppLogger.w(TAG, "recordLocal('$displayName') failed: ${it.message}") }.exceptionOrNull()
    }

    /**
     * Stamps the Drive copy onto the recording named [displayName] (creating a Drive-only row if the
     * recording is not yet catalogued). When [deleteLocalAfter] is true (DRIVE-only mode), the local
     * copy reference is cleared because the worker deletes the on-device file after copying.
     *
     * [lastModified] is only consulted when a Drive-ONLY row has to be created, where there is no device
     * copy to have supplied one. It matters because a row with no timestamp is one the retention sweep
     * refuses to age out (it will not delete a recording whose age it cannot establish), so a Drive-only
     * row created without it would never expire. The sync path leaves it at 0 as before: it always stamps
     * Drive onto a row [recordLocal] has already dated.
     */
    suspend fun markDrive(
        context: Context,
        displayName: String,
        driveUri: Uri,
        driveSizeBytes: Long?,
        deleteLocalAfter: Boolean,
        lastModified: Long = 0L,
    ) = withContext(NonCancellable) {
        // Not cancellable: by the time this runs the file IS in Drive. A worker stopped at this moment
        // ("markDrive failed: Job was cancelled", Honor BVL-N49, 2026-11-03) left the row looking unsynced,
        // which is the symptom the "not reaching Drive" warning counts. It is one small database write.
        // The Drive sweep uploads sidecars too (.md transcripts, .json details; GitHub #49), but they are
        // not recordings: a row for one shows up in retention, the health check and the library
        // (BabApps item 6). The upload itself is untouched; only the catalog entry is skipped.
        // Narrow on purpose: only a sidecar extension is skipped. A recording whose extension is not on the
        // audio list (the folder scan also accepts audio by MIME type) must still get its Drive stamp, or
        // the health check warns that recordings are not reaching Drive when they are.
        if (isSidecarFile(displayName)) return@withContext
        runCatching {
            val dao = dao(context)
            val size = driveSizeBytes?.takeIf { it > 0L }
            if (dao.findByName(displayName) == null) {
                dao.upsert(
                    RecordingEntry(
                        displayName = displayName,
                        driveUri = driveUri.toString(),
                        driveSizeBytes = size,
                        lastModified = lastModified.takeIf { it > 0L } ?: 0L,
                    )
                )
            } else {
                dao.setDrive(displayName, driveUri.toString(), size)
                if (deleteLocalAfter) dao.clearLocal(displayName)
            }
        }.onFailure { AppLogger.w(TAG, "markDrive('$displayName') failed: ${it.message}") }
        Unit
    }

    /**
     * Remembers [durationSeconds] for [displayName] so the file never has to be opened for it again.
     *
     * Only ever called with a positive value: a failed read stays uncached so a transient failure
     * cannot become a permanent wrong answer, which is the behaviour before this cache existed.
     */
    suspend fun setDuration(context: Context, displayName: String, durationSeconds: Long) {
        runCatching { dao(context).setDuration(displayName, durationSeconds) }
            .onFailure { AppLogger.w(TAG, "setDuration('$displayName') failed: ${it.message}") }
    }

    private const val MIGRATIONS_FILE = "cv_catalog_migrations"
    private const val KEY_DRIVE_SIZES_FORGOTTEN = "drive_sizes_unconfirmed_forgotten"

    /**
     * Once per install: forgets the stored Drive size of rows with a device copy (review C7 follow-up).
     *
     * Before C7 every path that stamped a Drive copy recorded the DEVICE size as the Drive size, confirmed by Drive
     * or not, and retention takes an equal Drive size as proof the call reached Drive. Those stamps cannot be told
     * from real ones, so they are dropped on every row with a device copy, the rows retention trusts; the Drive
     * sweep, the folder scan and the Drive repair, which record only sizes Drive listed, re-confirm them. Drive-only
     * rows keep their size: retention never relies on it, nothing would re-confirm it, and the list shows it. The flag is set only after the update succeeds, so a failure retries.
     */
    suspend fun forgetUnconfirmedDriveSizesOnce(context: Context): Boolean =
        forgetUnconfirmedDriveSizesOnceWith(context) { dao(context).clearDeviceRowDriveSizes() }

    /**
     * [forgetUnconfirmedDriveSizesOnce] with the clear passed in, so a test can make it fail. True once the clear
     * has succeeded (now or on an earlier run); false when it failed, which the retention sweep must treat as
     * "old sizes may still be trusted wrongly" and delete no device copy. Cancellation is rethrown.
     */
    internal suspend fun forgetUnconfirmedDriveSizesOnceWith(context: Context, clear: suspend () -> Int): Boolean {
        val state = context.getSharedPreferences(MIGRATIONS_FILE, Context.MODE_PRIVATE)
        if (state.getBoolean(KEY_DRIVE_SIZES_FORGOTTEN, false)) return true
        val cleared = try {
            clear()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not forget the unconfirmed Drive sizes; retrying next time: ${e.message}")
            return false
        }
        // commit(), not apply(): the flag must be on disk before anything trusts the sizes again.
        if (!state.edit().putBoolean(KEY_DRIVE_SIZES_FORGOTTEN, true).commit()) {
            AppLogger.w(TAG, "Forgot $cleared Drive size(s) but could not record it; doing it again next time")
        }
        AppLogger.i(TAG, "Forgot $cleared unconfirmed Drive size(s) from before Drive sizes were checked; a Drive listing re-confirms them")
        return true
    }

    private const val KEY_DRIVE_SIZES_RECONFIRMED = "drive_sizes_reconfirmed_since_forgotten"

    /**
     * Records that a Drive sweep finished after the one-time clear, so the Drive sizes it lists have been stamped
     * again. Until then, retention's "kept for Drive" count would include every expired call whose size was just
     * cleared, and the notice would cry wolf (review C7/N3). A no-op before the clear has happened.
     */
    fun markDriveSizesReconfirmed(context: Context) {
        val state = context.getSharedPreferences(MIGRATIONS_FILE, Context.MODE_PRIVATE)
        if (!state.getBoolean(KEY_DRIVE_SIZES_FORGOTTEN, false) || state.getBoolean(KEY_DRIVE_SIZES_RECONFIRMED, false)) return
        state.edit().putBoolean(KEY_DRIVE_SIZES_RECONFIRMED, true).apply()
        AppLogger.i(TAG, "A Drive sweep finished after the old Drive sizes were cleared; they are re-confirmed")
    }

    /** Whether a Drive sweep has finished since the one-time clear; see [markDriveSizesReconfirmed]. */
    fun areDriveSizesReconfirmed(context: Context): Boolean =
        context.getSharedPreferences(MIGRATIONS_FILE, Context.MODE_PRIVATE).getBoolean(KEY_DRIVE_SIZES_RECONFIRMED, false)

    /** Whether [displayName] has a catalog row; null when the catalog cannot be read. Never throws. */
    suspend fun contains(context: Context, displayName: String): Boolean? =
        runCatching { dao(context).findByName(displayName) != null }.getOrElse {
            AppLogger.w(TAG, "contains() failed: ${it.message}"); null
        }

    /**
     * All catalogued recordings, or null when the catalog could not be read: for callers that must tell "failed"
     * from "empty", such as the retention sweep's "kept for Drive" count (review N3). Never throws.
     */
    suspend fun allOrNull(context: Context): List<RecordingEntry>? =
        runCatching { dao(context).getAll() }.getOrElse {
            AppLogger.w(TAG, "allOrNull() failed: ${it.message}"); null
        }

    /** All catalogued recordings, newest-first. Never throws (returns empty on failure). */
    suspend fun all(context: Context): List<RecordingEntry> =
        runCatching { dao(context).getAll() }.getOrElse {
            AppLogger.w(TAG, "all() failed: ${it.message}"); emptyList()
        }

    /**
     * Removes the recording (all copies) from the catalog, and with it any transcript.
     *
     * The transcript cascade is explicit because transcripts live in a separate database (see
     * [TranscriptCascade]); SQLite cannot do it for us.
     */
    suspend fun removeName(context: Context, displayName: String) {
        runCatching { dao(context).deleteByName(displayName) }
            .onFailure { AppLogger.w(TAG, "removeName('$displayName') failed: ${it.message}") }
        TranscriptCascade.deleteFor(context, listOf(displayName))
    }

    /**
     * Drops the catalog row for [displayName] and **nothing else** — no transcript, no summary, no
     * note, no tag.
     *
     * The one caller is [TranscribeOnlyAudio], where the whole point is that the text survives the
     * audio: the user asked for a file to be transcribed and not kept, so the words are the thing
     * that must still be there afterwards. [removeName] would delete them, which is precisely the
     * cascade it exists to run.
     *
     * **Never use this for a user's delete.** A recording removed this way leaves a searchable
     * transcript of a private call behind it, which is more exposing than the audio it replaced —
     * the defect [TranscriptCascade] was written to prevent.
     */
    suspend fun forgetName(context: Context, displayName: String) {
        runCatching { dao(context).deleteByName(displayName) }
            .onFailure { AppLogger.w(TAG, "forgetName('$displayName') failed: ${it.message}") }
    }

    /**
     * Clears the single copy whose content URI is [uri] (device or Drive) from its row, then drops the
     * row if no copy remains. Mirrors a per-copy delete of a BOTH recording.
     *
     * @return the recording's name when this call took its LAST copy (so its sidecars can follow, C41), else null.
     */
    suspend fun removeCopyByUri(context: Context, uri: Uri): String? {
        // Names whose last copy this call removes. Captured before deleteEmpty(), which is a bulk
        // delete and so cannot report what it dropped — and the transcript cascade needs to know.
        // This is the path the retention sweep takes (it deletes per copy, not by name).
        var orphaned = emptyList<String>()

        runCatching {
            val dao = dao(context)
            val s = uri.toString()
            val row = dao.findByUri(s) ?: return@runCatching
            if (row.localUri == s) dao.clearLocal(row.displayName)
            if (row.driveUri == s) dao.clearDrive(row.displayName)
            // Only the row this call emptied. Counting every copy-less row would also delete the transcript of
            // any other row that happened to be empty, which this call was never asked about.
            val after = dao.findByName(row.displayName)
            if (after != null && after.localUri == null && after.driveUri == null) orphaned = listOf(row.displayName)
            dao.deleteEmpty()
        }.onFailure { AppLogger.w(TAG, "removeCopyByUri($uri) failed: ${it.message}") }

        TranscriptCascade.deleteFor(context, orphaned)
        return orphaned.firstOrNull()
    }

    /** Extensions of the files CallVault writes beside a recording; never recordings themselves. */
    private val SIDECAR_EXTENSIONS = setOf("md", "json")

    /**
     * Whether [displayName] is a sidecar file CallVault writes beside a recording (BabApps item 6).
     * Deliberately narrow, a sidecar extension and nothing else: a recording with an unusual extension
     * that the folder scan took for audio by its MIME type is never mistaken for one. Used both to keep
     * [markDrive] from cataloguing sidecars and to clean up the rows older versions created.
     */
    internal fun isSidecarFile(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").lowercase() in SIDECAR_EXTENSIONS

    /**
     * One-time cleanup of the sidecar rows earlier versions created: removes them from the catalog only.
     * No transcript cascade ([forgetName] semantics) and no file is touched; the sidecar stays in the
     * folder and in Drive. Runs on every list load, so it asks the database only for names ending in a
     * sidecar extension (a LIKE query) instead of reading the whole catalog a second time. Returns how
     * many rows it removed.
     */
    suspend fun removeStaleSidecarRows(context: Context): Int = runCatching {
        val dao = dao(context)
        val stale = dao.sidecarNames().filter { isSidecarFile(it) }
        stale.forEach { dao.deleteByName(it) }
        if (stale.isNotEmpty()) AppLogger.i(TAG, "Removed ${stale.size} sidecar file(s) from the recordings catalog.")
        stale.size
    }.getOrElse {
        AppLogger.w(TAG, "removeStaleSidecarRows failed: ${it.message}"); 0
    }

    /**
     * One-time seed: if the catalog is empty, populate it from [scan] (a folder enumeration). Used so
     * recordings made before the catalog existed still appear. No-op once any row exists.
     */
    suspend fun importIfEmpty(context: Context, scan: suspend () -> List<RecordingEntry>) {
        runCatching {
            val dao = dao(context)
            if (dao.count() > 0) return
            val seeded = scan()
            if (seeded.isNotEmpty()) {
                dao.upsertAll(seeded)
                AppLogger.i(TAG, "Seeded recordings catalog with ${seeded.size} pre-existing file(s).")
            }
        }.onFailure { AppLogger.w(TAG, "importIfEmpty failed: ${it.message}") }
    }
}
