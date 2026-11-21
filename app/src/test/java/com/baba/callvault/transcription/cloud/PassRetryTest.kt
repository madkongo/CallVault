package com.baba.callvault.transcription.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** C168: one busy pass is retried in place, boundedly and stoppably. */
class PassRetryTest {

    private fun busy() = CloudError.forHttpStatus(503, "X")

    @Test
    fun `a pass that fails once then works is retried and returns`() {
        var calls = 0
        val result = PassRetry.run("p", { false }, { }) { if (++calls == 1) throw busy() else "ok" }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `retries are bounded`() {
        var calls = 0
        try {
            PassRetry.run("p", { false }, { }) { calls++; throw busy() }
            fail()
        } catch (e: CloudError) {
            assertEquals(PassRetry.MAX_TRIES, calls)
        }
    }

    @Test
    fun `an error that waiting cannot fix is thrown at once`() {
        var calls = 0
        try {
            PassRetry.run("p", { false }, { }) { calls++; throw CloudError.forHttpStatus(401, "X") }
            fail()
        } catch (e: CloudError) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `stop ends the wait and the retries`() {
        var calls = 0
        var stopped = false
        try {
            PassRetry.run("p", { stopped }, { stopped = true }) { calls++; throw busy() }
            fail()
        } catch (e: CloudError) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `retry-after is honoured and capped`() {
        assertEquals(30_000L, PassRetry.waitFor(1, 30_000L))
        assertEquals(PassRetry.MAX_WAIT_MS, PassRetry.waitFor(1, 900_000L))
        assertEquals(4_000L, PassRetry.waitFor(1, null))
        assertEquals(8_000L, PassRetry.waitFor(2, null))
        assertEquals(30_000L, PassRetry.parseRetryAfter("30"))
        assertEquals(PassRetry.MAX_WAIT_MS, PassRetry.parseRetryAfter("3600"))
        assertNull(PassRetry.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(PassRetry.parseRetryAfter(null))
    }

    @Test
    fun `the wait the service asked for is slept`() {
        var slept = 0L
        var calls = 0
        PassRetry.run("p", { false }, { slept += it }) { if (++calls == 1) throw busy().withRetryAfter(2_000L) else 1 }
        assertEquals(2_000L, slept)
    }
}
