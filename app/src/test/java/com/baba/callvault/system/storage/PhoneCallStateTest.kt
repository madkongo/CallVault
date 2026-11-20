/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.media.AudioManager
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Second review 2026-11-20 (S6): "unknown reads as a call" skipped every check for ever when the phone-state permission
 * was revoked, and the publisher rescheduled every 5.5 minutes indefinitely.
 */
class PhoneCallStateTest {

    @Test
    fun `the audio mode decides first, the carrier state is only the fallback`() {
        assertTrue(PhoneCallState.decide(AudioManager.MODE_IN_COMMUNICATION, carrierState = null))
        assertTrue(PhoneCallState.decide(AudioManager.MODE_IN_CALL, carrierState = null))
        assertTrue(PhoneCallState.decide(AudioManager.MODE_NORMAL, TelephonyManager.CALL_STATE_OFFHOOK))
        assertFalse(PhoneCallState.decide(AudioManager.MODE_NORMAL, TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun `nothing readable is not a call`() {
        assertFalse(PhoneCallState.decide(audioMode = null, carrierState = null))
        assertFalse(PhoneCallState.decide(AudioManager.MODE_NORMAL, carrierState = null))
    }

    @Test
    fun `the publisher backs off and stops instead of retrying every few minutes for ever`() {
        assertEquals(WaitingPolicy.FIRST_RETRY_MS, WaitingPolicy.retryDelayMs(0))
        assertEquals(WaitingPolicy.FIRST_RETRY_MS * 2, WaitingPolicy.retryDelayMs(1))
        assertEquals(WaitingPolicy.MAX_RETRY_MS, WaitingPolicy.retryDelayMs(20))
        assertTrue(WaitingPolicy.shouldRetry(WaitingPolicy.MAX_RETRIES - 1))
        assertFalse(WaitingPolicy.shouldRetry(WaitingPolicy.MAX_RETRIES))
    }

    @Test
    fun `a call that seems never to end stops blocking finished saves after a few retries`() {
        assertFalse(WaitingPolicy.ignoreCallState(attempt = 0))
        assertTrue(WaitingPolicy.ignoreCallState(attempt = WaitingPolicy.IGNORE_CALL_AFTER_ATTEMPTS))
    }

    @Test
    fun `a fresh save and the one pending retry each replace their own kind, so there is never a second chain`() {
        // Merge gate: an old run's late retry appended behind a fresh run made a second chain.
        assertTrue(WaitingPolicy.workName(fresh = true) != WaitingPolicy.workName(fresh = false))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, WaitingPolicy.workPolicy(fresh = true))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, WaitingPolicy.workPolicy(fresh = false))
    }

    @Test
    fun `when the retries stop, the count starts again for the next trigger`() {
        assertEquals(0, WaitingPolicy.attemptAfterRun(attempt = WaitingPolicy.MAX_RETRIES, retryLater = true))
    }

    @Test
    fun `the attempt count survives fresh triggers until a run needs no retry`() {
        // So the stuck-call override after a few retries can be reached even while checks keep triggering fresh runs.
        assertEquals(4, WaitingPolicy.attemptAfterRun(attempt = 3, retryLater = true))
        assertEquals(0, WaitingPolicy.attemptAfterRun(attempt = 3, retryLater = false))
    }
}
