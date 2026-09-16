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
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings a file the user already owns into CallVault, so it can be transcribed.
 *
 * An import is **not a call**. There is no number, no direction, no contact and no second speaker
 * channel, and nothing here invents any of them — the name carries a timestamp and the import
 * marker, and everything downstream reads the absences as absences. What it does have is the one
 * thing transcription needs: a catalogued file in the recordings folder with a name of its own.
 *
 * ## The order of the steps is the design
 *
 * 1. **Metadata first, bytes never.** The name and type decide whether this is a shape we can store
 *    ([ImportableAudio]); reading them costs one provider query.
 * 2. **Copy before decode, always.** A URI from a share sheet or a chat app is frequently a
 *    non-seekable pipe, and `MediaExtractor` needs to seek. Probing the source directly works on the
 *    files picked from local storage and fails on exactly the ones this feature exists for.
 * 3. **Decode the copy, then keep it.** The probe asks the device whether it can really read this
 *    file — see [decodes] — and a file that fails is deleted again rather than catalogued. The
 *    alternative is a row that looks like every other row and silently cannot be transcribed.
 * 4. **Catalogue with the import time.** Never the source's own date: retention measures age from
 *    the catalog's `lastModified`, so a two-year-old voice note stamped with when it was recorded
 *    would arrive already expired and be deleted by the nightly sweep the same night.
 */
object AudioImport {

    private const val TAG = "CV:AudioImport"

    /**
     * How much audio the decode probe reads before it is satisfied.
     *
     * Enough that the codec has been created, configured, run and has reported its output format —
     * which is where the 16-bit check lives — and short enough that importing a ninety-minute
     * recording costs the same as importing a voice note. Decoding the whole file to find out would
     * be the memory cost transcription is limited by, paid twice.
     */
    private const val PROBE_MS = 2_000L

    /** What the user gets back from an import attempt. */
    sealed interface Outcome {

        /**
         * The file is in the recordings folder and in the catalog.
         *
         * @param displayName the name it was stored under, which is what every other part of the app
         *   keys on — the transcript, the summary, the tags and the delete cascade alike.
         */
        data class Imported(val displayName: String) : Outcome

        /** Nothing was kept, and [reason] is what to tell the user. */
        data class Refused(val reason: Reason) : Outcome
    }

    /**
     * Why an import did not happen. Each has a sentence of its own in the UI, because "that didn't
     * work" is the message that makes someone try the same file three more times.
     */
    enum class Reason {
        /** Not a format CallVault can store, judged from the name and the provider's type. */
        NOT_AUDIO,

        /** The source is empty. Copying it would produce a row that plays nothing. */
        EMPTY,

        /** There is no recordings folder to put it in — setup has not finished, or the grant is gone. */
        NO_FOLDER,

        /** The bytes could not be written into the recordings folder. */
        COPY_FAILED,

        /**
         * The file was copied and this device could not decode it, so it could never be transcribed.
         * A 32-bit-float WAV lands here, and so does anything corrupt or DRM-protected.
         */
        UNDECODABLE,
    }

    /** What the checks that need no bytes decided. */
    sealed interface Plan {

        /** Worth copying. [storedAs] is the extension and MIME the copy is created with. */
        data class Accept(val storedAs: ImportableAudio.StoredAs) : Plan

        /** Not worth copying, and [reason] says so in terms the user can act on. */
        data class Refuse(val reason: Reason) : Plan
    }

    /**
     * Everything that can be decided before a byte is read, in the order it has to be decided in.
     *
     * Pure, and separate from [import], because this is where the refusals live and a refusal the
     * user cannot act on is the difference between "that isn't a format I can read" and trying the
     * same file three more times. The order matters: format first, because it is the commonest and
     * the most explicable; then emptiness, which is about this file; then the folder, which is about
     * the app and would be a confusing thing to be told about a PDF.
     *
     * @param sizeBytes the source's length, or a negative value when the provider did not report
     *   one. Unknown is not empty — refusing on a size nobody gave us would turn "we could not ask"
     *   into "your file is empty", and the copy will find out soon enough either way.
     */
    internal fun planFor(
        displayName: String?,
        mimeType: String?,
        sizeBytes: Long,
        hasFolder: Boolean,
    ): Plan {
        val storedAs = ImportableAudio.storedAs(displayName, mimeType)
            ?: return Plan.Refuse(Reason.NOT_AUDIO)
        if (sizeBytes == 0L) return Plan.Refuse(Reason.EMPTY)
        if (!hasFolder) return Plan.Refuse(Reason.NO_FOLDER)
        return Plan.Accept(storedAs)
    }

    /**
     * Imports [source], or says why it could not.
     *
     * Runs on [Dispatchers.IO] and never throws: an import that fails has to produce a sentence, not
     * a crash on the screen the user was standing on.
     *
     * @param source the URI handed back by the SAF picker.
     * @param importedAtMillis when the import happened; the catalog is stamped with this and nothing
     *   else. Injectable so the naming and the retention stamp can be pinned in a test.
     */
    suspend fun import(
        context: Context,
        source: Uri,
        importedAtMillis: Long = System.currentTimeMillis(),
    ): Outcome = withContext(Dispatchers.IO) {
        val meta = readMetadata(context, source)
        val folder = AppPreferences(context).getRecordingFolderUri()
            ?.takeIf { SafHelper.isFolderValid(context, it) }
        val storedAs = when (
            val plan = planFor(meta.displayName, meta.mimeType, meta.sizeBytes, hasFolder = folder != null)
        ) {
            is Plan.Refuse -> return@withContext refuse(
                plan.reason,
                "'${meta.displayName}' (${meta.mimeType}, ${meta.sizeBytes} bytes)",
            )
            is Plan.Accept -> plan.storedAs
        }
        // Smart-cast cannot survive the `when` above, and the plan has already established it.
        requireNotNull(folder)

        val displayName = ImportedRecording.nameFor(
            importedAtMillis = importedAtMillis,
            label = meta.displayName,
            extension = storedAs.extension,
        )
        val copied = SafHelper.copyFileToFolder(
            context = context,
            srcUri = source,
            destFolderUri = folder,
            displayName = displayName,
            mimeType = storedAs.mimeType,
            sourceSize = meta.sizeBytes,
        )
        val copyUri = when (copied) {
            is SafHelper.CopyResult.Copied -> copied.uri
            // The stamp carries milliseconds, so this is a name collision that should not be
            // reachable — but taking the existing document is still the right answer if it ever is.
            is SafHelper.CopyResult.AlreadyPresent -> copied.uri
            is SafHelper.CopyResult.Failed -> return@withContext refuse(Reason.COPY_FAILED, copied.reason)
        }

        if (!decodes(context, copyUri)) {
            // Deleted rather than kept: a file in the user's recordings folder that cannot be
            // transcribed is the exact outcome the probe exists to prevent, and leaving it there
            // would also leave it to the sweeps to reason about.
            SafHelper.deleteDocument(
                DocumentFile.fromSingleUri(context, copyUri),
                "the import '$displayName' this device cannot decode",
            )
            return@withContext refuse(Reason.UNDECODABLE, "'$displayName' could not be decoded on this device")
        }

        val sizeBytes = SafHelper.fileSize(context, copyUri).coerceAtLeast(0L)
        RecordingCatalog.recordLocal(
            context = context,
            displayName = displayName,
            localUri = copyUri,
            sizeBytes = sizeBytes,
            // The IMPORT time, never the source's. See this object's KDoc, and the note in
            // RetentionSweepWorker that asked for it.
            lastModified = importedAtMillis,
        )
        // Read once and remembered, exactly as a recording's is, so the list never has to open the
        // file for it — the cost that made the library slower the more of it there was.
        val durationSeconds = AudioDecoder.durationMs(context, copyUri)
            .takeIf { it > 0L }
            ?.let { (it + 500) / 1000 }
        if (durationSeconds != null) RecordingCatalog.setDuration(context, displayName, durationSeconds)

        AppLogger.i(TAG, "Imported '$displayName' ($sizeBytes bytes, ${durationSeconds ?: "unknown"}s)")
        Outcome.Imported(displayName)
    }

    /**
     * Whether this device can actually decode [uri], asked by decoding the first [PROBE_MS] of it.
     *
     * The app's own decoder, deliberately, not a second opinion: what has to be true is that
     * *transcription* will work, and transcription is [AudioDecoder]. That includes its refusal to
     * read anything but 16-bit PCM, which is what turns a 32-bit-float WAV down here rather than at
     * the end of a run the user waited for.
     */
    private fun decodes(context: Context, uri: Uri): Boolean = runCatching {
        AudioDecoder.decodeRange(context, uri, fromMs = 0L, toMs = PROBE_MS).audio.isNotEmpty()
    }.getOrElse { e ->
        AppLogger.w(TAG, "Decode probe of $uri failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    private fun refuse(reason: Reason, detail: String): Outcome {
        AppLogger.w(TAG, "Import refused ($reason): $detail")
        return Outcome.Refused(reason)
    }

    /** What the source says about itself: its name, its type and its size, each possibly unknown. */
    private data class Metadata(val displayName: String?, val mimeType: String?, val sizeBytes: Long)

    /**
     * Reads [source]'s metadata without opening it.
     *
     * `OpenableColumns` first because that is the contract a SAF or share URI actually implements;
     * [DocumentFile] is the fallback for the providers that answer the document contract instead.
     * A size of -1 means "not reported", which is different from zero and is treated as such.
     */
    private fun readMetadata(context: Context, source: Uri): Metadata {
        val mimeType = runCatching { context.contentResolver.getType(source) }.getOrNull()
        val queried = runCatching {
            context.contentResolver.query(source, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            }
        }.getOrNull()
        if (queried != null) return Metadata(queried.first, mimeType, queried.second)

        val doc = runCatching { DocumentFile.fromSingleUri(context, source) }.getOrNull()
        return Metadata(
            displayName = doc?.name,
            mimeType = mimeType ?: doc?.type,
            sizeBytes = doc?.length() ?: -1L,
        )
    }
}
