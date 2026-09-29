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
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.utils.AppLogger
import java.io.File
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * SafHelper provides utility functions for working with the Android Storage Access Framework (SAF).
 *
 * Users explicitly grant access to a folder via the system document-tree picker.
 */
object SafHelper {

    private const val TAG = "CV:SafHelper"

    /** Prefix for the app-private staging temp files used when a provider rejects `"rw"`. */
    private const val STAGING_TEMP_PREFIX = "rec_stage_"

    /** Staged output that is not a call (a merge): [CutOffRescueWorker] must never publish it. */
    private const val NON_RECOVERABLE_TEMP_PREFIX = "merge_stage_"
    private const val STAGING_TEMP_SUFFIX = ".tmp"

    /** Sub-directory of `filesDir` holding recordings that are still being made. */
    private const val STAGING_DIR_NAME = "staging"

    /**
     * Holds the result of a successful [createAudioFile] call.
     *
     * @param uri         The destination document, or **null until the recording is published**. A
     *                    recording in progress deliberately has no file in the user's folder — see
     *                    [createAudioFile] — so anything that needs a URI must wait for
     *                    [publishStagedRecording].
     * @param descriptor  An open read-write [ParcelFileDescriptor] the muxer writes into: always the
     *                    seekable staging file. Must be closed after use.
     * @param displayName A human-readable path for logging (e.g. "Recordings/call_incoming_….webm").
     * @param stagingFile Where the recording is being written. The caller publishes it with
     *                    [publishStagedRecording] once the container is finalised.
     * @param folderUri   The chosen folder, carried so the destination can be created at the end.
     * @param fileName    The name the published file will take.
     * @param mimeType    The MIME type the published file will take.
     */
    data class SafResult(
        val uri: Uri?,
        val descriptor: ParcelFileDescriptor,
        val displayName: String,
        val stagingFile: File? = null,
        val folderUri: Uri? = null,
        val fileName: String? = null,
        val mimeType: String? = null,
    )

    /**
     * Creates a new audio file inside the user-chosen SAF folder and returns a **seekable read-write**
     * descriptor for [MediaMuxer][android.media.MediaMuxer] to write into (it seeks back to patch the
     * container index on finalize, so a write-only fd is not enough).
     *
     * Most providers hand out a `"rw"` fd for a freshly created file. Some — Downloads, SD-card, and
     * cloud/synced or certain OEM document providers — reject it with `FileNotFoundException:
     * "Unsupported mode: rw"`. For those we transparently **stage** the recording to an app-private temp
     * file (internal storage always gives a real seekable rw fd) and return it via [SafResult.stagingFile];
     * the caller copies it into the SAF file write-only at finalize (the same path [copyFileToFolder]
     * proves works on every provider). This never throws for the mode issue — it returns null only if the
     * folder/file truly cannot be created.
     *
     * @param context    App context used to resolve the [DocumentFile] and open the FD.
     * @param folderUri  The tree URI of the destination folder (from the document-tree picker).
     * @param fileName   The desired file name including extension (e.g. "call_incoming_….webm").
     * @param mimeType   The MIME type of the file (e.g. "audio/webm" for Opus, "audio/mp4" for AAC).
     * @return A [SafResult] with the URI, open FD, display name, and optional staging file; or null on failure.
     */
    fun createAudioFile(
        context: Context,
        folderUri: Uri,
        fileName: String,
        mimeType: String,
        recoverable: Boolean = true,
    ): SafResult? {
        val directory = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        // Checked here rather than discovered at finalize: a folder the user has revoked or unmounted
        // must fail the recording immediately, while there is still someone to tell.
        if (!directory.canWrite()) return null

        // ALWAYS staged, and the destination file is NOT created yet. Both halves matter:
        //
        //  - Muxing straight into the SAF file meant the user's folder held a **growing** file for the
        //    whole call. Syncthing, FolderSync and Nextcloud upload what they find, so they captured
        //    truncated recordings; upstream diagnosed exactly this.
        //  - Creating the file up front and filling it at finalize is no better on its own: the folder
        //    then holds a **0-byte** file for the whole call, and a sync tool that uploads once takes
        //    the empty one.
        //
        // So nothing appears in the user's folder until there is a complete recording to put there.
        // The cost is one extra copy of a few megabytes, which is nothing beside losing the call.
        val prefix = if (recoverable) STAGING_TEMP_PREFIX else NON_RECOVERABLE_TEMP_PREFIX
        val tempFile = runCatching { File.createTempFile(prefix, STAGING_TEMP_SUFFIX, stagingDir(context)) }
            .getOrElse { e -> AppLogger.e(TAG, "Failed to create staging temp file", e); return null }
        // Where this recording is going, beside it, so a call cut off by the app being killed can still be
        // saved under its own name (CutOffRescueWorker). Best effort: without it the rescue uses a generic name.
        if (recoverable) {
            runCatching {
                StagingNote.fileFor(tempFile).writeText(
                    StagingNote(folderUri.toString(), fileName, mimeType, System.currentTimeMillis()).encode(),
                )
            }.onFailure { AppLogger.w(TAG, "Could not write the staging note for ${tempFile.name}: ${it.message}") }
        }
        val tempFd = runCatching {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }.getOrElse { e ->
            AppLogger.e(TAG, "Failed to open staging temp fd", e)
            runCatching { tempFile.delete() }
            return null
        }
        AppLogger.i(TAG, "Staging recording to ${tempFile.name} → will publish into $folderUri at finalize")
        return SafResult(
            uri = null,
            descriptor = tempFd,
            displayName = "${directory.name}/$fileName",
            stagingFile = tempFile,
            folderUri = folderUri,
            fileName = fileName,
            mimeType = mimeType,
        )
    }

    /**
     * Where a recording lives while it is being made.
     *
     * `filesDir`, deliberately, **not** `cacheDir`. Android is free to delete a cache directory when
     * storage runs low, and doing that to a call in progress would destroy the recording — the one
     * failure this whole staging arrangement exists to prevent. A directory the system will not touch
     * costs nothing extra, and the file is deleted as soon as it has been published.
     */
    private fun stagingDir(context: Context): File =
        File(context.filesDir, STAGING_DIR_NAME).apply { mkdirs() }

    /** The staging directory if it exists, without creating it. */
    fun stagingDirOrNull(context: Context): File? =
        File(context.filesDir, STAGING_DIR_NAME).takeIf { it.isDirectory }

    /** A call recording's staged file (not a merge, not a note). */
    fun isStagedRecording(file: File): Boolean =
        file.isFile && file.name.startsWith(STAGING_TEMP_PREFIX) && file.name.endsWith(STAGING_TEMP_SUFFIX)

    /**
     * Creates the destination file and writes [srcFile] into it, once there is a finished recording.
     *
     * Deliberately the *last* step. Until this runs, the user's folder contains nothing at all for this
     * call — which is what makes a half-uploaded or empty recording impossible rather than unlikely.
     *
     * @return the new document's URI, or null if the file could not be created or written. On failure
     *   the caller keeps the staged file rather than deleting it: an unpublished recording on internal
     *   storage is recoverable, and a deleted one is not.
     */
    fun publishStagedRecording(
        context: Context,
        folderUri: Uri,
        fileName: String,
        mimeType: String,
        srcFile: File,
    ): Uri? {
        val directory = DocumentFile.fromTreeUri(context, folderUri) ?: run {
            AppLogger.e(TAG, "Cannot resolve $folderUri to publish $fileName")
            return null
        }
        val newFile = directory.createFile(mimeType, fileName) ?: run {
            AppLogger.e(TAG, "Could not create $fileName in $folderUri")
            return null
        }
        if (!writeStagedFileToUri(context, srcFile, newFile.uri)) {
            // Remove the empty document we just made, or the folder is left holding exactly the 0-byte
            // file this change exists to prevent.
            runCatching { newFile.delete() }
            return null
        }
        return newFile.uri
    }

    /**
     * Streams a finished staged recording ([srcFile]) into the already-created SAF file at [destUri]
     * using a WRITE-ONLY stream — which works on the providers that reject `"rw"` (see [copyFileToFolder]).
     * Called at finalize when [createAudioFile] returned a [SafResult.stagingFile].
     *
     * @return true if the bytes were fully written; false on any failure (temp is then kept for recovery).
     */
    fun writeStagedFileToUri(context: Context, srcFile: File, destUri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(destUri, "wt")?.use { output ->
            srcFile.inputStream().use { input -> input.copyTo(output) }
        } ?: run {
            AppLogger.e(TAG, "openOutputStream returned null for $destUri")
            return false
        }
        true
    }.getOrElse { e ->
        AppLogger.e(TAG, "Failed to write staged recording into $destUri", e)
        false
    }

    /**
     * Returns true if [folderUri] points to an existing, writable SAF folder.
     * Used to validate the user's chosen recording folder before starting a session.
     *
     * @param context   App context used to resolve the [DocumentFile].
     * @param folderUri The tree URI to validate, or null.
     * @return true if the folder exists and is writable; false if null or inaccessible.
     */
    /**
     * SAF document-provider authorities that are cloud/synced and therefore unreliable as the LIVE
     * recording-capture folder: they reject `"rw"` and report file length asynchronously (0 right after
     * a write), which breaks in-progress capture and the empty-recording guard. They are perfectly fine
     * as the Drive *backup* target (that copy happens after the recording is finalised).
     */
    private val CLOUD_PROVIDER_AUTHORITIES = setOf(
        "com.google.android.apps.docs.storage",                          // Google Drive
        "com.google.android.apps.docs.storage.legacy",                   // Google Drive (legacy)
        "com.microsoft.skydrive.content.StorageAccessProvider",          // OneDrive
        "com.dropbox.product.android.dbapp.document_provider.documents", // Dropbox
    )

    /**
     * Returns true if [uri] is served by a known cloud/sync document provider (Google Drive, OneDrive,
     * Dropbox). Used to reject such folders as the capture destination and to warn about an existing one.
     */
    fun isCloudFolder(uri: Uri?): Boolean =
        uri?.authority?.let { it in CLOUD_PROVIDER_AUTHORITIES } == true

    @OptIn(ExperimentalContracts::class)
    fun isFolderValid(context: Context, folderUri: Uri?): Boolean {
        // Tells the compiler: if we returns true, folderUri is not null. Prevent false compiler error and warnings.
        contract {
            returns(true) implies (folderUri != null)
        }
        if (folderUri == null) return false
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        return directory != null && directory.exists() && directory.canWrite()
    }

    /**
     * Returns a human-readable display name for a SAF folder URI.
     * Used in the Settings screen to show which folder recordings are saved to.
     *
     * @param context   App context used to resolve the [DocumentFile].
     * @param folderUri The tree URI, or null.
     * @return The folder name (e.g. "Recordings"), or null.
     */
    fun getFolderDisplayNameOrNull(context: Context, folderUri: Uri?): String? {
        if (folderUri == null) return null
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        return directory?.name
    }

    /** Outcome of [copyFileToFolder] — an upload that was skipped is as good as one that ran. */
    sealed interface CopyResult {
        /** The recording was already in the destination folder; nothing was uploaded. */
        data class AlreadyPresent(val uri: Uri) : CopyResult

        /** The bytes were streamed and published under the final name. */
        data class Copied(val uri: Uri, val bytes: Long) : CopyResult

        /** Nothing usable landed in the destination; [reason] is safe to log (no file content). */
        data class Failed(val reason: String) : CopyResult
    }

    /**
     * Copies [srcUri] into [destFolderUri] as [displayName], **idempotently and atomically**.
     *
     * - *Idempotent*: a copy that is already there (same name, same size) is reported as
     *   [CopyResult.AlreadyPresent] instead of being uploaded a second time. Repeating the call — which
     *   is exactly what a WorkManager retry does — therefore costs nothing and produces no twin.
     * - *Atomic*: the bytes are streamed into a staging document ([CloudCopyPolicy.stagingNameFor]) and
     *   only renamed to [displayName] once the stream finished. An attempt killed mid-copy (the OEM
     *   background killer does this to large uploads) can then never leave something that *looks* like a
     *   finished recording; the next attempt cleans the leftover up and starts over.
     *
     * Uses a WRITE-ONLY output stream throughout, which is what works on cloud providers like Google
     * Drive (they reject "rw").
     *
     * @param context       App context used for content resolver operations.
     * @param srcUri        The content URI of the source file to copy.
     * @param destFolderUri The tree URI of the destination folder.
     * @param displayName   The desired file name for the copy (including extension).
     * @param mimeType      The MIME type of the file (e.g. "audio/ogg").
     * @param sourceSize    Byte length of the source, used to recognise a truncated earlier attempt.
     *                      Pass a non-positive value when it is unknown (nothing is then replaced).
     */
    fun copyFileToFolder(
        context: Context,
        srcUri: Uri,
        destFolderUri: Uri,
        displayName: String,
        mimeType: String,
        sourceSize: Long
    ): CopyResult {
        val dir = DocumentFile.fromTreeUri(context, destFolderUri)
            ?: return CopyResult.Failed("destination folder could not be resolved")
        if (!dir.canWrite()) return CopyResult.Failed("destination folder is not writable")

        val existing = runCatching { dir.findFile(displayName) }.getOrNull()
        if (existing != null) {
            val existingSize = runCatching { existing.length() }.getOrDefault(-1L)
            when (CloudCopyPolicy.verdict(existingSize, sourceSize)) {
                ExistingCopyVerdict.COMPLETE -> return CopyResult.AlreadyPresent(existing.uri)
                ExistingCopyVerdict.PARTIAL -> {
                    AppLogger.w(TAG, "Replacing a truncated '$displayName' ($existingSize of $sourceSize bytes)")
                    if (runCatching { existing.delete() }.getOrDefault(false).not()) {
                        return CopyResult.Failed("the truncated '$displayName' could not be removed")
                    }
                }
            }
        }

        // A leftover from an attempt that was killed mid-stream; it holds no value, only bytes.
        runCatching { dir.findFile(CloudCopyPolicy.stagingNameFor(displayName))?.delete() }

        val staged = dir.createFile(mimeType, CloudCopyPolicy.stagingNameFor(displayName))
            ?: return CopyResult.Failed("staging document for '$displayName' could not be created")
        // Renaming is how the copy is published. A provider that cannot rename (rare) gets the plain
        // write instead — the size check above still heals a truncated result on the following attempt.
        if (!supportsRename(context, staged.uri)) {
            runCatching { staged.delete() }
            AppLogger.i(TAG, "Destination provider cannot rename; writing '$displayName' directly")
            return streamInto(context, srcUri, dir, displayName, mimeType)
        }

        val bytes = streamBytes(context, srcUri, staged.uri)
            ?: return CopyResult.Failed("streaming '$displayName' into the staging document failed")
                .also { runCatching { staged.delete() } }

        val published = runCatching {
            DocumentsContract.renameDocument(context.contentResolver, staged.uri, displayName)
        }.getOrElse { e ->
            runCatching { staged.delete() }
            return CopyResult.Failed("publishing '$displayName' failed: ${e.message}")
        }
        // renameDocument returns null when the document keeps its URI — the rename still happened.
        return CopyResult.Copied(published ?: staged.uri, bytes)
    }

    /** Plain create-and-write, for destinations that do not support renaming a document. */
    private fun streamInto(
        context: Context,
        srcUri: Uri,
        dir: DocumentFile,
        displayName: String,
        mimeType: String
    ): CopyResult {
        val dest = dir.createFile(mimeType, displayName)
            ?: return CopyResult.Failed("'$displayName' could not be created")
        val bytes = streamBytes(context, srcUri, dest.uri)
            ?: return CopyResult.Failed("streaming '$displayName' failed")
                .also { runCatching { dest.delete() } }
        return CopyResult.Copied(dest.uri, bytes)
    }

    /** Streams [srcUri] into [destUri] write-only. Returns the byte count, or null if anything failed. */
    private fun streamBytes(context: Context, srcUri: Uri, destUri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(srcUri)?.use { input ->
            context.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                input.copyTo(output)
            }
        }
    }.getOrElse { e ->
        AppLogger.w(TAG, "Copy stream failed: ${e.message}")
        null
    }

    /** True when the document at [uri] can be renamed — the mechanism the atomic publish relies on. */
    private fun supportsRename(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
        )?.use { cursor ->
            cursor.moveToFirst() &&
                (cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        } ?: false
    }.getOrDefault(false)

    /**
     * Deletes [doc] and says so when it does not happen.
     *
     * [DocumentFile.delete] reports a refusal by **returning false**, not by throwing, so the common
     * `runCatching { doc.delete() }` idiom silently accepts a cleanup that never ran. That is how empty
     * recordings piled up in a user's folder: the guard that rejects a 0-byte capture asked for it to be
     * deleted, the provider declined, and nothing in the log ever mentioned it.
     *
     * @param doc  the document to remove; null means there was nothing to delete, which is not a failure.
     * @param what a short description of the document, for the log line ("the empty recording 'x.ogg'").
     * @return true when the document is gone (or was never there).
     */
    fun deleteDocument(doc: DocumentFile?, what: String): Boolean {
        if (doc == null) return true
        val deleted = runCatching { doc.delete() }.getOrElse { e ->
            AppLogger.w(TAG, "Deleting $what threw: ${e.message}")
            false
        }
        if (!deleted) AppLogger.w(TAG, "Deleting $what was refused by the provider; it stays in the folder")
        return deleted
    }

    /**
     * Returns the byte length of the document at [uri], or -1 if unknown.
     *
     * @param context App context used to resolve the [DocumentFile].
     * @param uri     The content URI of the document to measure.
     * @return The file size in bytes, or -1 if unavailable.
     */
    fun fileSize(context: Context, uri: Uri): Long =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.length() ?: -1L }.getOrDefault(-1L)
}
