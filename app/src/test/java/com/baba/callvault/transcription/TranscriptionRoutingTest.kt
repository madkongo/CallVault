/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

import androidx.work.NetworkType
import com.baba.callvault.transcription.cloud.TranscriptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device / cloud decisions (issue #48), without WorkManager or a model. */
class TranscriptionRoutingTest {

    @Test
    fun `a cloud service never waits for a local model`() {
        assertEquals(TranscriptionRouting.Route.CLOUD, TranscriptionRouting.route(TranscriptionService.GROQ, modelInstalled = false))
        assertEquals(TranscriptionRouting.Route.CLOUD, TranscriptionRouting.route(TranscriptionService.CUSTOM, modelInstalled = true))
    }

    @Test
    fun `on this phone still waits for its model`() {
        assertEquals(TranscriptionRouting.Route.WAIT_FOR_MODEL, TranscriptionRouting.route(TranscriptionService.ON_DEVICE, false))
        assertEquals(TranscriptionRouting.Route.ON_DEVICE, TranscriptionRouting.route(TranscriptionService.ON_DEVICE, true))
    }

    @Test
    fun `a cloud run needs wifi unless mobile data was allowed`() {
        assertEquals(NetworkType.UNMETERED, TranscriptionRouting.constraints(true, allowMobileData = false, requiresCharging = false).networkType)
        assertEquals(NetworkType.CONNECTED, TranscriptionRouting.constraints(true, allowMobileData = true, requiresCharging = false).networkType)
    }

    @Test
    fun `an on-device run needs no network whatever the mobile data switch says`() {
        assertEquals(NetworkType.NOT_REQUIRED, TranscriptionRouting.constraints(false, allowMobileData = true, requiresCharging = true).networkType)
    }

    @Test
    fun `charging follows the setting on both paths`() {
        assertTrue(TranscriptionRouting.constraints(true, false, requiresCharging = true).requiresCharging)
        assertFalse(TranscriptionRouting.constraints(false, false, requiresCharging = false).requiresCharging)
    }

    @Test
    fun `passing cloud failures are retried a few times, then fail`() {
        assertTrue(TranscriptionRouting.mayRetryCloud(0))
        assertTrue(TranscriptionRouting.mayRetryCloud(TranscriptionRouting.MAX_CLOUD_ATTEMPTS - 2))
        assertFalse(TranscriptionRouting.mayRetryCloud(TranscriptionRouting.MAX_CLOUD_ATTEMPTS - 1))
    }
}
