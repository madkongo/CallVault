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
import android.provider.ContactsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecordingsRepository enumerates finished recordings for the in-app Home list.
 *
 * Recordings can live in the device folder ([AppPreferences.getRecordingFolderUri]) and/or the Drive
 * folder ([AppPreferences.getDriveFolderUri]) depending on the user's [com.baba.callvault.data.StorageTarget].
 * This repository merges both folders and dedupes by display name (a file may exist in both for BOTH
 * mode, or only in Drive after a DRIVE-only sync). It is intentionally read-only, best-effort, and
 * never throws — on any failure it simply returns whatever it could enumerate (possibly empty).
 */
object RecordingsRepository {

    private const val TAG = "CV:RecordingsRepo"

    /** Audio container extensions CallVault writes (Opus -> .ogg, AAC -> .m4a). */
    private val AUDIO_EXTENSIONS = listOf(".ogg", ".m4a")

    /**
     * Where a recording physically lives, derived from which configured folder(s) a given display
     * name was found in.
     *
     *  - [LOCAL]: present only in the device folder ([AppPreferences.getRecordingFolderUri]).
     *  - [DRIVE]: present only in the Drive folder ([AppPreferences.getDriveFolderUri]).
     *  - [BOTH]:  present in both folders (e.g. BOTH storage mode, or after a Drive sync).
     */
    enum class RecordingSource { LOCAL, DRIVE, BOTH }

    /**
     * A single recording surfaced to the UI. Parsing of [direction]/[displayDate]/[number] is
     * best-effort from the display name; any unparsed field is null and the UI falls back to the
     * raw [displayName].
     *
     * @param uri          Content URI used for playback (SAF single-document URI).
     * @param displayName  The file's display name (including extension).
     * @param sizeBytes    File size in bytes (0 if unknown).
     * @param lastModified Last-modified epoch millis (0 if unknown).
     * @param direction    Parsed call direction, or null if it could not be derived.
     * @param displayDate  A human-friendly date string parsed from the name, or null.
     * @param number       The phone number parsed from the name, or null.
     * @param source       Which folder(s) this recording was found in (LOCAL / DRIVE / BOTH).
     * @param contactName  The contact display name resolved from [number] via PhoneLookup, or null
     *                     when READ_CONTACTS is not granted, no number was parsed, or no match.
     * @param localUri     The device-folder copy's content URI, or null if this name is Drive-only.
     * @param driveUri     The Drive-folder copy's content URI, or null if this name is device-only.
     *                     For a BOTH item both [localUri] and [driveUri] are set, so each physical
     *                     copy can be played individually.
     * @param localSizeBytes Size of the device copy in bytes, or null when there is no device copy.
     * @param driveSizeBytes Size of the Drive copy in bytes, or null when there is no Drive copy.
     */
    data class RecordingItem(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val direction: RecordingDirection?,
        val displayDate: String?,
        val number: String?,
        val source: RecordingSource = RecordingSource.LOCAL,
        val contactName: String? = null,
        val localUri: Uri? = null,
        val driveUri: Uri? = null,
        val localSizeBytes: Long? = null,
        val driveSizeBytes: Long? = null
    )

    /** Friendly day format used for the Date facet keys (e.g. "Jun 11, 2026"). */
    private val DAY_KEY_FORMAT = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /**
     * Derives a stable day key for a recording, used by the Home "Date" filter facet. Prefers the
     * date encoded in [RecordingItem.displayDate] (whose shape is "yyyy-MM-dd HH:mm"); when that is
     * absent or unparsable, falls back to formatting [RecordingItem.lastModified]. The same key is
     * used both to build the option list and to match the active filter, so they always agree.
     */
    fun dayKey(item: RecordingItem): String {
        // displayDate is "yyyy-MM-dd HH:mm" — turn the date part into the friendly day label.
        val raw = item.displayDate?.substringBefore(' ')?.takeIf { it.length == 10 }
        if (raw != null) {
            runCatching {
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
                if (parsed != null) return DAY_KEY_FORMAT.format(parsed)
            }
        }
        return DAY_KEY_FORMAT.format(Date(item.lastModified))
    }

    /**
     * Lists all recordings, newest-first, from CallVault's own catalog ([RecordingCatalog]) — NOT a
     * live folder listing. The catalog is the source of truth (written at record-finish and updated by
     * the copy/sweep workers), so the Home list is instant, offline, and immune to a cloud provider's
     * eventually-consistent folder cache: no Google Drive "phantom" recordings and no Drive sync-error
     * toast, because we never enumerate or open the Drive folder here. On first run the catalog is
     * seeded once from a folder scan ([scanFolders]) so recordings made before it existed still appear.
     * Runs on [Dispatchers.IO]; returns an empty list on any error.
     *
     * @param context App context used to access the catalog and resolve contact names.
     * @return The sorted list of recordings (possibly empty); never throws.
     */
    suspend fun listRecordings(context: Context): List<RecordingItem> = withContext(Dispatchers.IO) {
        runCatching {
            // One-time seed for pre-catalog recordings; no-op once any row exists.
            RecordingCatalog.importIfEmpty(context) { scanFolders(context) }

            val items = RecordingCatalog.all(context).mapNotNull { toItem(it) }

            // Resolve contact names from the parsed numbers (only when READ_CONTACTS is granted),
            // caching by number within this call to avoid duplicate PhoneLookup queries.
            if (!PermissionChecks.hasContactsPermission(context)) items
            else {
                val nameCache = HashMap<String, String?>()
                items.map { item ->
                    val number = item.number
                    if (number.isNullOrBlank()) item
                    else {
                        val name = nameCache.getOrPut(number) { lookupContactName(context, number) }
                        if (name == null) item else item.copy(contactName = name)
                    }
                }
            }
        }.getOrElse { e ->
            AppLogger.w(TAG, "Failed to list recordings: ${e.message}")
            emptyList()
        }
    }

    /**
     * Maps a catalog [RecordingEntry] to the UI [RecordingItem], deriving direction/date/number from
     * the display name (the filename template encodes them). [RecordingSource] follows which copies are
     * present; the primary playback uri prefers the local copy. Returns null for an empty row (no copy).
     */
    private fun toItem(entry: RecordingEntry): RecordingItem? {
        val localUri = entry.localUri?.toUri()
        val driveUri = entry.driveUri?.toUri()
        val primary = localUri ?: driveUri ?: return null
        val source = when {
            localUri != null && driveUri != null -> RecordingSource.BOTH
            driveUri != null -> RecordingSource.DRIVE
            else -> RecordingSource.LOCAL
        }
        val parsed = parseName(entry.displayName)
        return RecordingItem(
            uri = primary,
            displayName = entry.displayName,
            sizeBytes = entry.localSizeBytes ?: entry.driveSizeBytes ?: 0L,
            lastModified = entry.lastModified,
            direction = parsed.direction,
            displayDate = parsed.displayDate,
            number = parsed.number,
            source = source,
            localUri = localUri,
            driveUri = driveUri,
            localSizeBytes = entry.localSizeBytes,
            driveSizeBytes = entry.driveSizeBytes
        )
    }

    /**
     * Enumerates the configured SAF folders into catalog rows for the one-time [RecordingCatalog]
     * seed. The device folder is CallVault's source of truth for LOCAL and BOTH (it always keeps the
     * local copy), so in BOTH mode a Drive copy only ANNOTATES a name that also exists locally; a
     * Drive-ONLY name in BOTH mode is a deleted/orphaned file (e.g. a stale Google Drive listing-cache
     * "phantom") and is intentionally NOT seeded. In DRIVE mode the local copy is removed after sync,
     * so Drive is the source of truth and is enumerated directly. No Drive document is ever opened.
     */
    private fun scanFolders(context: Context): List<RecordingEntry> {
        val prefs = AppPreferences(context)
        val target = prefs.getStorageTarget()
        val deviceFolder = prefs.getRecordingFolderUri()
        val driveFolder = prefs.getDriveFolderUri()
        val byName = LinkedHashMap<String, RecordingEntry>()

        if (target != StorageTarget.DRIVE && deviceFolder != null) {
            for (item in enumerateFolder(context, deviceFolder)) {
                byName.putIfAbsent(
                    item.displayName,
                    RecordingEntry(
                        displayName = item.displayName,
                        localUri = item.uri.toString(),
                        localSizeBytes = item.sizeBytes.takeIf { it > 0L },
                        lastModified = item.lastModified
                    )
                )
            }
        }
        if (target == StorageTarget.DRIVE && driveFolder != null) {
            for (item in enumerateFolder(context, driveFolder)) {
                byName.putIfAbsent(
                    item.displayName,
                    RecordingEntry(
                        displayName = item.displayName,
                        driveUri = item.uri.toString(),
                        driveSizeBytes = item.sizeBytes.takeIf { it > 0L },
                        lastModified = item.lastModified
                    )
                )
            }
        }
        if (target == StorageTarget.BOTH && driveFolder != null) {
            for (item in enumerateFolder(context, driveFolder)) {
                val existing = byName[item.displayName] ?: continue
                byName[item.displayName] = existing.copy(
                    driveUri = item.uri.toString(),
                    driveSizeBytes = item.sizeBytes.takeIf { it > 0L }
                )
            }
        }
        return byName.values.toList()
    }

    /**
     * Deletes the recording represented by [item] everywhere the app surfaces it: the [item]'s own
     * single-document URI, plus any same-named child in the device folder
     * ([AppPreferences.getRecordingFolderUri]) and the Drive folder ([AppPreferences.getDriveFolderUri]).
     * Best-effort and never throws.
     *
     * @return true if at least one underlying file was deleted.
     */
    suspend fun deleteRecording(context: Context, item: RecordingItem): Boolean = withContext(Dispatchers.IO) {
        var deletedAny = false

        // 1. Delete the exact file this row points at.
        runCatching {
            if (DocumentFile.fromSingleUri(context, item.uri)?.delete() == true) deletedAny = true
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to delete ${item.uri}: ${e.message}")
        }

        // 2. Delete any same-named copy in the configured folders.
        val prefs = AppPreferences(context)
        val folders = listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri())
        for (folderUri in folders) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return@runCatching
                for (doc in tree.listFiles()) {
                    if (doc.isFile && doc.name == item.displayName) {
                        if (doc.delete()) deletedAny = true
                    }
                }
            }.onFailure { e ->
                AppLogger.w(TAG, "Failed to delete copy of ${item.displayName} in $folderUri: ${e.message}")
            }
        }

        // Drop the recording from the catalog (all copies are gone) so Home reflects the deletion.
        RecordingCatalog.removeName(context, item.displayName)
        deletedAny
    }

    /**
     * Deletes ONLY the single file at [uri] (one physical copy), via
     * [DocumentFile.fromSingleUri]. Unlike [deleteRecording], this does NOT touch same-named copies
     * in the other folder — it is used to delete just the Device or just the Drive copy of a BOTH
     * recording. Best-effort and never throws.
     *
     * @return true if the file was deleted, false otherwise (missing, no permission, or error).
     */
    suspend fun deleteFile(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val deleted = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.delete() == true
        }.getOrElse { e ->
            AppLogger.w(TAG, "Failed to delete file $uri: ${e.message}")
            false
        }
        // Clear just this copy from the catalog (dropping the row if it was the last copy).
        RecordingCatalog.removeCopyByUri(context, uri)
        deleted
    }

    /**
     * Resolves [phoneNumber] to a contact display name via [ContactsContract.PhoneLookup].
     * Mirrors the lookup in [com.baba.callvault.utils.RecordingFileNameFormatter] (whose equivalent
     * helper is private and not reusable from here). Caller must verify READ_CONTACTS first.
     * Never throws; returns null when there is no match.
     */
    private fun lookupContactName(context: Context, phoneNumber: String): String? = runCatching {
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }.getOrNull()

    /**
     * Enumerates one SAF folder, filtering to audio files. Never throws.
     *
     * Phantom defense lives in [listRecordings], not here: in BOTH mode a Drive-only name (the shape a
     * stale Google Drive listing-cache "phantom" takes) is never surfaced because the device folder is
     * authoritative. We deliberately do NOT open Drive documents to probe them — that makes the Google
     * Drive app raise its own "synchronization issue" toast. The cheap metadata check below still drops
     * obviously-dead/zero-byte entries.
     */
    private fun enumerateFolder(context: Context, folderUri: Uri): List<RecordingItem> {
        val result = mutableListOf<RecordingItem>()
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, folderUri)
            val files = tree?.listFiles() ?: emptyArray()
            for (doc in files) {
                if (doc.isFile && isAudio(doc) && isUsable(doc)) {
                    result.add(toItem(doc))
                }
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to enumerate folder $folderUri: ${e.message}")
        }
        return result
    }

    /**
     * True if [doc] still exists and is non-empty per its metadata — drops obviously-dead and
     * zero-byte entries. Best-effort: treats a thrown query as not-usable. (Stale cloud-cache phantoms,
     * whose metadata can still look valid, are handled structurally in [listRecordings].)
     */
    private fun isUsable(doc: DocumentFile): Boolean = runCatching {
        doc.exists() && doc.canRead() && doc.length() > 0L
    }.getOrDefault(false)

    /** True if the document looks like a CallVault audio file (by extension or an audio mime type). */
    private fun isAudio(doc: DocumentFile): Boolean {
        val name = doc.name?.lowercase().orEmpty()
        if (AUDIO_EXTENSIONS.any { name.endsWith(it) }) return true
        return doc.type?.startsWith("audio/") == true
    }

    private fun toItem(doc: DocumentFile): RecordingItem {
        val name = doc.name ?: "recording"
        val parsed = parseName(name)
        return RecordingItem(
            uri = doc.uri,
            displayName = name,
            sizeBytes = doc.length().coerceAtLeast(0L),
            lastModified = doc.lastModified().coerceAtLeast(0L),
            direction = parsed.direction,
            displayDate = parsed.displayDate,
            number = parsed.number
        )
    }

    // -------- Best-effort name parsing

    private data class ParsedName(
        val direction: RecordingDirection?,
        val displayDate: String?,
        val number: String?
    )

    /**
     * Best-effort parse of the default template "{date}_{direction}_{phone_number}".
     * {date} is "yyyyMMdd_HHmmss.SSSZ" (which itself contains an underscore), {direction} is
     * "in"/"out". We split on '_' and look for the in/out token as an anchor; everything before it
     * (joined) is the date, everything after it is the number. If the layout doesn't match, all
     * fields are null and the UI shows the raw name.
     */
    private fun parseName(displayName: String): ParsedName {
        val base = displayName.substringBeforeLast('.')
        val parts = base.split('_')
        val dirIndex = parts.indexOfFirst { it == "in" || it == "out" }
        if (dirIndex <= 0) return ParsedName(null, null, null)

        val direction = when (parts[dirIndex]) {
            "in" -> RecordingDirection.INCOMING
            "out" -> RecordingDirection.OUTGOING
            else -> null
        }
        val rawDate = parts.subList(0, dirIndex).joinToString("_")
        val number = parts.subList(dirIndex + 1, parts.size).joinToString("_").ifBlank { null }
        return ParsedName(direction, formatDate(rawDate), number)
    }

    /**
     * Reformats the raw "yyyyMMdd_HHmmss.SSSZ" date token into a friendlier "yyyy-MM-dd HH:mm".
     * Falls back to the raw token if it doesn't match the expected shape.
     */
    private fun formatDate(rawDate: String): String? {
        // Expected shape: yyyyMMdd_HHmmss(.SSSZ)?  take the yyyyMMdd and HHmmss segments.
        val segments = rawDate.split('_')
        if (segments.size < 2) return rawDate.ifBlank { null }
        val day = segments[0]
        val time = segments[1].substringBefore('.')
        if (day.length != 8 || time.length < 4) return rawDate
        return buildString {
            append(day.substring(0, 4)).append('-')
            append(day.substring(4, 6)).append('-')
            append(day.substring(6, 8)).append(' ')
            append(time.substring(0, 2)).append(':')
            append(time.substring(2, 4))
        }
    }
}
