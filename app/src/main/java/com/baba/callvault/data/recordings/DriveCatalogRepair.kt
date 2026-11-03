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
import com.baba.callvault.system.storage.SafHelper
import java.io.FileNotFoundException
import androidx.core.net.toUri
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-points catalog rows whose Drive URI was issued under a folder grant we no longer hold.
 *
 * **Why a stored Drive URI stops working.** Google Drive's SAF document URIs carry the account slot they
 * were issued for — `…/tree/acc=1;doc=encoded=…`. That slot is an index, not an identity, and Drive
 * renumbers it when accounts are added, removed or re-ordered. Measured on 2026-08-04: every stored URI
 * pointed at `acc=1`, the live grant was `acc=4`, and every Drive call threw
 * `SecurityException: Permission Denial` — uploads, deletes and folder listings alike. Re-picking the
 * folder in Settings restores the grant, but does nothing for the URIs already written into the catalog.
 *
 * **Why that would otherwise be permanent.** A row holding a dead Drive URI is stuck between the two
 * halves of the retention sweep: the catalog pass tries the dead URI and fails for ever, while the
 * untracked pass skips the file because the row claims a Drive copy already exists. The recording becomes
 * undeletable — the same shape of trap that stranded 124 files, arrived at from the other direction.
 *
 * So each sweep re-points what it can and forgets only what is genuinely absent, and the file is then
 * governed normally again.
 */
object DriveCatalogRepair {

    private const val TAG = "CV:DriveRepair"

    /**
     * Matches stale rows against the live folder listing by display name, re-pointing each to its real
     * URI and dropping the reference when the file is not there at all.
     *
     * Enumerates the Drive folder itself rather than sharing the sweep's later listing: this runs once a
     * day, off the recording path, and the sweep's own scan has to see the repaired state rather than the
     * one this started from. Never throws; returns how many rows were changed.
     */
    suspend fun reconcile(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val folder = AppPreferences(context).getDriveFolderUri() ?: return@withContext 0
            val folderUri = folder.toString()
            val stale = RecordingCatalog.all(context)
                .mapNotNull { row -> row.driveUri?.let { uri -> row to uri } }
                .filter { (_, uri) -> isStaleReference(uri, folderUri) }
            if (stale.isEmpty()) return@withContext 0

            val live = RecordingsRepository.enumerateFolder(context, folder).associateBy { it.displayName }
            var repointed = 0
            var forgotten = 0
            for ((row, driveUri) in stale) {
                val match = live[row.displayName]
                if (match != null) {
                    RecordingCatalog.markDrive(
                        context = context,
                        displayName = row.displayName,
                        driveUri = match.uri,
                        driveSizeBytes = match.sizeBytes.takeIf { it > 0L },
                        deleteLocalAfter = false,
                        lastModified = row.lastModified,
                    )
                    repointed++
                } else {
                    // Not in the folder under the live grant, so the reference points at nothing we can
                    // reach or reason about. Dropping it is safe now in a way it was not before: if the
                    // file does turn up in a later listing, the sweep's untracked pass finds it by
                    // enumeration rather than by a URI we remembered.
                    RecordingCatalog.removeCopyByUri(context, driveUri.toUri())
                    forgotten++
                }
            }
            AppLogger.i(TAG, "Drive references repaired: $repointed re-pointed, $forgotten forgotten")
            repointed + forgotten
        }.getOrElse {
            AppLogger.w(TAG, "Could not repair Drive references: ${it.message}")
            0
        }
    }

    /**
     * Whether [documentUri] was issued under a different SAF tree than [folderUri] — i.e. a grant we no
     * longer hold.
     *
     * Compared rather than probed on purpose. A failed call cannot tell "this URI is dead" from "Drive is
     * offline", and clearing a row on a transient failure is how recordings got lost in the first place;
     * the tree the URI was minted under is a fact we can read locally, with no network and no ambiguity.
     * Anything without a tree segment is left alone — unjudged rather than assumed stale.
     */
    internal fun isStaleReference(documentUri: String, folderUri: String): Boolean {
        val docTree = treeIdOf(documentUri) ?: return false
        val folderTree = treeIdOf(folderUri) ?: return false
        return authorityOf(documentUri) != authorityOf(folderUri) || docTree != folderTree
    }

    /** The `tree/<id>` segment of a SAF URI, or null when it has none (e.g. a plain document URI). */
    private fun treeIdOf(uri: String): String? {
        val marker = "/tree/"
        val start = uri.indexOf(marker)
        if (start < 0) return null
        return uri.substring(start + marker.length).substringBefore('/').takeIf { it.isNotEmpty() }
    }

    /** The authority of a `content://authority/…` URI, or null when it does not look like one. */
    private fun authorityOf(uri: String): String? {
        val start = uri.indexOf("://")
        if (start < 0) return null
        return uri.substring(start + 3).substringBefore('/').takeIf { it.isNotEmpty() }
    }

    // ---- Copies deleted in Drive itself ---------------------------------------------------------------

    /** What asking Drive about one document said. Only [GONE] ever lets a reference be forgotten. */
    enum class Presence { PRESENT, GONE, UNKNOWN }

    /** How often [forgetDeletedIfDue] looks: it lists the Drive folder, which is not free. */
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
    private const val STATE_FILE = "cv_drive_presence"
    private const val KEY_CHECKED_AT = "checked_at"

    /**
     * Forgets Drive copies that were deleted in Drive itself, at most every [CHECK_EVERY_MS].
     *
     * Before this nothing ever noticed: a recording deleted (or binned) in Drive stayed in the list for good,
     * pointing at nothing (OP9, 2026-11-03, after the Drive test folder was binned). [reconcile] only fixes
     * references minted under an old folder grant, and only on the retention sweep, which does not run at all
     * when recordings are kept forever. Called when the list loads. Never throws; returns how many copies
     * were forgotten.
     */
    suspend fun forgetDeletedIfDue(context: Context, now: Long = System.currentTimeMillis()): Int {
        val state = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        if (now - state.getLong(KEY_CHECKED_AT, 0L) < CHECK_EVERY_MS) return 0
        state.edit().putLong(KEY_CHECKED_AT, now).apply()
        return forgetDeleted(context)
    }

    /** See [forgetDeletedIfDue]; runs the check now. */
    suspend fun forgetDeleted(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val folder = AppPreferences(context).getDriveFolderUri()
            val rows = RecordingCatalog.all(context).mapNotNull { row -> row.driveUri?.let { row.displayName to it } }
            if (rows.isEmpty()) return@withContext 0
            val listed = if (SafHelper.isFolderValid(context, folder)) listedNames(context, folder) else null
            val gone = goneCopies(rows, listed) { presenceOf(context, it.toUri()) }
            gone.forEach { RecordingCatalog.removeCopyByUri(context, it.toUri()) }
            AppLogger.i(
                TAG,
                "Drive presence check: ${rows.size} Drive copies, folder listing " +
                    "${listed?.size ?: "unavailable"}, ${gone.size} deleted in Drive and forgotten",
            )
            gone.size
        }.getOrElse {
            AppLogger.w(TAG, "Drive presence check failed: ${it.message}")
            0
        }
    }

    /**
     * Which Drive copies are really gone. Two proofs are needed: the name is missing from the folder listing
     * (when one could be read; an empty or failed listing proves nothing, so then every row is a candidate),
     * AND asking Drive about that document says it does not exist. Offline, a refused grant or any doubt is
     * [Presence.UNKNOWN] and keeps the copy: forgetting a recording that is still there is the worse mistake.
     *
     * @param rows   (display name, Drive URI) of every catalogued Drive copy.
     * @param listed the names in the Drive folder, or null when the folder could not be listed.
     */
    internal fun goneCopies(
        rows: List<Pair<String, String>>,
        listed: Set<String>?,
        presence: (String) -> Presence,
    ): List<String> {
        val candidates = if (listed.isNullOrEmpty()) rows else rows.filter { it.first !in listed }
        return candidates.map { it.second }.filter { presence(it) == Presence.GONE }
    }

    /**
     * Names in the Drive folder, or null when it could not be listed.
     *
     * Queried directly rather than through `DocumentFile.listFiles()`, which turns a failure into an empty list:
     * here a failure is null and only a successful query can say "empty".
     */
    private fun listedNames(context: Context, folder: Uri): Set<String>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        context.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it) } } }
    }.getOrNull()

    /**
     * Whether one Drive document still exists, by trying to open it.
     *
     * Querying its details is no proof: measured on the OP9 (2026-11-03), after a folder was deleted forever in
     * Drive the provider still returned the deleted files' cached name and size. Opening them failed with
     * FileNotFoundException ("StorageFileLoadException[io_error]"). An offline phone could fail the same way, so
     * the failure counts as [Presence.GONE] only while the phone has a validated internet connection.
     */
    private fun presenceOf(context: Context, uri: Uri): Presence = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { Presence.PRESENT } ?: Presence.UNKNOWN
    } catch (e: FileNotFoundException) {
        if (isOnline(context)) Presence.GONE else Presence.UNKNOWN
    } catch (e: Exception) {
        AppLogger.d(TAG, "Drive presence unknown for one copy: ${e.javaClass.simpleName}: ${e.message}")
        Presence.UNKNOWN
    }

    /** True with a validated internet connection, so a failed open means the file, not the network. */
    private fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
}
