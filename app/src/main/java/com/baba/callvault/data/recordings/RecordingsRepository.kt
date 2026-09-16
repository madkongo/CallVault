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
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.db.RecordingEntry
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.VoicemailLabel
import com.baba.callvault.transcription.AudioDecoder
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
        /** Call start in epoch millis, parsed from the same filename timestamp as [displayDate]. */
        val startedAtMillis: Long?,
        /**
         * How long the call lasted: from the system call log where it is there, otherwise read
         * from the recording's own container. Null only when neither knows — a Drive-only copy,
         * or a file declaring no duration.
         */
        val durationSeconds: Long? = null,
        val number: String?,
        val source: RecordingSource = RecordingSource.LOCAL,
        val contactName: String? = null,
        /** Non-null only for VoIP recordings: the app the call was made in, for a badge/icon. */
        val voipApp: String? = null,
        /**
         * True for a file the user imported rather than a call CallVault recorded.
         *
         * Carried on the item rather than re-derived from the name wherever it is needed, so the
         * marker is read by [ImportedRecording.isImported] — the one place that knows the rule — and
         * every row, badge and sweep is answering the same question the same way.
         */
        val isImported: Boolean = false,
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

            // Resolve contact names from the parsed numbers (PhoneLookup only when READ_CONTACTS is
            // granted), caching by number within this call to avoid duplicate queries. Voicemail is
            // not a real contact, so it gets its label as a fallback after the lookup misses.
            // "No name" is cached as "" — getOrPut re-runs the lambda for stored nulls.
            val hasContactsPermission = PermissionChecks.hasContactsPermission(context)
            val nameCache = HashMap<String, String>()

            // How long each call lasted, from the system call log — one query for the whole list. It
            // cannot account for every recording: an **app call is never in the call log at all**, and
            // neither is one older than the log keeps.
            val durations = CallDurationLookup.durationsFor(context, items.mapNotNull { it.startedAtMillis })
            // A merged recording keeps the timestamp of the call it began with, so the system call
            // log answers for that ONE call — 5 minutes for a conversation that is now twenty-five.
            // The log cannot know about merging, so for these the stored length is the only truth.
            val mergedNames = runCatching {
                RecordingDatabase.get(context).mergePartDao().allMergedNames().toSet()
            }.getOrDefault(emptySet())

            var probes = 0
            val startedAtNs = System.nanoTime()

            items.map { item ->
                val fromCallLog = if (item.displayName in mergedNames) null
                else item.startedAtMillis?.let { durations[it] }
                // Fall back to the recording's own container duration. It is not a guess: it is the
                // length of the audio actually captured, which is the more direct answer for a list of
                // recordings. Without it every WhatsApp row showed a blank where its length should be,
                // and the transcription length limit had nothing to judge an app call by.
                //
                // Read ONCE and remembered in the catalog. The original version called this on every
                // list load for every recording the call log could not answer for — every app call
                // (they are never in the call log at all), everything older than the log keeps, and
                // ALL of them when READ_CALL_LOG is not granted, since the lookup then returns an
                // empty map. Each one is a SAF file-descriptor open plus a MediaExtractor, so the cost
                // was O(library) on every ON_RESUME and grew for as long as someone kept using the
                // app. It was reported from the field as the list taking longer and longer to appear,
                // and as a just-finished call seeming not to have recorded — the list is emitted in
                // one piece, so nothing showed until the slowest file had been opened.
                //
                // Precedence is unchanged for an ordinary recording: the call log still wins where it
                // has an answer. A merged one is the exception, and has to be — see above.
                //
                // Only for a device copy: the Drive one would be a network read per row.
                val seconds = when {
                    fromCallLog != null -> fromCallLog
                    item.durationSeconds != null -> item.durationSeconds
                    else -> item.localUri?.let { uri ->
                        probes++
                        val read = AudioDecoder.durationMs(context, uri)
                            .takeIf { it > 0 }?.let { (it + 500) / 1000 }
                        // A failed read stays uncached, so a transient failure cannot become a
                        // permanent wrong answer.
                        if (read != null) RecordingCatalog.setDuration(context, item.displayName, read)
                        read
                    }
                }
                val withDuration = seconds?.let { item.copy(durationSeconds = it) } ?: item
                val number = withDuration.number
                if (number.isNullOrBlank()) withDuration
                else {
                    val name = nameCache.getOrPut(number) {
                        (if (hasContactsPermission) lookupContactName(context, number) else null)
                            ?: VoicemailLabel.labelOrNull(context, number)
                            ?: ""
                    }
                    if (name.isEmpty()) withDuration else withDuration.copy(contactName = name)
                }
            }.also {
                // One line, always, so a "the list is slow" report can be answered with a number
                // instead of a guess. After the first pass `probes` is 0 and this is the cheap path.
                val ms = (System.nanoTime() - startedAtNs) / 1_000_000
                AppLogger.i(TAG, "Listed ${it.size} recordings in ${ms}ms ($probes duration probe(s))")
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
            startedAtMillis = parsed.startedAtMillis,
            number = parsed.number,
            contactName = parsed.contactName,
            voipApp = parsed.voipApp,
            isImported = parsed.isImported,
            source = source,
            durationSeconds = entry.durationSeconds,
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
        // Clear this copy from the catalog ONLY if the file is actually gone (dropping the row if it was
        // the last copy). Clearing it regardless is what stranded 123 recordings on one device's Drive:
        // the Drive delete failed, the row was dropped anyway, and since the retention sweep only walks
        // the catalog, the file became both invisible in the app and permanently un-retryable — while
        // the sweep's own docs promised a retry "on the next daily run". Keeping the row is what makes
        // that promise true.
        if (deleted) {
            RecordingCatalog.removeCopyByUri(context, uri)
        } else {
            AppLogger.w(TAG, "Keeping the catalog entry for $uri so the next sweep can retry the delete")
        }
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
    internal fun enumerateFolder(context: Context, folderUri: Uri): List<RecordingItem> {
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
            startedAtMillis = parsed.startedAtMillis,
            number = parsed.number,
            contactName = parsed.contactName,
            voipApp = parsed.voipApp,
            isImported = parsed.isImported
        )
    }

    /** Filename marker for a VoIP recording (see VoipRecordingCoordinator). */
    private const val VOIP_TOKEN = "voip"

    /** The same marker carrying the app, as "voip-WhatsApp"; keeps the app out of the caller's slot. */
    private const val VOIP_APP_PREFIX = "$VOIP_TOKEN-"

    // -------- Best-effort name parsing

    internal data class ParsedName(
        val direction: RecordingDirection?,
        val displayDate: String?,
        val startedAtMillis: Long? = null,
        val number: String?,
        /** Set only for VoIP recordings, which carry a name rather than a number. */
        val contactName: String? = null,
        /** The app the VoIP call was made in (e.g. "WhatsApp"), when it could be determined. */
        val voipApp: String? = null,
        /** True for an imported file; see [ImportedRecording]. */
        val isImported: Boolean = false
    )

    /**
     * Best-effort parse of the default template "{date}_{direction}_{phone_number}".
     * {date} is "yyyyMMdd_HHmmss.SSSZ" (which itself contains an underscore), {direction} is
     * "in"/"out". We split on '_' and look for the in/out token as an anchor; everything before it
     * (joined) is the date, everything after it is the number. If the layout doesn't match, all
     * fields are null and the UI shows the raw name.
     */
    internal fun parseName(displayName: String): ParsedName {
        val base = displayName.substringBeforeLast('.')
        val parts = base.split('_')

        // An imported file uses "{date}_import[_{label}]". It is NOT a call: there is no number, no
        // direction and nobody on the other end, and none of those is invented here. What it has is a
        // date — the date it was imported — and the label it was given, which goes where a contact
        // name goes because it is the nearest thing to one and is what recognises the row.
        //
        // Asked of ImportedRecording rather than matched here, because the marker is read by SLOT and
        // not by search: a contact called "Important" or a VoIP app named "Import" must never claim
        // the branch, and that rule has to live in exactly one place. It also computes the stamp,
        // which this parser cannot: a call's date ends at the in/out anchor, and an import has none.
        if (ImportedRecording.isImported(displayName)) {
            val rawDate = ImportedRecording.stampTokenOf(displayName).orEmpty()
            return ParsedName(
                direction = null,
                displayDate = formatDate(rawDate),
                startedAtMillis = parseStartedAt(rawDate),
                number = null,
                contactName = ImportedRecording.labelOf(displayName),
                voipApp = null,
                isImported = true,
            )
        }

        // VoIP recordings use "{date}_voip[_{caller}]": there is no call-log entry behind them, so
        // there is no number and no reliable direction — but there IS often a name from the call
        // notification. Without this branch the whole raw filename is shown, which is far too long to
        // read in the list.
        val voipIndex = parts.indexOfFirst { it == VOIP_TOKEN || it.startsWith(VOIP_APP_PREFIX) }
        if (voipIndex > 0) {
            val rawDate = parts.subList(0, voipIndex).joinToString("_")
            val extras = parts.subList(voipIndex + 1, parts.size)
            val marker = parts[voipIndex]

            // Current grammar is "{date}_voip-{App}[_{caller}]": the app rides on the marker itself, so
            // an app with no caller ("_voip-Signal") can never be misread as a caller with no app.
            // That ambiguity was real — a Signal call showed no app badge because its lone token was
            // taken for the contact's name.
            val voipApp: String?
            val caller: String?
            if (marker.startsWith(VOIP_APP_PREFIX)) {
                voipApp = marker.removePrefix(VOIP_APP_PREFIX).ifBlank { null }
                caller = extras.joinToString("_").ifBlank { null }
            } else {
                // Legacy "{date}_voip[_{App}][_{caller}]", still on disk from earlier builds. The two
                // cannot be told apart with a single token; reading it as the caller loses a badge,
                // whereas the reverse would put a stranger's name where the app belongs.
                voipApp = extras.getOrNull(0)?.takeIf { extras.size >= 2 }
                caller = when {
                    extras.isEmpty() -> null
                    extras.size == 1 -> extras[0]
                    else -> extras.drop(1).joinToString("_")
                }?.ifBlank { null }
            }
            return ParsedName(
                direction = null,
                displayDate = formatDate(rawDate),
                startedAtMillis = parseStartedAt(rawDate),
                number = null,
                contactName = caller,
                voipApp = voipApp,
            )
        }

        val dirIndex = parts.indexOfFirst { it == "in" || it == "out" }
        if (dirIndex <= 0) return ParsedName(null, null, null, null)

        val direction = when (parts[dirIndex]) {
            "in" -> RecordingDirection.INCOMING
            "out" -> RecordingDirection.OUTGOING
            else -> null
        }
        val rawDate = parts.subList(0, dirIndex).joinToString("_")
        val number = parts.subList(dirIndex + 1, parts.size).joinToString("_").ifBlank { null }
        return ParsedName(direction, formatDate(rawDate), parseStartedAt(rawDate), number)
    }

    /**
     * Reformats the raw "yyyyMMdd_HHmmss.SSSZ" date token into a friendlier "yyyy-MM-dd HH:mm".
     * Falls back to the raw token if it doesn't match the expected shape.
     */
    /**
     * Parses the raw "yyyyMMdd_HHmmss.SSSZ" filename token into epoch millis, for relative dates in
     * the UI and for matching a recording to its call-log entry.
     *
     * The offset form is tried FIRST and matters: a recording made abroad, or before a DST change,
     * carries the offset it was made at, and reading it as local time would shift the call by hours
     * and match it to the wrong call-log entry — or to none.
     */
    private fun parseStartedAt(rawDate: String): Long? {
        val token = rawDate.trim().ifBlank { return null }
        for (pattern in arrayOf("yyyyMMdd_HHmmss.SSSZ", "yyyyMMdd_HHmmss.SSS", "yyyyMMdd_HHmmss")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(token)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

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
