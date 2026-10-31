/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What one loopback port turned out to be. */
enum class PortProbe {
    /** Nothing listening (refused), or the connect timed out. */
    CLOSED,

    /** Something listens, but it did not answer like adbd. */
    OPEN_NOT_ADB,

    /** adbd's Wireless-debugging port: it answered our plain `CNXN` with `STLS` ("switch to TLS"). */
    ADB_TLS,

    /** adbd's classic `adb tcpip` listener: it answered with `AUTH` or `CNXN`. */
    ADB_PLAIN,
}

/**
 * Asks a loopback port "are you adbd?" with the first packet any adb client sends.
 *
 * Issue #65: on some ROMs adbd's `_adb-tls-connect._tcp` service is never advertised (adbd announces it
 * through the old `mdnsd`, which those ROMs do not run), so the port has to be found some other way. The
 * Wireless-debugging port is a plain TCP listener that expects an adb `CNXN` first and answers it with
 * `STLS` before any TLS starts — so a plain `CNXN` is enough to recognise it, and nothing else on the phone
 * answers that way. Sending TLS first does not work: adbd reads it as a bad header and hangs up.
 *
 * The approach (plain CNXN, accept STLS) follows Stardust's `AdbPortFinder.kt`; this is our own code,
 * written from the adb protocol (AOSP `packages/modules/adb/protocol.txt`, `adb.h`), not a copy.
 *
 * Packet layout: a 24-byte little-endian header — command, arg0, arg1, payload length, payload checksum,
 * magic (`command xor 0xffffffff`) — then the payload.
 */
object AdbWireProbe {
    const val A_CNXN = 0x4e584e43
    const val A_STLS = 0x534c5453
    const val A_AUTH = 0x48545541

    /** adb protocol version with checksum skipping, the one current adb clients send. */
    const val A_VERSION = 0x01000001

    /** The payload size we offer; adbd only uses it to size later packets, which never come. */
    const val MAX_PAYLOAD = 256 * 1024

    const val HEADER_SIZE = 24

    /** A harmless identity, as `adb` itself sends `host::features=…`. */
    private const val PROBE_BANNER = "host::\u0000"

    /** The `CNXN` packet: header + banner. */
    fun cnxnPacket(): ByteArray {
        val payload = PROBE_BANNER.toByteArray(Charsets.UTF_8)
        val checksum = payload.fold(0) { sum, b -> sum + (b.toInt() and 0xff) }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(A_CNXN)
            .putInt(A_VERSION)
            .putInt(MAX_PAYLOAD)
            .putInt(payload.size)
            .putInt(checksum)
            .putInt(A_CNXN xor -1)
            .put(payload)
            .array()
    }

    /**
     * What a reply header says. Anything that is not a well-formed adb header — wrong size, or a magic
     * that does not match its command — is not adbd, whatever its first four bytes happen to be.
     */
    fun classify(header: ByteArray): PortProbe {
        if (header.size < HEADER_SIZE) return PortProbe.OPEN_NOT_ADB
        val buf = ByteBuffer.wrap(header, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.getInt(0)
        val magic = buf.getInt(20)
        if (magic != command xor -1) return PortProbe.OPEN_NOT_ADB
        return when (command) {
            A_STLS -> PortProbe.ADB_TLS
            A_AUTH, A_CNXN -> PortProbe.ADB_PLAIN
            else -> PortProbe.OPEN_NOT_ADB
        }
    }

    /**
     * Connects to `host:port`, sends [cnxnPacket] and classifies the reply. Blocking, bounded by the two
     * timeouts (a refused loopback connect returns at once). Never throws.
     */
    fun probe(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): PortProbe {
        val socket = Socket()
        try {
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                return PortProbe.CLOSED
            }
            return try {
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.getOutputStream().apply { write(cnxnPacket()); flush() }
                classify(readHeader(socket.getInputStream()))
            } catch (e: IOException) {
                PortProbe.OPEN_NOT_ADB
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads up to [HEADER_SIZE] bytes; fewer when the peer hangs up first. */
    private fun readHeader(input: InputStream): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val n = input.read(header, read, HEADER_SIZE - read)
            if (n < 0) break
            read += n
        }
        return header.copyOf(read)
    }
}
