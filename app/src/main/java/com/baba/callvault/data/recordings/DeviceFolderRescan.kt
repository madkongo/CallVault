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
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Brings the catalog in line with the device folder, for pull-to-refresh on the Recordings page.
 *
 * The list reads the catalog, never the folder, so a file copied into the folder by hand never showed and one
 * deleted with a file manager stayed listed until playback failed. This compares the two once, when asked:
 *
 * - **Added**: an audio file in the folder that the catalog has no device copy of, under the same rule as the
 *   first-run seed ([RecordingsRepository.seedsFromDevice]), so in Drive mode only imports come back.
 * - **Forgotten**: a device copy that is missing from the listing AND whose own document says it does not exist.
 *   Two proofs, as for Drive ([DriveCatalogRepair.goneCopies]); an unreadable folder forgets nothing.
 *
 * Only the device folder: listing Drive adds the "phantom" files its cache still shows. Drive copies are checked
 * by [DriveCatalogRepair.forgetDeleted] instead.
 */
object DeviceFolderRescan {

    private const val TAG = "CV:FolderRescan"

    /** How recent a copy may be and still be left alone: long enough for a finishing call to be published. */
    private const val SETTLE_MS = 2 * 60 * 1000L

    data class Result(val added: Int, val forgotten: Int)

    /** A file the folder listing returned, reduced to what the plan needs. */
    data class Listed(val name: String, val uri: String, val sizeBytes: Long, val lastModified: Long)

    data class Plan(val add: List<Listed>, val forgetCandidates: List<String>)

    /**
     * What to add and which device copies may be gone, before any document is asked.
     *
     * @param audio the usable audio files in the folder.
     * @param allNames every name in the folder, audio or not; null when it could not be listed.
     * @param inFolder whether a device copy's URI lies in the current folder. Copies elsewhere (a folder used before,
     *   an SD card since removed) are never candidates: this folder's listing says nothing about them.
     * @param now for [SETTLE_MS]: a copy written in the last moments may be mid-publish and is left alone.
     */
    internal fun plan(
        target: StorageTarget,
        audio: List<Listed>,
        allNames: Set<String>?,
        catalog: List<RecordingEntry>,
        inFolder: (String) -> Boolean,
        now: Long,
    ): Plan {
        val knownLocal = catalog.filter { it.localUri != null }
        val knownNames = knownLocal.map { it.displayName }.toSet()
        val add = audio.filter { it.name !in knownNames && RecordingsRepository.seedsFromDevice(target, it.name) }
        // No listing proves nothing, so then nothing is a candidate. An empty listing is still a listing: the
        // per-document check below decides each one.
        val candidates = if (allNames == null) emptyList()
        else knownLocal
            .filter { it.displayName !in allNames && now - it.lastModified > SETTLE_MS }
            .mapNotNull { it.localUri }
            .filter(inFolder)
        return Plan(add, candidates)
    }

    /** Runs the rescan. Off the main thread; never throws. */
    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = AppPreferences(context)
            val folder = prefs.getRecordingFolderUri()
            if (!SafHelper.isFolderValid(context, folder)) {
                AppLogger.i(TAG, "Device folder not readable; nothing rescanned")
                return@withContext Result(0, 0)
            }
            val audio = RecordingsRepository.enumerateFolder(context, folder).map {
                Listed(it.displayName, it.uri.toString(), it.sizeBytes, it.lastModified)
            }
            val treeId = DocumentsContract.getTreeDocumentId(folder)
            val inFolder = { uri: String -> isUnderTree(uri, folder.authority, treeId) }
            val plan = plan(
                prefs.getStorageTarget(), audio, listedNames(context, folder), RecordingCatalog.all(context),
                inFolder, System.currentTimeMillis(),
            )
            plan.add.forEach {
                RecordingCatalog.recordLocal(context, it.name, it.uri.toUri(), it.sizeBytes, it.lastModified)
            }
            val gone = plan.forgetCandidates.filter { isGone(context, it.toUri()) }
            gone.forEach { RecordingCatalog.removeCopyByUri(context, it.toUri()) }
            AppLogger.i(TAG, "Rescanned the device folder: ${plan.add.size} added, ${gone.size} of ${plan.forgetCandidates.size} missing forgotten")
            Result(plan.add.size, gone.size)
        }.getOrElse {
            AppLogger.w(TAG, "Rescan failed: ${it.message}")
            Result(0, 0)
        }
    }

    /** Whether [uri] is a document of the tree [treeId] on [authority]. Never throws. */
    private fun isUnderTree(uri: String, authority: String?, treeId: String): Boolean = runCatching {
        val u = uri.toUri()
        u.authority == authority && DocumentsContract.isTreeUri(u) && DocumentsContract.getTreeDocumentId(u) == treeId
    }.getOrDefault(false)

    /** Whether the provider's IllegalArgumentException is its way of saying "no such file". */
    internal fun isMissingFileReport(e: IllegalArgumentException): Boolean =
        generateSequence<Throwable>(e) { it.cause }.any { it is FileNotFoundException } ||
            e.message?.contains("FileNotFoundException") == true

    /** Names in the folder, or null when the query failed (DocumentFile.listFiles would say "empty"). */
    private fun listedNames(context: Context, folder: Uri): Set<String>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        context.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it) } } }
    }.getOrNull()

    /** Only a "file not found" from the document itself counts; any other failure keeps the copy. */
    private fun isGone(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { false } ?: false
    } catch (e: FileNotFoundException) {
        true
    } catch (e: IllegalArgumentException) {
        // How Android's own storage provider reports a missing file inside a tree: "Failed to determine if
        // primary:Recordings/x.ogg is child of primary:Recordings: FileNotFoundException: Missing file" (OP9,
        // Android 14), with the FileNotFoundException written into the message rather than kept as the cause. Only
        // that counts; the folder itself was just found readable, so it is the file that is missing.
        isMissingFileReport(e)
    } catch (e: Exception) {
        AppLogger.d(TAG, "Kept one copy, presence unknown: ${e.javaClass.simpleName}")
        false
    }
}
