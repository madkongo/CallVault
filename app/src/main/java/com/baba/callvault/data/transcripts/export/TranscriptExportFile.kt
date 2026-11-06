/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

import android.content.Context
import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * Writes an export to a file the share-sheet can attach.
 *
 * **In `cacheDir`, deliberately.** An exported transcript is a copy made to hand somewhere else, not
 * a second place the app keeps the user's data: the original stays in the database. Putting it in the
 * cache means the system can reclaim it, and means a transcript never accumulates silently in private
 * storage where a file manager cannot reach it and the user cannot know it is there.
 *
 * The folder is emptied before each export rather than after: a share is asynchronous and the
 * receiving app may still be reading the file when the chooser closes, so deleting it on the way out
 * is a race. Clearing on the way *in* keeps exactly one export on disk and cannot delete a file
 * something is currently reading.
 */
object TranscriptExportFile {

    private const val TAG = "CV:TranscriptExport"

    /** Matches `res/xml/file_paths.xml`; anything outside it cannot be shared. */
    private const val DIRECTORY = "exports"

    /**
     * Writes [content] as [fileName] and returns it, or null if it could not be written.
     *
     * Null rather than an exception because the caller is a tap: there is a person waiting, and the
     * honest response to "the cache is full" is a message, not a crash.
     */
    fun write(context: Context, fileName: String, content: String): File? =
        writeAll(context, listOf(fileName to content))?.single()

    /**
     * Writes several files for ONE share, clearing the folder once first, or returns null if any could not be
     * written. [write] clears it on every call, so writing a call's transcript and then its summary through it
     * would delete the transcript before the share sheet ever read it.
     */
    fun writeAll(context: Context, files: List<Pair<String, String>>): List<File>? = runCatching {
        val dir = File(context.cacheDir, DIRECTORY)
        if (dir.exists()) dir.listFiles()?.forEach { it.delete() } else dir.mkdirs()

        files.map { (fileName, content) ->
            // The name reaches here from a recording's display name, which is derived from a contact
            // name and can therefore contain anything a contact can. A separator would write outside the
            // shared folder; the rest are simply not valid on every filesystem the file may end up on.
            val safe = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            File(dir, safe).apply { writeText(content) }
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not write the export: ${it.message}")
        null
    }
}
