/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.net.Uri
import android.os.Bundle

/**
 * The messages between the app and the Drive copier process ([DriveCopyService]).
 *
 * Plain [Bundle]s over a Messenger rather than an AIDL interface: three calls, all request/response, and
 * nothing here may ever be confused with the recorder's own AIDL (whose transaction codes are positional).
 */
object DriveCopyProtocol {

    /** The copier's process suffix. Used by the Application to skip the app's own start-up there. */
    const val PROCESS_SUFFIX = ":drive"

    fun isCopierProcess(processName: String?): Boolean = processName?.endsWith(PROCESS_SUFFIX) == true

    const val MSG_LIST = 1
    const val MSG_COPY = 2

    // Request keys
    const val K_FOLDER = "folder"
    const val K_SRC = "src"
    const val K_NAME = "name"
    const val K_MIME = "mime"
    const val K_SIZE = "size"
    const val K_EXISTING = "existing"
    const val K_EXISTING_SIZE = "existingSize"
    const val K_STAGING = "staging"
    const val K_LOOKUP = "lookup"

    // Reply keys
    const val K_OK = "ok"
    const val K_ERROR = "error"
    const val K_NAMES = "names"
    const val K_URIS = "uris"
    const val K_SIZES = "sizes"
    const val K_KIND = "kind"
    const val K_RESULT_URI = "resultUri"
    const val K_BYTES = "bytes"
    const val K_TIMING_NAMES = "timingNames"
    const val K_TIMING_MS = "timingMs"

    const val KIND_COPIED = 0
    const val KIND_ALREADY = 1
    const val KIND_FAILED = 2

    /** What one folder listing returned, in the order the provider gave it. */
    class Listing(val names: List<String>, val uris: List<Uri>, val sizes: List<Long>)

    fun listRequest(folder: Uri) = Bundle().apply { putParcelable(K_FOLDER, folder) }

    fun listReply(listing: Listing) = Bundle().apply {
        putBoolean(K_OK, true)
        putStringArray(K_NAMES, listing.names.toTypedArray())
        putParcelableArrayList(K_URIS, ArrayList(listing.uris))
        putLongArray(K_SIZES, listing.sizes.toLongArray())
    }

    fun failureReply(reason: String) = Bundle().apply {
        putBoolean(K_OK, false)
        putString(K_ERROR, reason)
    }

    @Suppress("DEPRECATION")
    fun readListing(reply: Bundle): Listing? {
        if (!reply.getBoolean(K_OK)) return null
        val names = reply.getStringArray(K_NAMES) ?: return null
        val uris = reply.getParcelableArrayList<Uri>(K_URIS) ?: return null
        val sizes = reply.getLongArray(K_SIZES) ?: return null
        return Listing(names.toList(), uris, sizes.toList())
    }

    /** What the app already knows about the destination, so the copier never lists the folder again. */
    class Request(
        val src: Uri,
        val folder: Uri,
        val name: String,
        val mime: String,
        val sourceSize: Long,
        val existing: Uri?,
        val existingSize: Long,
        val staging: Uri?,
        /** True when the caller has NOT listed the folder: the copier then looks the name up itself. */
        val lookup: Boolean = false,
    )

    fun copyRequest(r: Request) = Bundle().apply {
        putBoolean(K_LOOKUP, r.lookup)
        putParcelable(K_SRC, r.src)
        putParcelable(K_FOLDER, r.folder)
        putString(K_NAME, r.name)
        putString(K_MIME, r.mime)
        putLong(K_SIZE, r.sourceSize)
        r.existing?.let { putParcelable(K_EXISTING, it) }
        putLong(K_EXISTING_SIZE, r.existingSize)
        r.staging?.let { putParcelable(K_STAGING, it) }
    }

    @Suppress("DEPRECATION")
    fun readCopyRequest(b: Bundle): Request? = Request(
        src = b.getParcelable(K_SRC) ?: return null,
        folder = b.getParcelable(K_FOLDER) ?: return null,
        name = b.getString(K_NAME) ?: return null,
        mime = b.getString(K_MIME) ?: return null,
        sourceSize = b.getLong(K_SIZE),
        existing = b.getParcelable(K_EXISTING),
        existingSize = b.getLong(K_EXISTING_SIZE, -1L),
        staging = b.getParcelable(K_STAGING),
        lookup = b.getBoolean(K_LOOKUP),
    )

    fun copyReply(result: SafHelper.CopyResult, timings: CopyTimings) = Bundle().apply {
        putBoolean(K_OK, true)
        when (result) {
            is SafHelper.CopyResult.Copied -> {
                putInt(K_KIND, KIND_COPIED); putParcelable(K_RESULT_URI, result.uri); putLong(K_BYTES, result.bytes)
            }
            is SafHelper.CopyResult.AlreadyPresent -> {
                putInt(K_KIND, KIND_ALREADY); putParcelable(K_RESULT_URI, result.uri)
            }
            is SafHelper.CopyResult.Failed -> {
                putInt(K_KIND, KIND_FAILED); putString(K_ERROR, result.reason)
            }
        }
        val map = timings.asMap()
        putStringArray(K_TIMING_NAMES, map.keys.toTypedArray())
        putLongArray(K_TIMING_MS, map.values.toLongArray())
    }

    /** The reply turned back into a result, plus the copier's own step timings (`step=12ms ...`). */
    @Suppress("DEPRECATION")
    fun readCopyReply(reply: Bundle): Pair<SafHelper.CopyResult, String> {
        val names = reply.getStringArray(K_TIMING_NAMES) ?: emptyArray()
        val ms = reply.getLongArray(K_TIMING_MS) ?: LongArray(0)
        val timing = names.indices.joinToString(" ") { "${names[it]}=${ms.getOrElse(it) { 0 }}ms" }
        if (!reply.getBoolean(K_OK)) {
            return SafHelper.CopyResult.Failed(reply.getString(K_ERROR) ?: "the copier gave no reason") to timing
        }
        val uri = reply.getParcelable<Uri>(K_RESULT_URI)
        val result = when (reply.getInt(K_KIND)) {
            KIND_COPIED -> if (uri != null) SafHelper.CopyResult.Copied(uri, reply.getLong(K_BYTES))
            else SafHelper.CopyResult.Failed("the copier returned no document")
            KIND_ALREADY -> if (uri != null) SafHelper.CopyResult.AlreadyPresent(uri)
            else SafHelper.CopyResult.Failed("the copier returned no document")
            else -> SafHelper.CopyResult.Failed(reply.getString(K_ERROR) ?: "copy failed")
        }
        return result to timing
    }
}
