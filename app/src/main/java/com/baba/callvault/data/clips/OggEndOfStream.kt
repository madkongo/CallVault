/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.clips

import java.io.RandomAccessFile

/**
 * Marks the last page of an Ogg file as the end of its stream.
 *
 * Android's Ogg muxer never sets that flag (measured 2026-11-04: our own recordings end with page flags 0x0), and
 * strict players refuse such a file. BCR fixes it the same way: set bit 0x04 of the last page's header-type byte and
 * recompute that page's CRC. Nothing else in the file changes.
 */
object OggEndOfStream {

    private val CAPTURE = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private const val HEADER_TYPE = 5
    private const val CRC_OFFSET = 22
    private const val SEGMENT_COUNT = 26
    private const val HEADER_BYTES = 27
    private const val EOS_FLAG = 0x04

    /** How far from the end to look for the last page; a page is at most ~64 KB. */
    private const val TAIL_BYTES = 70 * 1024

    /** Patches [file] in place. Returns false, changing nothing, when no well-formed last page is found. */
    fun mark(file: java.io.File): Boolean = RandomAccessFile(file, "rw").use { raf ->
        val len = raf.length()
        val from = (len - TAIL_BYTES).coerceAtLeast(0L)
        val tail = ByteArray((len - from).toInt())
        raf.seek(from)
        raf.readFully(tail)
        val patched = markLastPage(tail) ?: return false
        raf.seek(from + patched.first)
        raf.write(patched.second)
        true
    }

    /**
     * The last page of [bytes] with the flag set and its CRC recomputed, with its offset in [bytes]; or null when
     * [bytes] has no complete page ending exactly at its end.
     */
    fun markLastPage(bytes: ByteArray): Pair<Int, ByteArray>? {
        var at = lastIndexOf(bytes, CAPTURE)
        while (at >= 0) {
            val size = pageSize(bytes, at)
            if (size != null && at + size == bytes.size) {
                val page = bytes.copyOfRange(at, at + size)
                page[HEADER_TYPE] = (page[HEADER_TYPE].toInt() or EOS_FLAG).toByte()
                for (i in 0 until 4) page[CRC_OFFSET + i] = 0
                val crc = crc(page)
                for (i in 0 until 4) page[CRC_OFFSET + i] = (crc ushr (8 * i)).toByte()
                return at to page
            }
            at = lastIndexOf(bytes, CAPTURE, before = at)
        }
        return null
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, initial 0. */
    fun crc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }

    private fun pageSize(bytes: ByteArray, at: Int): Int? {
        if (at + HEADER_BYTES > bytes.size) return null
        val segments = bytes[at + SEGMENT_COUNT].toInt() and 0xFF
        if (at + HEADER_BYTES + segments > bytes.size) return null
        var body = 0
        for (i in 0 until segments) body += bytes[at + HEADER_BYTES + i].toInt() and 0xFF
        return HEADER_BYTES + segments + body
    }

    private fun lastIndexOf(bytes: ByteArray, pattern: ByteArray, before: Int = bytes.size): Int {
        var i = minOf(before - 1, bytes.size - pattern.size)
        while (i >= 0) {
            if ((pattern.indices).all { bytes[i + it] == pattern[it] }) return i
            i--
        }
        return -1
    }
}
