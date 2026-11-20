package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderApprovalCallsTest {

    private val logged = mutableListOf<String>()
    private val calls = RecorderApprovalCalls(warn = { logged += it })

    @Test
    fun `an approval that works is passed through and logs nothing`() {
        assertEquals("APPROVED", calls.approveNetwork { NetworkApproval.APPROVED })
        val kept = KeptSwitchResult(KeptSwitchOutcome.APPROVED_RESTORED_OFF, "k", "m", 5L).encode()
        assertEquals(kept, calls.approveKeepingSwitch { kept })
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `an approval object that cannot load answers FAILED and logs the real cause`() {
        val answer = calls.approveNetwork { throw NoClassDefFoundError("Could not initialize class WirelessDebuggingApproval") }

        assertEquals("FAILED", answer)
        assertEquals(1, logged.size)
        assertTrue(logged[0], logged[0].contains("NoClassDefFoundError: Could not initialize class WirelessDebuggingApproval"))
    }

    @Test
    fun `a keep-the-switch approval that throws answers APPROVAL_FAILED the app can decode, and logs the cause chain`() {
        val answer = calls.approveKeepingSwitch {
            throw ExceptionInInitializerError(java.util.regex.PatternSyntaxException("Syntax error", "(?:}|x)", 3))
        }

        assertEquals(KeptSwitchOutcome.APPROVAL_FAILED, KeptSwitchResult.decode(answer)?.outcome)
        assertTrue(logged.single(), logged.single().contains("ExceptionInInitializerError, caused by PatternSyntaxException: Syntax error"))
    }

    @Test
    fun `an out-of-memory or stack overflow is logged, then thrown on, never turned into FAILED`() {
        val oom = OutOfMemoryError("heap")
        val thrown = runCatching { calls.approveNetwork { throw oom } }.exceptionOrNull()
        assertSame(oom, thrown)
        val overflow = StackOverflowError()
        assertSame(overflow, runCatching { calls.approveKeepingSwitch { throw overflow } }.exceptionOrNull())
        assertEquals(2, logged.size)
        assertTrue(logged[0], logged[0].contains("OutOfMemoryError: heap"))
    }
}
