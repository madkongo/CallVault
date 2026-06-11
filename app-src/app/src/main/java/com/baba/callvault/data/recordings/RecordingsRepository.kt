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
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * @param contactName  The contact display name resolved from [number] via PhoneLookup, or null
     *                     when READ_CONTACTS is not granted, no number was parsed, or no match.
     */
    data class RecordingItem(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val direction: RecordingDirection?,
        val displayDate: String?,
        val number: String?,
        val contactName: String? = null
    )

    /**
     * Lists all recordings across the device and Drive folders, merged and deduped by display name,
     * sorted newest-first. Runs on [Dispatchers.IO]. Returns an empty list if no folder is configured
     * or on any error.
     *
     * @param context App context used to resolve SAF document trees and read preferences.
     * @return The merged, sorted list of recordings (possibly empty); never throws.
     */
    suspend fun listRecordings(context: Context): List<RecordingItem> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = AppPreferences(context)
            val folders = listOfNotNull(prefs.getRecordingFolderUri(), prefs.getDriveFolderUri())

            // Dedupe by display name; first writer wins (device folder is listed first).
            val byName = LinkedHashMap<String, RecordingItem>()
            for (folderUri in folders) {
                for (item in enumerateFolder(context, folderUri)) {
                    byName.putIfAbsent(item.displayName, item)
                }
            }

            val merged = byName.values.sortedWith(
                compareByDescending<RecordingItem> { it.lastModified }
                    .thenByDescending { it.displayName }
            ).toList()

            // Resolve contact names from the parsed numbers (only when READ_CONTACTS is granted),
            // caching by number within this call to avoid duplicate PhoneLookup queries.
            if (!PermissionChecks.hasContactsPermission(context)) merged
            else {
                val nameCache = HashMap<String, String?>()
                merged.map { item ->
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

        deletedAny
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

    /** Enumerates one SAF folder, filtering to audio files. Never throws. */
    private fun enumerateFolder(context: Context, folderUri: Uri): List<RecordingItem> {
        val result = mutableListOf<RecordingItem>()
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, folderUri)
            val files = tree?.listFiles() ?: emptyArray()
            for (doc in files) {
                if (doc.isFile && isAudio(doc)) {
                    result.add(toItem(doc))
                }
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to enumerate folder $folderUri: ${e.message}")
        }
        return result
    }

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
