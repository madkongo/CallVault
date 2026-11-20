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
import com.baba.callvault.system.diagnostics.CallEndLog
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * Calls recorded while the recording folder was not usable, kept in CallVault's own storage until a folder is usable
 * again (review N5).
 *
 * Field report (Motorola edge 70 fusion, Android 16, 2.5.1): the user deleted the recording folder by accident and
 * three Telegram calls were refused, with nothing saved and nothing said. A call is now never refused for its folder:
 * it is recorded here instead, and [WaitingRecordingsWorker] saves it into the folder under its own name, with its
 * library entry, once a folder is usable.
 *
 * **Only when the folder is not usable.** [chooseOutput] asks the folder first and comes here only when the folder
 * failed, so a phone whose folder works records exactly as before: same staging area, same names, same timing.
 *
 * A directory of its own (`filesDir/waiting`), not the staging area: the cut-off rescue publishes staged files into
 * the folder their note names, which is exactly the folder that is gone, and would report them as calls "cut off".
 * Notes use the [StagingNote] format, so the orphan-session check still reads a waiting call's start time.
 *
 * `filesDir`, never `cacheDir`: Android may clear a cache when space runs low. The files are lost if CallVault is
 * uninstalled, which the alert says.
 */
object WaitingRecordings {

    private const val TAG = "CV:Waiting"
    const val DIR_NAME = "waiting"
    private const val PREFIX = "rec_wait_"
    private const val SUFFIX = ".tmp"

    /**
     * The folder's own output when it is usable ([inFolder], today's path, untouched); waiting storage otherwise, and
     * also when the folder looked usable but its file could not be created.
     */
    inline fun <T : Any> chooseOutput(folderUsable: Boolean, inFolder: () -> T?, waiting: () -> T?): T? =
        (if (folderUsable) inFolder() else null) ?: waiting()

    fun dirOrNull(context: Context): File? = File(context.filesDir, DIR_NAME).takeIf { it.isDirectory }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun isWaitingRecording(file: File): Boolean =
        file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX)

    /** The waiting recordings (not their notes). */
    fun files(context: Context): List<File> =
        dirOrNull(context)?.listFiles().orEmpty().filter { isWaitingRecording(it) }

    fun count(context: Context): Int = runCatching { files(context).size }.getOrDefault(0)

    fun totalBytes(context: Context): Long = runCatching { files(context).sumOf { it.length() } }.getOrDefault(0L)

    private fun freeBytes(context: Context): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(-1L)

    fun admission(context: Context): WaitingPolicy.Admission = WaitingPolicy.admit(totalBytes(context), freeBytes(context))

    /** Whether a new call can be kept here now. */
    fun canAccept(context: Context): Boolean = admission(context) == WaitingPolicy.Admission.OK

    /**
     * Opens a waiting recording for a call whose folder is not usable ([why], for the log). Null when it cannot be
     * kept (the cap or the phone's space is reached, or the file could not be made); the caller then does what it did
     * before this existed, and the reason is logged.
     *
     * @param kind "phone call" or "app call", for the always-on log.
     */
    fun create(context: Context, folderUri: Uri?, fileName: String, mimeType: String, why: String, kind: String): SafHelper.SafResult? {
        val admission = admission(context)
        if (admission != WaitingPolicy.Admission.OK) {
            CallEndLog.record(
                context,
                "$kind: recording folder not usable ($why); cannot keep it in CallVault either: " +
                    (if (admission == WaitingPolicy.Admission.FULL) "waiting calls reached their ${WaitingPolicy.CAP_BYTES} byte cap"
                    else "the phone has under ${WaitingPolicy.MIN_FREE_BYTES} bytes free") + "; not recorded",
            )
            onRefused(context, admission)
            return null
        }
        val file = runCatching { File.createTempFile(PREFIX, SUFFIX, dir(context)) }
            .getOrElse { e -> CallEndLog.record(context, "$kind: could not create a waiting recording: ${CallEndLog.describe(e)}"); return null }
        runCatching {
            WaitingNote.write(file, WaitingNote(StagingNote(folderUri?.toString() ?: "none", fileName, mimeType, System.currentTimeMillis()), null))
        }.onFailure { AppLogger.w(TAG, "Could not write the note of ${file.name}: ${it.message}") }
        val fd = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }.getOrElse { e ->
            CallEndLog.record(context, "$kind: could not open a waiting recording: ${CallEndLog.describe(e)}")
            StagingNote.deleteWithNote(file)
            return null
        }
        // Never taken for an abandoned file while its call goes on: a paused recording is quiet (review C12).
        LiveStagingFiles.register(file)
        CallEndLog.record(
            context,
            "$kind: recording folder not usable ($why); recording into CallVault's own storage, to save into a folder later: ${CallEndLog.safeName(fileName)}",
        )
        return SafHelper.SafResult(
            uri = null,
            descriptor = fd,
            displayName = "CallVault/$fileName",
            stagingFile = file,
            folderUri = null,
            fileName = fileName,
            mimeType = mimeType,
            waiting = true,
        )
    }

    /**
     * The call of a waiting recording ended (its capture is closed): keep it under [name] for the after-call steps, or
     * remove it when it holds no audio. Returns whether a recording is kept. The file stays owned
     * ([LiveStagingFiles]) and unfinished until [ready], so it is never saved under a name the call log is about to
     * change. Never throws.
     */
    fun end(context: Context, file: File, name: String, mimeType: String, kind: String): Boolean = runCatching {
        val bytes = if (file.exists()) file.length() else 0L
        if (bytes <= 0L) {
            LiveStagingFiles.release(file)
            StagingNote.deleteWithNote(file)
            CallEndLog.record(context, "$kind: the waiting recording holds no audio; nothing kept")
            return@runCatching false
        }
        LiveStagingFiles.register(file)
        rename(file, name, mimeType)
        true
    }.getOrElse { e ->
        AppLogger.e(TAG, "Ending the waiting recording ${file.name} failed; it stays where it is", e)
        true
    }

    /** Gives a waiting recording the name it will be saved under (the call-log rename). Never throws. */
    fun rename(file: File, name: String, mimeType: String? = null): Boolean = runCatching {
        val old = WaitingNote.read(file)
        val note = old?.note?.copy(fileName = name, mimeType = mimeType ?: old.note.mimeType)
            ?: StagingNote("none", name, mimeType ?: "audio/ogg", file.lastModified())
        WaitingNote.write(file, WaitingNote(note, old?.finishedAt, old?.extras))
        true
    }.onFailure { AppLogger.w(TAG, "Could not rename the waiting recording ${file.name}: ${it.message}") }.getOrDefault(false)

    /**
     * The after-call steps of a waiting recording are done: mark it finished with what saving needs ([extras]), so the
     * publisher may take it, and tell the user. Never throws.
     */
    fun ready(context: Context, file: File, mimeType: String, kind: String, extras: WaitingNote.Extras) {
        runCatching {
            LiveStagingFiles.release(file)
            val old = WaitingNote.read(file)
            val note = old?.note?.copy(mimeType = mimeType) ?: StagingNote("none", file.name, mimeType, file.lastModified())
            runCatching { WaitingNote.write(file, WaitingNote(note, System.currentTimeMillis(), extras)) }
                .onFailure { AppLogger.w(TAG, "Could not mark ${file.name} finished; it will be saved as a cut-off: ${it.message}") }
            CallEndLog.record(
                context,
                "$kind kept in CallVault until a recording folder is usable: ${CallEndLog.safeName(note.fileName)} " +
                    "size=${file.length()} bytes; waiting now: ${count(context)} calls, ${totalBytes(context)} bytes",
            )
            // The call is real evidence that the folder is gone: measure now (at the call's end, never its start) so Home
            // and the alert follow.
            RecordingFolderCheck.checkAsync(context, "a call found the folder not usable")
            onKept(context)
        }.onFailure { AppLogger.e(TAG, "Finishing the waiting recording ${file.name} failed; it stays where it is", it) }
    }

    /** [end] and [ready] together, for a call with no after-call steps to run. Returns whether a recording is kept. */
    fun finish(context: Context, file: File, finalName: String, mimeType: String, kind: String): Boolean {
        if (!end(context, file, finalName, mimeType, kind)) return false
        ready(context, file, mimeType, kind, WaitingNote.Extras(isVoip = kind == "app call", appCallMix = false, direction = null))
        return true
    }

    private fun onKept(context: Context) = FolderProtection.onWaitingKept(context)

    private fun onRefused(context: Context, admission: WaitingPolicy.Admission) = FolderProtection.onWaitingRefused(context, admission)
}
