/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Issue #65: recognising adbd's Wireless-debugging port by its answer to a plain CNXN. */
class AdbWireProbeTest {

    private fun header(command: Int, magic: Int = command xor -1): ByteArray =
        ByteBuffer.allocate(AdbWireProbe.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(0x01000001).putInt(0).putInt(0).putInt(0).putInt(magic).array()

    @Test
    fun `the CNXN packet is a little-endian adb header followed by the banner`() {
        val packet = AdbWireProbe.cnxnPacket()
        val banner = "host::\u0000".toByteArray()
        val expectedHeader = byteArrayOf(
            0x43, 0x4e, 0x58, 0x4e, // "CNXN"
            0x01, 0x00, 0x00, 0x01, // version 0x01000001
            0x00, 0x00, 0x04, 0x00, // max payload 256 KiB
            0x07, 0x00, 0x00, 0x00, // payload length 7
        )
        assertArrayEquals(expectedHeader, packet.copyOfRange(0, 16))
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(banner.sumOf { it.toInt() and 0xff }, buf.getInt(16))
        assertEquals(AdbWireProbe.A_CNXN xor -1, buf.getInt(20))
        assertArrayEquals(banner, packet.copyOfRange(24, packet.size))
    }

    @Test
    fun `STLS is the Wireless-debugging port`() {
        assertEquals(PortProbe.ADB_TLS, AdbWireProbe.classify(header(AdbWireProbe.A_STLS)))
    }

    @Test
    fun `AUTH or CNXN is a classic adb listener`() {
        assertEquals(PortProbe.ADB_PLAIN, AdbWireProbe.classify(header(AdbWireProbe.A_AUTH)))
        assertEquals(PortProbe.ADB_PLAIN, AdbWireProbe.classify(header(AdbWireProbe.A_CNXN)))
    }

    @Test
    fun `a bad magic, a short reply or another command is not adbd`() {
        assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.classify(header(AdbWireProbe.A_STLS, magic = 0)))
        assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.classify(header(AdbWireProbe.A_STLS).copyOf(12)))
        assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.classify(header(0x45534c43))) // "CLSE"
        assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.classify("HTTP/1.1 400 Bad Request\r\n".toByteArray()))
    }

    /** A one-shot local server that reads the probe's packet and answers with [reply]. */
    private fun serve(reply: ByteArray?): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { client ->
                    val got = ByteArray(AdbWireProbe.cnxnPacket().size)
                    var read = 0
                    while (read < got.size) {
                        val n = client.getInputStream().read(got, read, got.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (reply != null) client.getOutputStream().apply { write(reply); flush() }
                }
            }
        }
        return server
    }

    @Test
    fun `a local listener that answers STLS is found as the TLS port`() {
        serve(header(AdbWireProbe.A_STLS)).use { server ->
            assertEquals(PortProbe.ADB_TLS, AdbWireProbe.probe("127.0.0.1", server.localPort, 500, 1_000))
        }
    }

    @Test
    fun `a local listener that answers something else, or hangs up, is open but not adbd`() {
        serve("SSH-2.0-OpenSSH_9.0\r\n".toByteArray() + ByteArray(8)).use { server ->
            assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.probe("127.0.0.1", server.localPort, 500, 1_000))
        }
        serve(null).use { server ->
            assertEquals(PortProbe.OPEN_NOT_ADB, AdbWireProbe.probe("127.0.0.1", server.localPort, 500, 1_000))
        }
    }

    @Test
    fun `a closed port reads closed`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(PortProbe.CLOSED, AdbWireProbe.probe("127.0.0.1", port, 500, 1_000))
    }
}
