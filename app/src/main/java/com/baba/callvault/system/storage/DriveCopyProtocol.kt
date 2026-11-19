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
import android.os.Message
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

    /**
     * An empty reply to [request]: the same message kind, and the request's sequence number (`arg1`) echoed so
     * the app can tell which call it answers (review C15).
     */
    fun replyTo(request: Message): Message = Message.obtain(null, request.what).also { it.arg1 = request.arg1 }

    fun listRequest(folder: Uri) = Bundle().apply { putParcelable(K_FOLDER, folder) }

    /** The listing inside the reply itself: only for the in-process copier, where no binder carries it. */
    fun listReply(listing: Listing) = Bundle().apply {
        putBoolean(K_OK, true)
        putStringArray(K_NAMES, listing.names.toTypedArray())
        putParcelableArrayList(K_URIS, ArrayList(listing.uris))
        putLongArray(K_SIZES, listing.sizes.toLongArray())
    }

    /**
     * The listing as a file in the app's cache folder, and a reply that carries only its path and counts.
     *
     * A Messenger reply travels through the binder, whose transaction buffer is about 1 MB shared by
     * everything in flight, and a one-way reply gets half of that. A Honor user's Drive folder of 1484 entries
     * made a 654 KB reply that never arrived (`TransactionTooLargeException`), and the app waited three
     * minutes for it. The copier process and the app share one uid and one cache folder, so a file is the
     * simplest carrier whose size does not matter. Chunked replies would need sequence numbers and a
     * partial-failure story; a file is written whole or the call fails with a reason.
     */
    fun listReplyViaFile(listing: Listing, dir: File): Bundle {
        dir.mkdirs()
        val file = File.createTempFile(LIST_FILE_PREFIX, ".bin", dir)
        try {
            file.outputStream().buffered().use { ListingCodec.write(listing.names, listing.uris.map { it.toString() }, listing.sizes, it) }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return Bundle().apply {
            putBoolean(K_OK, true)
            putString(K_LIST_FILE, file.absolutePath)
            putInt(K_COUNT, listing.names.size)
            putLong(K_LIST_BYTES, file.length())
        }
    }

    fun failureReply(reason: String) = Bundle().apply {
        putBoolean(K_OK, false)
        putString(K_ERROR, reason)
    }

    /** The reason sent instead of a reply the binder would not carry; says how big it was. */
    fun tooLargeReason(entries: Int, bytes: Int, cause: String): String =
        "reply too large: $entries entries, $bytes bytes ($cause)"

    const val K_LIST_FILE = "listFile"
    const val K_COUNT = "count"
    const val K_LIST_BYTES = "listBytes"
    const val LIST_FILE_PREFIX = "drive-list-"

    /** The listing a reply carries (inline or in a file), or null with the reason it could not be read. */
    @Suppress("DEPRECATION")
    fun readListingWithReason(reply: Bundle): Pair<Listing?, String?> {
        if (!reply.getBoolean(K_OK)) return null to readError(reply)
        val path = reply.getString(K_LIST_FILE)
        if (path != null) {
            val file = File(path)
            return try {
                val (names, uris, sizes) = file.inputStream().buffered().use { ListingCodec.read(it) }
                val expected = reply.getInt(K_COUNT, -1)
                if (expected >= 0 && expected != names.size) {
                    null to "the list file held ${names.size} entries, the reply said $expected"
                } else {
                    Listing(names, uris.map { Uri.parse(it) }, sizes) to null
                }
            } catch (t: Throwable) {
                null to "the list file could not be read: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                file.delete()
            }
        }
        val names = reply.getStringArray(K_NAMES) ?: return null to "the reply carried no names"
        val uris = reply.getParcelableArrayList<Uri>(K_URIS) ?: return null to "the reply carried no URIs"
        val sizes = reply.getLongArray(K_SIZES) ?: return null to "the reply carried no sizes"
        return Listing(names.toList(), uris, sizes.toList()) to null
    }

    fun readListing(reply: Bundle): Listing? = readListingWithReason(reply).first

    /** Leftover list files (a reader that died before deleting one) older than an hour are removed. */
    fun sweepStaleListFiles(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach {
            if (it.name.startsWith(LIST_FILE_PREFIX) && now - it.lastModified() > 60 * 60_000L) it.delete()
        }
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

    /** The reason a reply gave for failing, or null when it did not fail (or gave none). */
    fun readError(reply: Bundle): String? = if (reply.getBoolean(K_OK)) null else reply.getString(K_ERROR)

    /** What the provider reports for the document a copy ended in, or -1: see [copyReply]. */
    const val K_DRIVE_SIZE = "driveSize"

    /**
     * [driveSize] is the Drive document's length as the provider reports it AFTER the copy (-1 when it will
     * not say). It is what the app checks before deleting the device copy: a delete never rests on "the copy
     * call returned". [name] is the recording the request named, so the app can refuse a reply for another one.
     */
    fun copyReply(result: SafHelper.CopyResult, timings: CopyTimings, driveSize: Long, name: String) = Bundle().apply {
        putBoolean(K_OK, true)
        putLong(K_DRIVE_SIZE, driveSize)
        putString(K_NAME, name)
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

    /** A copy reply read back: the result, the copier's step timings, and the Drive document's reported length. */
    data class CopyReply(val result: SafHelper.CopyResult, val steps: String, val driveSize: Long)

    /**
     * The reply turned back into a result. A successful reply that names another recording than [expectedName]
     * reads as a failure with no Drive length, so nothing is deleted or stamped on it (review C15).
     */
    @Suppress("DEPRECATION")
    fun readCopyReply(reply: Bundle, expectedName: String): CopyReply {
        val names = reply.getStringArray(K_TIMING_NAMES) ?: emptyArray()
        val ms = reply.getLongArray(K_TIMING_MS) ?: LongArray(0)
        val timing = names.indices.joinToString(" ") { "${names[it]}=${ms.getOrElse(it) { 0 }}ms" }
        val driveSize = reply.getLong(K_DRIVE_SIZE, -1L)
        if (!reply.getBoolean(K_OK)) {
            return CopyReply(SafHelper.CopyResult.Failed(reply.getString(K_ERROR) ?: "the copier gave no reason"), timing, -1L)
        }
        val repliedFor = reply.getString(K_NAME)
        if (repliedFor != expectedName) {
            return CopyReply(
                SafHelper.CopyResult.Failed("the copier's reply was for '$repliedFor', not '$expectedName'; ignored"), timing, -1L,
            )
        }
        val uri = reply.getParcelable<Uri>(K_RESULT_URI)
        val result = when (reply.getInt(K_KIND)) {
            KIND_COPIED -> if (uri != null) SafHelper.CopyResult.Copied(uri, reply.getLong(K_BYTES))
            else SafHelper.CopyResult.Failed("the copier returned no document")
            KIND_ALREADY -> if (uri != null) SafHelper.CopyResult.AlreadyPresent(uri)
            else SafHelper.CopyResult.Failed("the copier returned no document")
            else -> SafHelper.CopyResult.Failed(reply.getString(K_ERROR) ?: "copy failed")
        }
        return CopyReply(result, timing, driveSize)
    }
}

/**
 * The on-disk form of a folder listing ([DriveCopyProtocol.listReplyViaFile]): a count, then per entry the
 * name, the document URI and the length. Plain strings and numbers, so it needs nothing from Android.
 */
object ListingCodec {
    fun write(names: List<String>, uris: List<String>, sizes: List<Long>, out: OutputStream) {
        require(names.size == uris.size && names.size == sizes.size) { "listing columns differ in length" }
        val data = DataOutputStream(out)
        data.writeInt(names.size)
        for (i in names.indices) {
            data.writeUTF(names[i])
            data.writeUTF(uris[i])
            data.writeLong(sizes[i])
        }
        data.flush()
    }

    fun read(input: InputStream): Triple<List<String>, List<String>, List<Long>> {
        val data = DataInputStream(input)
        val count = data.readInt()
        require(count >= 0) { "negative entry count $count" }
        val names = ArrayList<String>(count)
        val uris = ArrayList<String>(count)
        val sizes = ArrayList<Long>(count)
        repeat(count) {
            names += data.readUTF()
            uris += data.readUTF()
            sizes += data.readLong()
        }
        return Triple(names, uris, sizes)
    }
}
