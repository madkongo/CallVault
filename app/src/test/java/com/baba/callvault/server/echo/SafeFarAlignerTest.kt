package com.baba.callvault.server.echo

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFarAlignerTest {

    @Test
    fun `a wrong-sized chunk falls back to the raw far side for the rest of the call`() {
        val logs = mutableListOf<String>()
        val safe = SafeFarAligner(EchoAligner.CHUNK_FRAMES * 2) { logs += it }
        val tooShort = ByteArray(10)
        assertSame(tooShort, safe.align(tooShort, tooShort))
        val ok = ByteArray(EchoAligner.CHUNK_FRAMES * 2)
        assertSame(ok, safe.align(ok, ok))
        assertTrue(logs.single().contains("aligner failed"))
    }
}
