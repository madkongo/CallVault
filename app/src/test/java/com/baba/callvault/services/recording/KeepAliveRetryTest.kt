/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R3: after a refused foreground start the keep-alive is retried a few times, never forever. */
class KeepAliveRetryTest {

    @Test
    fun `the first retry comes after a few minutes`() {
        assertEquals(KeepAliveRetry.RETRY_DELAY_MS, KeepAliveRetry.delayForAttempt(1))
        assertTrue(KeepAliveRetry.RETRY_DELAY_MS in 60_000L..600_000L)
    }

    @Test
    fun `retries stop after the limit`() {
        assertEquals(KeepAliveRetry.RETRY_DELAY_MS, KeepAliveRetry.delayForAttempt(KeepAliveRetry.MAX_ATTEMPTS))
        assertNull(KeepAliveRetry.delayForAttempt(KeepAliveRetry.MAX_ATTEMPTS + 1))
    }

    @Test
    fun `a nonsense attempt number is refused`() {
        assertNull(KeepAliveRetry.delayForAttempt(0))
        assertNull(KeepAliveRetry.delayForAttempt(-3))
    }

    @Test
    fun `a running keep-alive needs no retry`() {
        assertFalse(KeepAliveRetry.needsRetry(running = true, userStopped = false))
    }

    @Test
    fun `a keep-alive the user switched off is not retried`() {
        assertFalse(KeepAliveRetry.needsRetry(running = false, userStopped = true))
    }

    @Test
    fun `a keep-alive that is down and wanted is retried`() {
        assertTrue(KeepAliveRetry.needsRetry(running = false, userStopped = false))
    }

    @Test
    fun `a refusal at app start keeps a queued chain, every other caller replaces it`() {
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, KeepAliveRetry.workPolicy(fromAppStart = true))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, KeepAliveRetry.workPolicy(fromAppStart = false))
    }

    @Test
    fun `the attempt cap is real - no delay past the last attempt`() {
        assertEquals(null, KeepAliveRetry.delayForAttempt(KeepAliveRetry.MAX_ATTEMPTS + 1))
    }
}
