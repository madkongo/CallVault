/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */


package com.baba.callvault.system.storage

import java.io.File

/** What a direct look at the folder's path on the phone's own storage said. */
enum class PathLook {
    /** Not looked at: the case did not call for it (see [FolderCheckPolicy.shouldLookAtPath]). */
    NOT_LOOKED,

    /** The folder is there as a directory. */
    EXISTS,

    /** The folder is not there, and the look was shown to work (a standard folder beside it was seen). */
    ABSENT,

    /** Nothing could be proven: the storage root or every standard folder was out of sight too. */
    CANNOT_TELL,
}

/**
 * A second opinion on a recording folder on the phone's own (primary) storage, for when its provider gives no answer
 * at all (OnePlus 9 Pro, OxygenOS 14, 2026-11-20: a folder renamed away answered with a null cursor, not "no such
 * file", and the loss waited out the 10-minute transient grace).
 *
 * Why a path look, and why it can be trusted. The app runs on Android 11+ (minSdk 30), where shared storage is served
 * by FUSE: any app may look a directory up by its path without a storage permission, except other apps' folders under
 * `Android/` (those are left out, see [relativePathOnPrimary]). The tree grant cannot reach the folder's parent (it
 * covers the folder only), and `DocumentFile.exists()` asks the same provider that gave no answer, so neither helps.
 * An absent folder counts only when standard folders at the root (Download, DCIM, ...) are seen at the same moment:
 * that shows the look works on this phone. If none is seen, or the root itself is not, the look proves nothing
 * ([PathLook.CANNOT_TELL]) and the reading stays transient, so no false alarm.
 *
 * SD cards and cloud folders never get here: an SD card may simply be unmounted, and a cloud folder may be offline.
 */
object FolderPathLook {

    private const val PRIMARY_PREFIX = "primary:"

    /** Folders the system keeps at the root of the phone's storage; any one seen shows the look works. */
    private val STANDARD_FOLDERS = listOf("Download", "DCIM", "Pictures", "Music", "Documents", "Movies", "Alarms", "Ringtones", "Notifications")

    /** The result, with what was seen, for the `folder:` log line. */
    data class Result(val look: PathLook, val evidence: String)

    /**
     * The folder's path below the primary storage root, from the tree's document id ("primary:Music/Calls"), or null
     * when a path look would prove nothing: another volume, the root itself, a `..` segment, or a folder under
     * `Android/` (other apps' folders there are hidden from us, so "not seen" there is not "not there").
     */
    fun relativePathOnPrimary(treeDocumentId: String): String? {
        if (!treeDocumentId.startsWith(PRIMARY_PREFIX, ignoreCase = true)) return null
        val segments = treeDocumentId.substring(PRIMARY_PREFIX.length).split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.first().equals("Android", ignoreCase = true)) return null
        return segments.joinToString("/")
    }

    /** Looks at [relative] below [root]. Blocking (file system), cheap: a few `stat` calls. Never throws. */
    fun look(root: File, relative: String): Result = runCatching {
        if (!root.isDirectory) return@runCatching Result(PathLook.CANNOT_TELL, "storage root not visible")
        val target = File(root, relative)
        if (target.isDirectory) return@runCatching Result(PathLook.EXISTS, "path exists")
        val seen = STANDARD_FOLDERS.filter { it != relative && File(root, it).isDirectory }
        if (seen.isEmpty()) return@runCatching Result(PathLook.CANNOT_TELL, "path not seen, but no standard folder seen either")
        val what = if (target.exists()) "a file, not a folder, is at the path" else "path not there"
        Result(PathLook.ABSENT, "$what; ${seen.take(2).joinToString("+")} seen beside it")
    }.getOrElse { Result(PathLook.CANNOT_TELL, "path look threw ${it.javaClass.simpleName}") }
}
