/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDeathRedeliveryTest {

    @Test
    fun `while recording, the first re-delivery comes after a short wait`() {
        assertEquals(1_000L, AppDeathRedelivery.nextDelayMs(attempt = 0, recording = true, lastDelivered = null))
    }

    @Test
    fun `a failed delivery is retried up to three attempts, then given up`() {
        assertEquals(2_000L, AppDeathRedelivery.nextDelayMs(1, true, false))
        assertEquals(2_000L, AppDeathRedelivery.nextDelayMs(2, true, false))
        assertNull(AppDeathRedelivery.nextDelayMs(3, true, false))
    }

    @Test
    fun `a delivered binder ends the retries`() {
        assertNull(AppDeathRedelivery.nextDelayMs(1, true, true))
    }

    @Test
    fun `an idle daemon never restarts a stopped app`() {
        assertNull(AppDeathRedelivery.nextDelayMs(0, recording = false, lastDelivered = null))
    }

    @Test
    fun `recording that ends between retries stops them`() {
        assertNull(AppDeathRedelivery.nextDelayMs(1, recording = false, lastDelivered = false))
    }

    @Test
    fun `only the uid-active transaction counts as the app becoming active`() {
        assertTrue(AppDeathRedelivery.isActiveTransaction(code = 2, activeCode = 2))
        assertFalse(AppDeathRedelivery.isActiveTransaction(code = 1, activeCode = 2))
        assertTrue("code unknown: fall back to the old behaviour", AppDeathRedelivery.isActiveTransaction(code = 7, activeCode = null))
    }
}
