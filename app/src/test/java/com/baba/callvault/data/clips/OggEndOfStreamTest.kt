package com.baba.callvault.data.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OggEndOfStreamTest {

    /** The last two pages of a real CallVault WhatsApp recording (OP9, 2026-11-02), written by Android's muxer. */
    private val tail = "4f6767530000805101000000000051dcb0745c000000b159cd1a013df812cda242b02af7920d9078253831d252879e05c6473bfbcee6abb5d3604f4200d5ced055b059490d725fd5e6a67ed6da36d00678644c5b691fec48e54f6767530000405501000000000051dcb0745d000000ac961770013df8ab2c3c53349012fb00b014fb23dd823a54b266ee059f3e4e0c910acda3994ff599493f423309ef6b07a62be968ed60cfef40db4ad83aff52e5d77a47".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun storedCrc(page: ByteArray) =
        (0 until 4).fold(0) { acc, i -> acc or ((page[22 + i].toInt() and 0xFF) shl (8 * i)) }

    @Test fun `crc matches what Android wrote`() {
        val last = tail.copyOfRange(tail.size - 89, tail.size)
        val zeroed = last.copyOf().also { for (i in 22 until 26) it[i] = 0 }
        assertEquals(storedCrc(last), OggEndOfStream.crc(zeroed))
    }

    @Test fun `marks only the last page and keeps its crc valid`() {
        val (at, page) = OggEndOfStream.markLastPage(tail)!!
        assertEquals(tail.size - 89, at)
        assertEquals(0x04, page[5].toInt() and 0x04)
        val zeroed = page.copyOf().also { for (i in 22 until 26) it[i] = 0 }
        assertEquals(storedCrc(page), OggEndOfStream.crc(zeroed))
        // Everything but the flag and the crc is unchanged.
        val original = tail.copyOfRange(at, tail.size)
        (0 until page.size).filter { it != 5 && it !in 22 until 26 }.forEach { assertEquals(original[it], page[it]) }
    }

    @Test fun `a cut-off page is left alone`() =
        assertNull(OggEndOfStream.markLastPage(tail.copyOfRange(0, tail.size - 3)))

    @Test fun `no ogg page at all is left alone`() =
        assertNull(OggEndOfStream.markLastPage(ByteArray(64) { 7 }))
}
